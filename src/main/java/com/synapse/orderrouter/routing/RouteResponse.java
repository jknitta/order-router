package com.synapse.orderrouter.routing;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record RouteResponse(boolean feasible, List<SupplierShipment> routing, List<String> errors) {

    public record SupplierShipment(String supplierId, String supplierName, List<ShipmentItem> items) {
    }

    public record ShipmentItem(String productCode, int quantity, String category, String fulfillmentMode) {
    }
}
