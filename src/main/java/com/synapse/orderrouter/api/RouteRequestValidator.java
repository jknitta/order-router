package com.synapse.orderrouter.api;

import com.synapse.orderrouter.api.RouteRequest.LineItem;
import com.synapse.orderrouter.catalog.Catalog;
import com.synapse.orderrouter.catalog.ServiceArea;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Checks what can be decided from the request alone. Whether suppliers exist is the router's job. */
@Component
public class RouteRequestValidator {

    private static final BigDecimal MAX_QUANTITY = BigDecimal.valueOf(Integer.MAX_VALUE);

    private final Catalog catalog;

    public RouteRequestValidator(Catalog catalog) {
        this.catalog = catalog;
    }

    /** Returns one message per problem; an empty list means the request is well-formed. */
    public List<String> validate(RouteRequest request) {
        List<String> errors = new ArrayList<>();
        boolean hasItems = request.items() != null && !request.items().isEmpty();
        if (!hasItems) {
            errors.add("Order must include at least one line item.");
        }
        if (ServiceArea.normalize(request.customerZip()).isEmpty()) {
            errors.add("Order must include a valid customer_zip.");
        }
        if (hasItems) {
            if (request.items().stream().anyMatch(Objects::isNull)) {
                errors.add("Order items must not contain null entries.");
            }
            request.items().forEach(item -> validateItem(item, errors));
            validateSummedQuantities(request.items(), errors);
        }
        return errors;
    }

    /** Lines for the same product are merged by the router, so their total must fit too. */
    private static void validateSummedQuantities(List<LineItem> items, List<String> errors) {
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        items.stream()
                .filter(item -> item != null && item.productCode() != null && isWholePositive(item.quantity()))
                .forEach(item -> totals.merge(item.productCode(), item.quantity(), BigDecimal::add));
        totals.forEach((productCode, total) -> {
            boolean alreadyReported = items.stream().anyMatch(item -> item != null
                    && productCode.equals(item.productCode()) && isWholePositive(item.quantity())
                    && item.quantity().compareTo(MAX_QUANTITY) > 0);
            if (total.compareTo(MAX_QUANTITY) > 0 && !alreadyReported) {
                errors.add(tooLarge(productCode));
            }
        });
    }

    private static boolean isWholePositive(BigDecimal quantity) {
        return quantity != null && quantity.signum() > 0 && quantity.stripTrailingZeros().scale() <= 0;
    }

    private static String tooLarge(String productCode) {
        return "Quantity for " + productCode + " is too large (maximum is " + Integer.MAX_VALUE + ").";
    }

    private void validateItem(LineItem item, List<String> errors) {
        if (item == null) {
            return;
        }
        if (item.productCode() == null || !catalog.productCategories().containsKey(item.productCode())) {
            errors.add("Unknown product code: " + item.productCode() + ".");
        }
        validateQuantity(item, errors);
    }

    private static void validateQuantity(LineItem item, List<String> errors) {
        BigDecimal quantity = item.quantity();
        if (quantity == null || quantity.signum() <= 0) {
            errors.add("Quantity for " + item.productCode() + " must be a positive integer.");
        } else if (quantity.stripTrailingZeros().scale() > 0) {
            errors.add("Quantity for " + item.productCode() + " must be a whole number, but was "
                    + quantity.toPlainString() + ".");
        } else if (quantity.compareTo(MAX_QUANTITY) > 0) {
            errors.add(tooLarge(item.productCode()));
        }
    }
}
