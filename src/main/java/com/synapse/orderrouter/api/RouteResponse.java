package com.synapse.orderrouter.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.synapse.orderrouter.routing.Plan;
import java.util.List;

/** The JSON body returned by POST /api/route. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RouteResponse(boolean feasible, List<SupplierShipment> routing, List<String> errors) {

    public record SupplierShipment(String supplierId, String supplierName, List<ShipmentItem> items) {
    }

    public record ShipmentItem(String productCode, int quantity, String category, String fulfillmentMode) {
    }

    public static RouteResponse success(Plan plan) {
        throw new UnsupportedOperationException("not implemented");
    }

    public static RouteResponse failure(List<String> errors) {
        throw new UnsupportedOperationException("not implemented");
    }
}
