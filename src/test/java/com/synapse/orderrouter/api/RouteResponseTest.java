package com.synapse.orderrouter.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import com.synapse.orderrouter.api.RouteResponse.ShipmentItem;
import com.synapse.orderrouter.api.RouteResponse.SupplierShipment;
import com.synapse.orderrouter.catalog.ServiceArea;
import com.synapse.orderrouter.catalog.Supplier;
import com.synapse.orderrouter.routing.Plan;
import com.synapse.orderrouter.routing.Plan.Assignment;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RouteResponseTest {

    private static final Supplier ALPHA = supplier("SUP-001", "Alpha");
    private static final Supplier BETA = supplier("SUP-002", "Beta");

    private static Supplier supplier(String id, String name) {
        return new Supplier(id, name, ServiceArea.parse(""), Set.of("cane"), 7, false);
    }

    // BETA is listed first and appears twice, so the plan is deliberately not grouped or sorted.
    private final Plan plan = new Plan(List.of(
            new Assignment(BETA, "P-OXY", 1, "oxygen", false),
            new Assignment(ALPHA, "P-CANE", 3, "cane", true),
            new Assignment(BETA, "P-WC", 2, "wheelchair", false)));

    @Test
    void successIsFeasibleWithRoutingAndNoErrors() {
        RouteResponse response = RouteResponse.success(plan);

        assertThat(response.feasible()).isTrue();
        assertThat(response.errors()).isNull();
        assertThat(response.routing()).isNotNull();
    }

    @Test
    void successGroupsItemsBySupplierOrderedBySupplierId() {
        RouteResponse response = RouteResponse.success(plan);

        assertThat(response.routing()).extracting(SupplierShipment::supplierId, SupplierShipment::supplierName)
                .containsExactly(tuple("SUP-001", "Alpha"), tuple("SUP-002", "Beta"));
    }

    @Test
    void successKeepsEachSuppliersItemsInPlanOrderWithQuantityAndCategory() {
        SupplierShipment beta = RouteResponse.success(plan).routing().get(1);

        assertThat(beta.items())
                .extracting(ShipmentItem::productCode, ShipmentItem::quantity, ShipmentItem::category)
                .containsExactly(tuple("P-OXY", 1, "oxygen"), tuple("P-WC", 2, "wheelchair"));
    }

    @Test
    void localAssignmentsAreReportedAsLocalAndOthersAsMailOrder() {
        List<SupplierShipment> routing = RouteResponse.success(plan).routing();

        assertThat(routing.get(0).items()).extracting(ShipmentItem::fulfillmentMode).containsExactly("local");
        assertThat(routing.get(1).items()).extracting(ShipmentItem::fulfillmentMode)
                .containsExactly("mail_order", "mail_order");
    }

    @Test
    void failureIsInfeasibleWithErrorsAndNoRouting() {
        RouteResponse response = RouteResponse.failure(List.of("first problem", "second problem"));

        assertThat(response.feasible()).isFalse();
        assertThat(response.routing()).isNull();
        assertThat(response.errors()).containsExactly("first problem", "second problem");
    }
}
