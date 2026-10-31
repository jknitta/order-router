package com.synapse.orderrouter.routing;

import java.util.List;

/** What the router needs to know about an order. The ZIP is already normalized to 5 digits. */
public record Order(String zip, boolean mailOrder, List<Line> lines) {

    public record Line(String productCode, int quantity) {
    }
}
