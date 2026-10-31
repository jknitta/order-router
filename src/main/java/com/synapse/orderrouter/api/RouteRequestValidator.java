package com.synapse.orderrouter.api;

import com.synapse.orderrouter.api.RouteRequest.LineItem;
import com.synapse.orderrouter.catalog.Catalog;
import com.synapse.orderrouter.catalog.ServiceArea;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
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
        }
        return errors;
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
            errors.add("Quantity for " + item.productCode() + " is too large (maximum is " + Integer.MAX_VALUE
                    + ").");
        }
    }
}
