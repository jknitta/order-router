package com.synapse.orderrouter.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.synapse.orderrouter.routing.Plan;
import com.synapse.orderrouter.routing.Plan.Assignment;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** The JSON body returned by POST /api/route. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RouteResponse(boolean feasible, List<SupplierShipment> routing, List<String> errors) {

    public record SupplierShipment(String supplierId, String supplierName, List<ShipmentItem> items) {
    }

    public record ShipmentItem(String productCode, int quantity, String category, String fulfillmentMode) {
    }

    /** One shipment per supplier, ordered by supplier id, each listing its items in plan order. */
    public static RouteResponse success(Plan plan) {
        Map<String, List<Assignment>> bySupplierId = plan.assignments().stream()
                .collect(Collectors.groupingBy(a -> a.supplier().id(), TreeMap::new, Collectors.toList()));
        List<SupplierShipment> routing = bySupplierId.values().stream()
                .map(assignments -> new SupplierShipment(
                        assignments.get(0).supplier().id(),
                        assignments.get(0).supplier().name(),
                        assignments.stream().map(RouteResponse::toShipmentItem).toList()))
                .toList();
        return new RouteResponse(true, routing, null);
    }

    public static RouteResponse failure(List<String> errors) {
        return new RouteResponse(false, null, errors);
    }

    private static ShipmentItem toShipmentItem(Assignment a) {
        return new ShipmentItem(a.productCode(), a.quantity(), a.category(), a.local() ? "local" : "mail_order");
    }
}
