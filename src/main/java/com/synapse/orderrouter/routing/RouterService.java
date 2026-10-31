package com.synapse.orderrouter.routing;

import com.synapse.orderrouter.catalog.Catalog;
import com.synapse.orderrouter.catalog.Supplier;
import com.synapse.orderrouter.routing.Plan.Assignment;
import com.synapse.orderrouter.routing.RoutingResult.Routed;
import com.synapse.orderrouter.routing.RoutingResult.Unroutable;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class RouterService {

    private record Item(String productCode, int quantity, String category) {
    }

    /** A supplier that may serve this order, and the product codes in the order it can handle. */
    private record Candidate(Supplier supplier, boolean local, Set<String> coverableCodes) {

        boolean covers(String productCode) {
            return coverableCodes.contains(productCode);
        }
    }

    private record SearchResult(Plan plan, int combinationsExamined) {
    }

    /** Which of two suppliers that can take the same item should get it: higher score, then local, then lower id. */
    private static final Comparator<Candidate> BEST_FIRST = Comparator
            .comparingDouble((Candidate c) -> c.supplier().score()).reversed()
            .thenComparing(Candidate::local, Comparator.reverseOrder())
            .thenComparing(c -> c.supplier().id());

    private final Catalog catalog;
    private final SupplierSetRanker ranker;
    private final DistributionSummary candidateGroups;
    private final DistributionSummary combinationsExamined;
    private final DistributionSummary suppliersInPlan;

    public RouterService(Catalog catalog, SupplierSetRanker ranker, MeterRegistry meterRegistry) {
        this.catalog = catalog;
        this.ranker = ranker;
        this.candidateGroups = DistributionSummary.builder("router.search.candidates")
                .description("Distinct supplier coverage groups searched per order").register(meterRegistry);
        this.combinationsExamined = DistributionSummary.builder("router.search.combinations")
                .description("Supplier combinations examined per order").register(meterRegistry);
        this.suppliersInPlan = DistributionSummary.builder("router.search.suppliers")
                .description("Suppliers in the chosen plan per order").register(meterRegistry);
    }

    /** Routes an order that has already passed validation. */
    public RoutingResult route(Order order) {
        List<Item> items = mergeDuplicateLines(order.lines());
        List<Candidate> candidates = eligibleCandidates(items, order);

        List<String> errors = unroutableItemErrors(items, candidates);
        if (!errors.isEmpty()) {
            return new Unroutable(errors);
        }
        List<Candidate> distinctCandidates = bestPerCoverage(candidates);
        SearchResult result = fewestSuppliersPlan(items, distinctCandidates);
        candidateGroups.record(distinctCandidates.size());
        combinationsExamined.record(result.combinationsExamined());
        suppliersInPlan.record(result.plan().suppliers().size());
        return new Routed(result.plan());
    }

    private List<Item> mergeDuplicateLines(List<Order.Line> lines) {
        Map<String, Integer> quantityByCode = new LinkedHashMap<>();
        lines.forEach(line -> quantityByCode.merge(line.productCode(), line.quantity(), Integer::sum));
        return quantityByCode.entrySet().stream()
                .map(e -> new Item(e.getKey(), e.getValue(), catalog.productCategories().get(e.getKey())))
                .toList();
    }

    /** A supplier is eligible if it serves the ZIP, or if the order allows mail order and the supplier ships. */
    private List<Candidate> eligibleCandidates(List<Item> items, Order order) {
        List<Candidate> candidates = new ArrayList<>();
        for (Supplier supplier : catalog.suppliers()) {
            boolean local = supplier.area().serves(order.zip());
            if (!local && !(order.mailOrder() && supplier.canMailOrder())) {
                continue;
            }
            Set<String> coverableCodes = items.stream()
                    .filter(item -> supplier.categories().contains(item.category()))
                    .map(Item::productCode)
                    .collect(Collectors.toSet());
            if (!coverableCodes.isEmpty()) {
                candidates.add(new Candidate(supplier, local, coverableCodes));
            }
        }
        return candidates;
    }

    private List<String> unroutableItemErrors(List<Item> items, List<Candidate> candidates) {
        return items.stream()
                .filter(item -> candidates.stream().noneMatch(c -> c.covers(item.productCode())))
                .map(item -> "No eligible supplier for product " + item.productCode()
                        + " (category: " + item.category() + ").")
                .toList();
    }

    /**
     * Among suppliers that can take exactly the same items, only the best-ranked can ever win, so the others are
     * dropped. This keeps the search small however many suppliers there are.
     */
    private List<Candidate> bestPerCoverage(List<Candidate> candidates) {
        Map<Set<String>, Candidate> best = new HashMap<>();
        candidates.forEach(c -> best.merge(c.coverableCodes(), c, (a, b) -> BEST_FIRST.compare(a, b) <= 0 ? a : b));
        return List.copyOf(best.values());
    }

    /** Tries 1 supplier, then 2, and so on; the first size that covers the order wins, with its best-ranked plan. */
    private SearchResult fewestSuppliersPlan(List<Item> items, List<Candidate> candidates) {
        int examined = 0;
        for (int size = 1; size <= items.size(); size++) {
            List<Plan> plans = new ArrayList<>();
            examined += collectCovers(items, candidates, 0, size, new ArrayDeque<>(), plans);
            if (!plans.isEmpty()) {
                return new SearchResult(plans.stream().min(ranker).orElseThrow(), examined);
            }
        }
        throw new IllegalStateException("Every item has an eligible supplier, so a cover must exist");
    }

    /**
     * Adds a plan to the list for every group of `remaining` more candidates that, with `chosen`, covers the order.
     * Returns how many complete combinations it looked at.
     */
    private int collectCovers(List<Item> items, List<Candidate> candidates, int start, int remaining,
                               Deque<Candidate> chosen, List<Plan> plans) {
        if (remaining == 0) {
            if (coversAll(items, chosen)) {
                plans.add(assignItems(items, chosen));
            }
            return 1;
        }
        int examined = 0;
        for (int i = start; i < candidates.size(); i++) {
            chosen.addLast(candidates.get(i));
            examined += collectCovers(items, candidates, i + 1, remaining - 1, chosen, plans);
            chosen.removeLast();
        }
        return examined;
    }

    private boolean coversAll(List<Item> items, Deque<Candidate> chosen) {
        return items.stream().allMatch(item -> chosen.stream().anyMatch(c -> c.covers(item.productCode())));
    }

    private Plan assignItems(List<Item> items, Deque<Candidate> chosen) {
        List<Assignment> assignments = new ArrayList<>();
        for (Item item : items) {
            Candidate taker = chosen.stream()
                    .filter(c -> c.covers(item.productCode()))
                    .min(BEST_FIRST)
                    .orElseThrow();
            assignments.add(new Assignment(taker.supplier(), item.productCode(), item.quantity(), item.category(),
                    taker.local()));
        }
        return new Plan(assignments);
    }
}
