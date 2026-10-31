package com.synapse.orderrouter.api;

import com.synapse.orderrouter.catalog.ServiceArea;
import com.synapse.orderrouter.routing.Order;
import java.util.List;

/** The JSON body of POST /api/route. */
public record RouteRequest(String orderId, String customerZip, boolean mailOrder, List<LineItem> items) {

    public record LineItem(String productCode, Integer quantity) {
    }

    /** Converts a request that has passed validation into the order the router works with. */
    public Order toOrder() {
        String zip = ServiceArea.normalize(customerZip).orElseThrow();
        List<Order.Line> lines = items.stream()
                .map(item -> new Order.Line(item.productCode(), item.quantity()))
                .toList();
        return new Order(zip, mailOrder, lines);
    }
}
