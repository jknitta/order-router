package com.synapse.orderrouter.api;

import com.synapse.orderrouter.api.RouteRequest.LineItem;
import com.synapse.orderrouter.catalog.Catalog;
import com.synapse.orderrouter.catalog.ServiceArea;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Checks what can be decided from the request alone. Whether suppliers exist is the router's job. */
@Component
public class RouteRequestValidator {

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
        if (item.quantity() == null || item.quantity() <= 0) {
            errors.add("Quantity for " + item.productCode() + " must be a positive integer.");
        }
    }
}
