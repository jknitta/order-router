package com.synapse.orderrouter.routing;

import com.synapse.orderrouter.catalog.Catalog;
import com.synapse.orderrouter.catalog.Supplier;
import com.synapse.orderrouter.routing.Plan.Assignment;
import com.synapse.orderrouter.routing.RoutingResult.Routed;
import com.synapse.orderrouter.routing.RoutingResult.TimedOut;
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

    /** A supplier that may serve this order, and which of the order's categories it can handle. */
    private record Candidate(Supplier supplier, boolean local, Set<String> coverableCategories) {

        boolean covers(String category) {
            return coverableCategories.contains(category);
        }
    }

    /** Thrown from deep inside the search when the timeout passes; caught in route(). Carries no stack trace. */
    private static final class SearchTimedOut extends RuntimeException {

        SearchTimedOut() {
            super("Search timed out", null, false, false);
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
    private final SearchTimeout timeout;
    private final DistributionSummary candidateGroups;
    private final DistributionSummary combinationsExamined;
    private final DistributionSummary suppliersInPlan;

    public RouterService(Catalog catalog, SupplierSetRanker ranker, MeterRegistry meterRegistry,
            SearchTimeout timeout) {
        this.catalog = catalog;
        this.ranker = ranker;
        this.timeout = timeout;
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
        SearchResult result;
        try {
            result = fewestSuppliersPlan(items, distinctCandidates, System.nanoTime());
        } catch (SearchTimedOut e) {
            return new TimedOut();
        }
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
            Set<String> coverableCategories = items.stream()
                    .map(Item::category)
                    .filter(supplier.categories()::contains)
                    .collect(Collectors.toSet());
            if (!coverableCategories.isEmpty()) {
                candidates.add(new Candidate(supplier, local, coverableCategories));
            }
        }
        return candidates;
    }

    private List<String> unroutableItemErrors(List<Item> items, List<Candidate> candidates) {
        return items.stream()
                .filter(item -> candidates.stream().noneMatch(c -> c.covers(item.category())))
                .map(item -> "No eligible supplier for product " + item.productCode()
                        + " (category: " + item.category() + ").")
                .toList();
    }

    /**
     * Among suppliers that can take exactly the same categories, only the best-ranked can ever win, so the others are
     * dropped. This keeps the search small however many suppliers there are.
     */
    private List<Candidate> bestPerCoverage(List<Candidate> candidates) {
        Map<Set<String>, Candidate> best = new HashMap<>();
        candidates.forEach(c -> best.merge(c.coverableCategories(), c, (a, b) -> BEST_FIRST.compare(a, b) <= 0 ? a : b));
        return List.copyOf(best.values());
    }

    /** Tries 1 supplier, then 2, and so on; the first size that covers the order wins, with its best-ranked plan. */
    private SearchResult fewestSuppliersPlan(List<Item> items, List<Candidate> candidates, long startNanos) {
        List<String> categories = items.stream().map(Item::category).distinct().toList();
        int examined = 0;
        for (int size = 1; size <= categories.size(); size++) {
            List<Plan> plans = new ArrayList<>();
            examined += collectCovers(items, categories, candidates, 0, size, new ArrayDeque<>(), plans, startNanos);
            if (!plans.isEmpty()) {
                return new SearchResult(plans.stream().min(ranker).orElseThrow(), examined);
            }
        }
        throw new IllegalStateException("Every item has an eligible supplier, so a cover must exist");
    }

    /**
     * Adds a plan to the list for every group of `remaining` more candidates that, with `chosen`, covers the order.
     * Returns how many complete combinations it looked at. Stops with SearchTimedOut once the timeout has passed.
     */
    private int collectCovers(List<Item> items, List<String> categories, List<Candidate> candidates, int start,
            int remaining, Deque<Candidate> chosen, List<Plan> plans, long startNanos) {
        if (remaining == 0) {
            checkTimeout(startNanos);
            if (coversAll(categories, chosen)) {
                plans.add(assignItems(items, chosen));
            }
            return 1;
        }
        int examined = 0;
        for (int i = start; i < candidates.size(); i++) {
            chosen.addLast(candidates.get(i));
            examined += collectCovers(items, categories, candidates, i + 1, remaining - 1, chosen, plans,
                    startNanos);
            chosen.removeLast();
        }
        return examined;
    }

    private void checkTimeout(long startNanos) {
        if (timeout.enabled() && System.nanoTime() - startNanos >= timeout.duration().toNanos()) {
            throw new SearchTimedOut();
        }
    }

    private boolean coversAll(List<String> categories, Deque<Candidate> chosen) {
        return categories.stream().allMatch(category -> chosen.stream().anyMatch(c -> c.covers(category)));
    }

    private Plan assignItems(List<Item> items, Deque<Candidate> chosen) {
        List<Assignment> assignments = new ArrayList<>();
        for (Item item : items) {
            Candidate taker = chosen.stream()
                    .filter(c -> c.covers(item.category()))
                    .min(BEST_FIRST)
                    .orElseThrow();
            assignments.add(new Assignment(taker.supplier(), item.productCode(), item.quantity(), item.category(),
                    taker.local()));
        }
        return new Plan(assignments);
    }
}
