package com.synapse.orderrouter.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import com.synapse.orderrouter.catalog.Catalog;
import com.synapse.orderrouter.catalog.ServiceArea;
import com.synapse.orderrouter.catalog.Supplier;
import com.synapse.orderrouter.routing.Plan.Assignment;
import com.synapse.orderrouter.routing.RoutingResult.Routed;
import com.synapse.orderrouter.routing.RoutingResult.Unroutable;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
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

    private static RoutingResult route(Order order, Supplier... suppliers) {
        return new RouterService(new Catalog(List.of(suppliers), PRODUCTS), new DefaultSupplierSetRanker(),
                new SimpleMeterRegistry(), SearchTimeout.disabled()).route(order);
    }

    private static Order order(boolean mailOrder, String... productCodes) {
        List<Order.Line> lines = Arrays.stream(productCodes).map(code -> new Order.Line(code, 1)).toList();
        return new Order(ZIP, mailOrder, lines);
    }

    private static Plan planOf(RoutingResult result) {
        assertThat(result).isInstanceOf(Routed.class);
        return ((Routed) result).plan();
    }

    private static List<String> errorsOf(RoutingResult result) {
        assertThat(result).isInstanceOf(Unroutable.class);
        return ((Unroutable) result).errors();
    }

    /** The ids of the suppliers in the chosen plan, in id order. */
    private static List<String> supplierIds(RoutingResult result) {
        return planOf(result).suppliers().stream().map(Supplier::id).toList();
    }

    private static List<Assignment> assignmentsOf(RoutingResult result, String supplierId) {
        return planOf(result).assignments().stream()
                .filter(a -> a.supplier().id().equals(supplierId))
                .toList();
    }

    // --- eligibility ---

    @Test
    void whenNotMailOrderASupplierThatDoesNotServeTheZipIsNeverChosen() {
        RoutingResult result = route(order(false, "P-CANE"),
                supplier("SUP-001", ELSEWHERE, "cane", 10, true),
                supplier("SUP-002", ZIP, "cane", 5, false));

        assertThat(supplierIds(result)).containsExactly("SUP-002");
    }

    @Test
    void whenNotMailOrderAndNobodyServesTheZipTheOrderIsUnroutable() {
        RoutingResult result = route(order(false, "P-CANE"),
                supplier("SUP-001", ELSEWHERE, "cane", 10, true));

        assertThat(result).isInstanceOf(Unroutable.class);
    }

    @Test
    void whenMailOrderASupplierOutsideTheZipThatShipsIsEligible() {
        RoutingResult result = route(order(true, "P-CANE"),
                supplier("SUP-001", ELSEWHERE, "cane", 7, true));

        assertThat(supplierIds(result)).containsExactly("SUP-001");
    }

    @Test
    void whenMailOrderASupplierOutsideTheZipThatDoesNotShipIsNotEligible() {
        RoutingResult result = route(order(true, "P-CANE"),
                supplier("SUP-001", ELSEWHERE, "cane", 7, false));

        assertThat(result).isInstanceOf(Unroutable.class);
    }

    @Test
    void aSupplierMustHandleTheItemsCategory() {
        RoutingResult result = route(order(false, "P-CANE", "P-OXY"),
                supplier("SUP-001", ZIP, "cane", 7, false));

        assertThat(result).isInstanceOf(Unroutable.class);
    }

    @Test
    void anItemNoSupplierCanHandleFailsTheWholeOrderAndIsNamed() {
        RoutingResult result = route(order(false, "P-CANE", "P-OXY"),
                supplier("SUP-001", ZIP, "cane", 7, false));

        assertThat(errorsOf(result)).hasSize(1);
        assertThat(errorsOf(result).get(0)).contains("P-OXY");
    }

    // --- fewest shipments ---

    @Test
    void oneSupplierCoveringEverythingBeatsTwoEvenIfTheTwoAreRatedHigher() {
        RoutingResult result = route(order(false, "P-CANE", "P-OXY"),
                supplier("SUP-001", ZIP, "cane,oxygen", 5, false),
                supplier("SUP-002", ZIP, "cane", 9, false),
                supplier("SUP-003", ZIP, "oxygen", 9, false));

        assertThat(supplierIds(result)).containsExactly("SUP-001");
    }

    @Test
    void whenNoSingleSupplierCoversTheOrderTheMinimumNumberOfSuppliersIsUsed() {
        // Items 1-6. A greedy pick of the biggest supplier (SUP-001, items 1-4) first would end up with 3 shipments.
        // The minimum is 2: SUP-002 (1,3,5) + SUP-003 (2,4,6).
        RoutingResult result = route(order(false, "P-CANE", "P-CPAP", "P-OXY", "P-WC", "P-WALK", "P-COM"),
                supplier("SUP-001", ZIP, "cane,cpap,oxygen,wheelchair", 7, false),
                supplier("SUP-002", ZIP, "cane,oxygen,walker", 7, false),
                supplier("SUP-003", ZIP, "cpap,wheelchair,commode", 7, false));

        assertThat(supplierIds(result)).containsExactly("SUP-002", "SUP-003");
    }

    @Test
    void duplicateLinesForTheSameProductAreSummed() {
        Order order = new Order(ZIP, false, List.of(new Order.Line("P-CANE", 1), new Order.Line("P-CANE", 2)));

        RoutingResult result = route(order, supplier("SUP-001", ZIP, "cane", 7, false));

        assertThat(assignmentsOf(result, "SUP-001"))
                .extracting(Assignment::productCode, Assignment::quantity)
                .containsExactly(tuple("P-CANE", 3));
    }

    @Test
    void ordersWithMoreThan32DistinctItemsAreRoutedCorrectly() {
        // Regression: the first implementation tracked items as bits of an int, which breaks past 32 items.
        Map<String, String> products = new HashMap<>();
        Set<String> categories = new HashSet<>();
        List<Order.Line> lines = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            products.put("P-" + i, "category-" + i);
            categories.add("category-" + i);
            lines.add(new Order.Line("P-" + i, 1));
        }
        Supplier everything = new Supplier("SUP-001", "Name SUP-001", ServiceArea.parse(ZIP), categories, 7, false);

        RoutingResult result = new RouterService(new Catalog(List.of(everything), products),
                new DefaultSupplierSetRanker(), new SimpleMeterRegistry(), SearchTimeout.disabled())
                .route(new Order(ZIP, false, lines));

        assertThat(supplierIds(result)).containsExactly("SUP-001");
        assertThat(assignmentsOf(result, "SUP-001")).hasSize(40);
    }

    // --- quality, local preference, tie-breaks (single supplier) ---

    @Test
    void higherScoreWinsAmongSingleSupplierOptions() {
        RoutingResult result = route(order(false, "P-CANE"),
                supplier("SUP-001", ZIP, "cane", 6, false),
                supplier("SUP-002", ZIP, "cane", 8, false));

        assertThat(supplierIds(result)).containsExactly("SUP-002");
    }

    @Test
    void unratedSupplierRanksBelowASevenButAboveAFour() {
        Supplier unrated = supplier("SUP-001", ZIP, "cane", Supplier.UNRATED_SCORE, false);

        RoutingResult againstSeven = route(order(false, "P-CANE"), unrated,
                supplier("SUP-002", ZIP, "cane", 7, false));
        RoutingResult againstFour = route(order(false, "P-CANE"), unrated,
                supplier("SUP-002", ZIP, "cane", 4, false));

        assertThat(supplierIds(againstSeven)).containsExactly("SUP-002");
        assertThat(supplierIds(againstFour)).containsExactly("SUP-001");
    }

    @Test
    void exactScoreTieGoesToTheLocalSupplierEvenWithAHigherId() {
        RoutingResult result = route(order(true, "P-CANE"),
                supplier("SUP-001", ELSEWHERE, "cane", 7, true),
                supplier("SUP-002", ZIP, "cane", 7, false));

        assertThat(supplierIds(result)).containsExactly("SUP-002");
    }

    @Test
    void scoresThatDifferEvenSlightlyAreNotATie() {
        RoutingResult result = route(order(true, "P-CANE"),
                supplier("SUP-001", ZIP, "cane", 6.9, false),
                supplier("SUP-002", ELSEWHERE, "cane", 7.0, true));

        assertThat(supplierIds(result)).containsExactly("SUP-002");
    }

    @Test
    void aFullTieGoesToTheLowestSupplierIdRegardlessOfInputOrder() {
        RoutingResult result = route(order(false, "P-CANE"),
                supplier("SUP-002", ZIP, "cane", 7, false),
                supplier("SUP-001", ZIP, "cane", 7, false));

        assertThat(supplierIds(result)).containsExactly("SUP-001");
    }

    // --- ranking of equally small supplier sets ---

    @Test
    void equallySmallSetsAreRankedByHigherAverageScore() {
        // Four 2-supplier covers. 001+004 has the best average (8.5); the lowest-id cover 001+002 averages only 7.
        RoutingResult result = route(order(false, "P-CANE", "P-OXY"),
                supplier("SUP-001", ZIP, "cane", 9, false),
                supplier("SUP-002", ZIP, "oxygen", 5, false),
                supplier("SUP-003", ZIP, "cane", 8, false),
                supplier("SUP-004", ZIP, "oxygen", 8, false));

        assertThat(supplierIds(result)).containsExactly("SUP-001", "SUP-004");
    }

    @Test
    void whenAveragesTieTheSetWithMoreLocalItemsWins() {
        // Every score is 7, so every cover averages 7. Only 005+006 has both items local, despite higher ids.
        RoutingResult result = route(order(true, "P-CANE", "P-OXY"),
                supplier("SUP-001", ELSEWHERE, "cane", 7, true),
                supplier("SUP-002", ELSEWHERE, "oxygen", 7, true),
                supplier("SUP-005", ZIP, "cane", 7, false),
                supplier("SUP-006", ZIP, "oxygen", 7, false));

        assertThat(supplierIds(result)).containsExactly("SUP-005", "SUP-006");
    }

    @Test
    void anItemEitherSupplierInTheChosenSetCouldTakeGoesToTheHigherRated() {
        // SUP-001 (cane, oxygen) and SUP-002 (oxygen, wheelchair) are the only 2-supplier cover.
        RoutingResult result = route(order(false, "P-CANE", "P-OXY", "P-WC"),
                supplier("SUP-001", ZIP, "cane,oxygen", 6, false),
                supplier("SUP-002", ZIP, "oxygen,wheelchair", 9, false));

        assertThat(assignmentsOf(result, "SUP-001")).extracting(Assignment::productCode)
                .containsExactly("P-CANE");
        assertThat(assignmentsOf(result, "SUP-002")).extracting(Assignment::productCode)
                .containsExactlyInAnyOrder("P-OXY", "P-WC");
    }

    // --- what each assignment records ---

    @Test
    void whenNotMailOrderEveryAssignmentIsLocal() {
        RoutingResult result = route(order(false, "P-CANE"),
                supplier("SUP-001", ZIP, "cane", 7, true));

        assertThat(assignmentsOf(result, "SUP-001")).extracting(Assignment::local).containsExactly(true);
    }

    @Test
    void whenMailOrderAnAssignmentIsLocalOnlyIfTheSupplierServesTheZip() {
        RoutingResult result = route(order(true, "P-CANE", "P-OXY"),
                supplier("SUP-001", ZIP, "cane", 7, false),
                supplier("SUP-002", ELSEWHERE, "oxygen", 7, true));

        assertThat(assignmentsOf(result, "SUP-001").get(0).local()).isTrue();
        assertThat(assignmentsOf(result, "SUP-002").get(0).local()).isFalse();
    }

    @Test
    void eachAssignmentCarriesItsSupplierCategoryAndQuantity() {
        Order order = new Order(ZIP, false, List.of(new Order.Line("P-CANE", 2)));

        RoutingResult result = route(order, supplier("SUP-001", ZIP, "cane", 7, false));

        assertThat(assignmentsOf(result, "SUP-001"))
                .extracting(a -> a.supplier().name(), Assignment::productCode, Assignment::category,
                        Assignment::quantity)
                .containsExactly(tuple("Name SUP-001", "P-CANE", "cane", 2));
    }

    // --- search effort metrics ---

    @Test
    void recordsHowMuchWorkTheSearchDid() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RouterService service = new RouterService(new Catalog(List.of(
                supplier("SUP-001", ZIP, "cane,cpap,oxygen,wheelchair", 7, false),
                supplier("SUP-002", ZIP, "cane,oxygen,walker", 7, false),
                supplier("SUP-003", ZIP, "cpap,wheelchair,commode", 7, false)), PRODUCTS),
                new DefaultSupplierSetRanker(), registry, SearchTimeout.disabled());

        service.route(order(false, "P-CANE", "P-CPAP", "P-OXY", "P-WC", "P-WALK", "P-COM"));

        // 3 distinct candidates. One supplier can never cover 6 categories (the best covers 4), so size 1 is skipped.
        // For pairs it tries SUP-001 then SUP-002 for the first category (cane): 2 steps. After SUP-001 only SUP-002
        // covers walker (1 step, a dead end). In SUP-002's branch SUP-001 is skipped, since every group containing it
        // was already explored, so only SUP-003 is tried for cpap (1 step), and SUP-002 + SUP-003 covers the order.
        assertThat(registry.get("router.search.candidates").summary().totalAmount()).isEqualTo(3);
        assertThat(registry.get("router.search.combinations").summary().totalAmount()).isEqualTo(4);
        assertThat(registry.get("router.search.suppliers").summary().totalAmount()).isEqualTo(2);
    }

    @Test
    void anUnroutableOrderRecordsNoSearchWork() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RouterService service = new RouterService(
                new Catalog(List.of(supplier("SUP-001", ZIP, "cane", 7, false)), PRODUCTS),
                new DefaultSupplierSetRanker(), registry, SearchTimeout.disabled());

        service.route(order(false, "P-OXY"));

        assertThat(registry.get("router.search.combinations").summary().count()).isZero();
    }

    // --- timeout ---

    private static RoutingResult routeWithTimeout(SearchTimeout timeout) {
        // Needs two suppliers, so the search has to run past its first combination.
        return new RouterService(new Catalog(List.of(
                supplier("SUP-001", ZIP, "cane,cpap,oxygen,wheelchair", 7, false),
                supplier("SUP-002", ZIP, "cane,oxygen,walker", 7, false),
                supplier("SUP-003", ZIP, "cpap,wheelchair,commode", 7, false)), PRODUCTS),
                new DefaultSupplierSetRanker(), new SimpleMeterRegistry(), timeout)
                .route(order(false, "P-CANE", "P-CPAP", "P-OXY", "P-WC", "P-WALK", "P-COM"));
    }

    @Test
    void aSearchThatOutlastsTheTimeoutIsReportedAsTimedOut() {
        assertThat(routeWithTimeout(new SearchTimeout(true, java.time.Duration.ZERO)))
                .isInstanceOf(RoutingResult.TimedOut.class);
    }

    @Test
    void aDisabledTimeoutNeverStopsASearch() {
        assertThat(routeWithTimeout(new SearchTimeout(false, java.time.Duration.ZERO))).isInstanceOf(Routed.class);
    }

    @Test
    void aGenerousTimeoutDoesNotAffectANormalOrder() {
        assertThat(routeWithTimeout(new SearchTimeout(true, java.time.Duration.ofSeconds(10))))
                .isInstanceOf(Routed.class);
    }
}
