package com.synapse.orderrouter.routing;

import com.synapse.orderrouter.catalog.Catalog;
import com.synapse.orderrouter.catalog.Supplier;
import com.synapse.orderrouter.routing.Plan.Assignment;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A deliberately naive router used as an oracle in tests: it tries every subset of eligible suppliers and never
 * drops or collapses anything. Slow, but easy to trust, so the real router is checked against it.
 */
final class ReferenceRouter {

    private ReferenceRouter() {
    }

    static Optional<Plan> bestPlan(Catalog catalog, Order order, SupplierSetRanker ranker) {
        Map<String, Integer> quantityByCode = new LinkedHashMap<>();
        order.lines().forEach(line -> quantityByCode.merge(line.productCode(), line.quantity(), Integer::sum));

        List<Supplier> eligible = catalog.suppliers().stream()
                .filter(s -> s.area().serves(order.zip()) || (order.mailOrder() && s.canMailOrder()))
                .toList();

        for (String code : quantityByCode.keySet()) {
            String category = catalog.productCategories().get(code);
            if (eligible.stream().noneMatch(s -> s.categories().contains(category))) {
                return Optional.empty();
            }
        }

        for (int size = 1; size <= eligible.size(); size++) {
            List<Plan> plans = new ArrayList<>();
            for (int subset = 1; subset < (1 << eligible.size()); subset++) {
                if (Integer.bitCount(subset) != size) {
                    continue;
                }
                List<Supplier> chosen = new ArrayList<>();
                for (int i = 0; i < eligible.size(); i++) {
                    if ((subset & (1 << i)) != 0) {
                        chosen.add(eligible.get(i));
                    }
                }
                planFor(catalog, order, quantityByCode, chosen).ifPresent(plans::add);
            }
            if (!plans.isEmpty()) {
                return plans.stream().min(ranker);
            }
        }
        return Optional.empty();
    }

    private static Optional<Plan> planFor(Catalog catalog, Order order, Map<String, Integer> quantityByCode,
            List<Supplier> chosen) {
        Comparator<Supplier> bestTaker = Comparator.comparingDouble(Supplier::score).reversed()
                .thenComparing((Supplier s) -> s.area().serves(order.zip()), Comparator.reverseOrder())
                .thenComparing(Supplier::id);
        List<Assignment> assignments = new ArrayList<>();
        for (Map.Entry<String, Integer> line : quantityByCode.entrySet()) {
            String category = catalog.productCategories().get(line.getKey());
            Optional<Supplier> taker = chosen.stream()
                    .filter(s -> s.categories().contains(category))
                    .min(bestTaker);
            if (taker.isEmpty()) {
                return Optional.empty();
            }
            assignments.add(new Assignment(taker.get(), line.getKey(), line.getValue(), category,
                    taker.get().area().serves(order.zip())));
        }
        return Optional.of(new Plan(assignments));
    }
}
