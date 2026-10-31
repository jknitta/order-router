package com.synapse.orderrouter.routing;

import java.util.List;

public record RouteRequest(String orderId, String customerZip, boolean mailOrder, List<LineItem> items) {

    public record LineItem(String productCode, Integer quantity) {
    }
}
