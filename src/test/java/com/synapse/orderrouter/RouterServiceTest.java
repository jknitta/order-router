package com.synapse.orderrouter;

import static org.assertj.core.api.Assertions.assertThat;

import com.synapse.orderrouter.RouteRequest.LineItem;
import com.synapse.orderrouter.RouteResponse.ShipmentItem;
import com.synapse.orderrouter.RouteResponse.SupplierShipment;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RouterServiceTest {

    private static final String ZIP = "10015";
    private static final String ELSEWHERE = "90210";

    private static final Map<String, String> PRODUCTS = Map.of(
            "P-CANE", "cane",
            "P-CPAP", "cpap",
            "P-OXY", "oxygen",
            "P-WC", "wheelchair",
            "P-WALK", "walker",
            "P-COM", "commode");

    private static Supplier supplier(String id, String zips, String categories, double score, boolean canMail) {
        return new Supplier(id, "Name " + id, ServiceArea.parse(zips),
                Set.of(categories.split(",")), score, canMail);
    }

    private static RouteResponse route(RouteRequest request, Supplier... suppliers) {
        return new RouterService(new Catalog(List.of(suppliers), PRODUCTS)).route(request);
    }

    private static RouteRequest order(boolean mailOrder, String... productCodes) {
        List<LineItem> items = Arrays.stream(productCodes).map(code -> new LineItem(code, 1)).toList();
        return new RouteRequest("ORD-T", ZIP, mailOrder, items);
    }

    private static SupplierShipment shipmentOf(RouteResponse response, String supplierId) {
        return response.routing().stream()
                .filter(s -> s.supplierId().equals(supplierId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no shipment from " + supplierId));
    }

    private static List<String> supplierIds(RouteResponse response) {
        return response.routing().stream().map(SupplierShipment::supplierId).toList();
    }

    // --- eligibility ---

    @Test
    void whenNotMailOrderASupplierThatDoesNotServeTheZipIsNeverChosen() {
        RouteResponse response = route(order(false, "P-CANE"),
                supplier("SUP-001", ELSEWHERE, "cane", 10, true),
                supplier("SUP-002", ZIP, "cane", 5, false));

        assertThat(supplierIds(response)).containsExactly("SUP-002");
    }

    @Test
    void whenNotMailOrderAndNobodyServesTheZipTheOrderIsInfeasible() {
        RouteResponse response = route(order(false, "P-CANE"),
                supplier("SUP-001", ELSEWHERE, "cane", 10, true));

        assertThat(response.feasible()).isFalse();
    }

    @Test
    void whenMailOrderASupplierOutsideTheZipThatShipsIsEligible() {
        RouteResponse response = route(order(true, "P-CANE"),
                supplier("SUP-001", ELSEWHERE, "cane", 7, true));

        assertThat(response.feasible()).isTrue();
        assertThat(supplierIds(response)).containsExactly("SUP-001");
    }

    @Test
    void whenMailOrderASupplierOutsideTheZipThatDoesNotShipIsNotEligible() {
        RouteResponse response = route(order(true, "P-CANE"),
                supplier("SUP-001", ELSEWHERE, "cane", 7, false));

        assertThat(response.feasible()).isFalse();
    }

    @Test
    void aSupplierMustHandleTheItemsCategory() {
        RouteResponse response = route(order(false, "P-CANE", "P-OXY"),
                supplier("SUP-001", ZIP, "cane", 7, false));

        assertThat(response.feasible()).isFalse();
    }

    @Test
    void anItemNoSupplierCanHandleFailsTheWholeOrderAndIsNamed() {
        RouteResponse response = route(order(false, "P-CANE", "P-OXY"),
                supplier("SUP-001", ZIP, "cane", 7, false));

        assertThat(response.feasible()).isFalse();
        assertThat(response.routing()).isNull();
        assertThat(response.errors()).hasSize(1);
        assertThat(response.errors().get(0)).contains("P-OXY");
    }

    // --- fewest shipments ---

    @Test
    void oneSupplierCoveringEverythingBeatsTwoEvenIfTheTwoAreRatedHigher() {
        RouteResponse response = route(order(false, "P-CANE", "P-OXY"),
                supplier("SUP-001", ZIP, "cane,oxygen", 5, false),
                supplier("SUP-002", ZIP, "cane", 9, false),
                supplier("SUP-003", ZIP, "oxygen", 9, false));

        assertThat(supplierIds(response)).containsExactly("SUP-001");
    }

    @Test
    void whenNoSingleSupplierCoversTheOrderTheMinimumNumberOfSuppliersIsUsed() {
        // Items 1-6. A greedy pick of the biggest supplier (SUP-001, items 1-4) first would end up with 3 shipments.
        // The minimum is 2: SUP-002 (1,3,5) + SUP-003 (2,4,6).
        RouteResponse response = route(order(false, "P-CANE", "P-CPAP", "P-OXY", "P-WC", "P-WALK", "P-COM"),
                supplier("SUP-001", ZIP, "cane,cpap,oxygen,wheelchair", 7, false),
                supplier("SUP-002", ZIP, "cane,oxygen,walker", 7, false),
                supplier("SUP-003", ZIP, "cpap,wheelchair,commode", 7, false));

        assertThat(supplierIds(response)).containsExactlyInAnyOrder("SUP-002", "SUP-003");
    }

    @Test
    void duplicateLinesForTheSameProductAreSummed() {
        RouteRequest request = new RouteRequest("ORD-T", ZIP, false,
                List.of(new LineItem("P-CANE", 1), new LineItem("P-CANE", 2)));

        RouteResponse response = route(request, supplier("SUP-001", ZIP, "cane", 7, false));

        assertThat(shipmentOf(response, "SUP-001").items()).extracting(ShipmentItem::productCode, ShipmentItem::quantity)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("P-CANE", 3));
    }

    // --- quality, local preference, tie-breaks (single supplier) ---

    @Test
    void higherScoreWinsAmongSingleSupplierOptions() {
        RouteResponse response = route(order(false, "P-CANE"),
                supplier("SUP-001", ZIP, "cane", 6, false),
                supplier("SUP-002", ZIP, "cane", 8, false));

        assertThat(supplierIds(response)).containsExactly("SUP-002");
    }

    @Test
    void unratedSupplierRanksBelowASevenButAboveAFour() {
        Supplier unrated = supplier("SUP-001", ZIP, "cane", Supplier.UNRATED_SCORE, false);

        RouteResponse againstSeven = route(order(false, "P-CANE"), unrated,
                supplier("SUP-002", ZIP, "cane", 7, false));
        RouteResponse againstFour = route(order(false, "P-CANE"), unrated,
                supplier("SUP-002", ZIP, "cane", 4, false));

        assertThat(supplierIds(againstSeven)).containsExactly("SUP-002");
        assertThat(supplierIds(againstFour)).containsExactly("SUP-001");
    }

    @Test
    void exactScoreTieGoesToTheLocalSupplierEvenWithAHigherId() {
        RouteResponse response = route(order(true, "P-CANE"),
                supplier("SUP-001", ELSEWHERE, "cane", 7, true),
                supplier("SUP-002", ZIP, "cane", 7, false));

        assertThat(supplierIds(response)).containsExactly("SUP-002");
    }

    @Test
    void scoresThatDifferEvenSlightlyAreNotATie() {
        RouteResponse response = route(order(true, "P-CANE"),
                supplier("SUP-001", ZIP, "cane", 6.9, false),
                supplier("SUP-002", ELSEWHERE, "cane", 7.0, true));

        assertThat(supplierIds(response)).containsExactly("SUP-002");
    }

    @Test
    void aFullTieGoesToTheLowestSupplierIdRegardlessOfInputOrder() {
        RouteResponse response = route(order(false, "P-CANE"),
                supplier("SUP-002", ZIP, "cane", 7, false),
                supplier("SUP-001", ZIP, "cane", 7, false));

        assertThat(supplierIds(response)).containsExactly("SUP-001");
    }

    // --- ranking of equally small supplier sets ---

    @Test
    void equallySmallSetsAreRankedByHigherAverageScore() {
        RouteResponse response = route(order(false, "P-CANE", "P-OXY"),
                supplier("SUP-001", ZIP, "cane", 9, false),
                supplier("SUP-002", ZIP, "oxygen", 5, false),
                supplier("SUP-003", ZIP, "cane", 8, false),
                supplier("SUP-004", ZIP, "oxygen", 8, false));

        assertThat(supplierIds(response)).containsExactlyInAnyOrder("SUP-003", "SUP-004");
    }

    @Test
    void whenAveragesTieTheSetWithMoreLocalItemsWins() {
        // Both sets average 7. The lower-id set has one non-local supplier, so the all-local set wins.
        RouteResponse response = route(order(true, "P-CANE", "P-OXY"),
                supplier("SUP-001", ZIP, "cane", 8, false),
                supplier("SUP-002", ELSEWHERE, "oxygen", 6, true),
                supplier("SUP-005", ZIP, "cane", 7, false),
                supplier("SUP-006", ZIP, "oxygen", 7, false));

        assertThat(supplierIds(response)).containsExactlyInAnyOrder("SUP-005", "SUP-006");
    }

    @Test
    void anItemEitherSupplierInTheChosenSetCouldTakeGoesToTheHigherRated() {
        // SUP-001 (cane, oxygen) and SUP-002 (oxygen, wheelchair) are the only 2-supplier cover.
        RouteResponse response = route(order(false, "P-CANE", "P-OXY", "P-WC"),
                supplier("SUP-001", ZIP, "cane,oxygen", 6, false),
                supplier("SUP-002", ZIP, "oxygen,wheelchair", 9, false));

        assertThat(shipmentOf(response, "SUP-001").items()).extracting(ShipmentItem::productCode)
                .containsExactly("P-CANE");
        assertThat(shipmentOf(response, "SUP-002").items()).extracting(ShipmentItem::productCode)
                .containsExactlyInAnyOrder("P-OXY", "P-WC");
    }

    // --- output shape ---

    @Test
    void notMailOrderMarksEveryItemLocal() {
        RouteResponse response = route(order(false, "P-CANE"),
                supplier("SUP-001", ZIP, "cane", 7, true));

        assertThat(shipmentOf(response, "SUP-001").items()).extracting(ShipmentItem::fulfillmentMode)
                .containsExactly("local");
    }

    @Test
    void mailOrderMarksItemsLocalWhenTheSupplierServesTheZipAndMailOrderOtherwise() {
        RouteResponse response = route(order(true, "P-CANE", "P-OXY"),
                supplier("SUP-001", ZIP, "cane", 7, false),
                supplier("SUP-002", ELSEWHERE, "oxygen", 7, true));

        assertThat(shipmentOf(response, "SUP-001").items().get(0).fulfillmentMode()).isEqualTo("local");
        assertThat(shipmentOf(response, "SUP-002").items().get(0).fulfillmentMode()).isEqualTo("mail_order");
    }

    @Test
    void shipmentCarriesSupplierNameAndEachItemItsCategory() {
        RouteResponse response = route(order(false, "P-CANE"),
                supplier("SUP-001", ZIP, "cane", 7, false));

        assertThat(response.feasible()).isTrue();
        assertThat(response.errors()).isNull();
        SupplierShipment shipment = shipmentOf(response, "SUP-001");
        assertThat(shipment.supplierName()).isEqualTo("Name SUP-001");
        assertThat(shipment.items()).extracting(ShipmentItem::category).containsExactly("cane");
    }
}
