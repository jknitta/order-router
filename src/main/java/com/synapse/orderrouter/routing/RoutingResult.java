package com.synapse.orderrouter.routing;

import java.util.List;

/** The outcome of routing an order: a plan, or the reasons no plan exists. */
public sealed interface RoutingResult {

    record Routed(Plan plan) implements RoutingResult {
    }

    record Unroutable(List<String> errors) implements RoutingResult {
    }
}
