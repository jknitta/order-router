package com.synapse.orderrouter.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.synapse.orderrouter.catalog.Catalog;
import com.synapse.orderrouter.catalog.ServiceArea;
import com.synapse.orderrouter.catalog.Supplier;
import com.synapse.orderrouter.routing.RoutingResult.Routed;
import com.synapse.orderrouter.routing.RoutingResult.Unroutable;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The real router must agree with the naive reference on every random catalog and order. */
class RouterEquivalenceTest {

    private static final String ZIP = "10015";
    private static final String ELSEWHERE = "90210";
    private static final List<String> CATEGORIES = List.of("cane", "cpap", "oxygen", "wheelchair", "walker", "commode");
    private static final Map<String, String> PRODUCTS = Map.of(
            "P-CANE", "cane", "P-CPAP", "cpap", "P-OXY", "oxygen",
            "P-WC", "wheelchair", "P-WALK", "walker", "P-COM", "commode");
    private static final double[] SCORES = {5.5, 6.0, 7.0, 7.0, 8.0, 9.5};

    private static Catalog randomCatalog(Random random) {
        int count = 2 + random.nextInt(8);
        List<String> ids = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            ids.add(String.format("SUP-%03d", i));
        }
        Collections.shuffle(ids, random);
        List<Supplier> suppliers = new ArrayList<>();
        for (String id : ids) {
            Set<String> categories = new HashSet<>();
            CATEGORIES.stream().filter(c -> random.nextDouble() < 0.45).forEach(categories::add);
            if (categories.isEmpty()) {
                categories.add(CATEGORIES.get(random.nextInt(CATEGORIES.size())));
            }
            String zips = random.nextDouble() < 0.6 ? ZIP : ELSEWHERE;
            suppliers.add(new Supplier(id, "Name " + id, ServiceArea.parse(zips), categories,
                    SCORES[random.nextInt(SCORES.length)], random.nextDouble() < 0.7));
        }
        return new Catalog(suppliers, PRODUCTS);
    }

    private static Order randomOrder(Random random) {
        List<String> codes = new ArrayList<>(PRODUCTS.keySet());
        List<Order.Line> lines = new ArrayList<>();
        int lineCount = 1 + random.nextInt(6);
        for (int i = 0; i < lineCount; i++) {
            lines.add(new Order.Line(codes.get(random.nextInt(codes.size())), 1 + random.nextInt(3)));
        }
        return new Order(ZIP, random.nextBoolean(), lines);
    }

    @Test
    void theRouterAgreesWithTheReferenceOnRandomCatalogsAndOrders() {
        Random random = new Random(20260101L);
        SupplierSetRanker ranker = new DefaultSupplierSetRanker();
        int routed = 0;
        int multiSupplier = 0;

        for (int i = 0; i < 400; i++) {
            Catalog catalog = randomCatalog(random);
            Order order = randomOrder(random);

            Optional<Plan> expected = ReferenceRouter.bestPlan(catalog, order, ranker);
            RoutingResult actual = new RouterService(catalog, ranker, new SimpleMeterRegistry(),
                    SearchTimeout.disabled()).route(order);

            if (expected.isPresent()) {
                assertThat(actual).as("case %d", i).isInstanceOf(Routed.class);
                assertThat(((Routed) actual).plan().assignments()).as("case %d", i)
                        .isEqualTo(expected.get().assignments());
                routed++;
                if (expected.get().suppliers().size() > 1) {
                    multiSupplier++;
                }
            } else {
                assertThat(actual).as("case %d", i).isInstanceOf(Unroutable.class);
            }
        }

        // Guards the test itself: the random cases must include real multi-supplier decisions.
        assertThat(routed).isGreaterThan(100);
        assertThat(multiSupplier).isGreaterThan(30);
    }
}
