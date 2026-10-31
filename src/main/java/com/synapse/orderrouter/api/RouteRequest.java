package com.synapse.orderrouter.api;

import com.synapse.orderrouter.routing.Order;
import java.util.List;

/** The JSON body of POST /api/route. */
public record RouteRequest(String orderId, String customerZip, boolean mailOrder, List<LineItem> items) {

    public record LineItem(String productCode, Integer quantity) {
    }

    /** Converts a request that has passed validation into the order the router works with. */
    public Order toOrder() {
        throw new UnsupportedOperationException("not implemented");
    }
}
