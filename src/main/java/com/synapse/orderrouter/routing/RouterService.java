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
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
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

    private record SearchResult(Plan plan, int stepsTaken) {
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
                .description("Supplier choices tried by the search per order").register(meterRegistry);
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
        combinationsExamined.record(result.stepsTaken());
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
        candidates.forEach(c -> best.merge(c.coverableCategories(), c,
                (a, b) -> BEST_FIRST.compare(a, b) <= 0 ? a : b));
        return best.values().stream().sorted(Comparator.comparing(c -> c.supplier().id())).toList();
    }

    private SearchResult fewestSuppliersPlan(List<Item> items, List<Candidate> candidates, long startNanos) {
        return new Search(items, candidates, startNanos).run();
    }

    /**
     * Finds the smallest number of suppliers that cover every category in the order, and the best-ranked plan of
     * that size. It tries 1 supplier, then 2, and so on. At each step it takes the first category still uncovered and
     * tries only suppliers that cover it, instead of every combination of suppliers.
     */
    private final class Search {

        private final List<Item> items;
        private final List<String> categories;
        private final List<Candidate> candidates;
        private final int mostCategoriesOneSupplierCovers;
        private final long startNanos;
        private final Deque<Candidate> chosen = new ArrayDeque<>();
        // Suppliers already tried for the current category, so the same group is not found twice in another order.
        private final Set<Candidate> alreadyTried = Collections.newSetFromMap(new IdentityHashMap<>());
        private final List<Plan> plans = new ArrayList<>();
        private int steps;

        Search(List<Item> items, List<Candidate> candidates, long startNanos) {
            this.items = items;
            this.categories = items.stream().map(Item::category).distinct().toList();
            this.candidates = candidates;
            this.mostCategoriesOneSupplierCovers = candidates.stream()
                    .mapToInt(c -> c.coverableCategories().size())
                    .max()
                    .orElse(0);
            this.startNanos = startNanos;
        }

        SearchResult run() {
            for (int size = 1; size <= categories.size(); size++) {
                addCoversUsing(size, categories);
                if (!plans.isEmpty()) {
                    return new SearchResult(plans.stream().min(ranker).orElseThrow(), steps);
                }
            }
            throw new IllegalStateException("Every item has an eligible supplier, so a cover must exist");
        }

        /** Adds a plan for every way to cover the `uncovered` categories with at most `remaining` more suppliers. */
        private void addCoversUsing(int remaining, List<String> uncovered) {
            if (uncovered.isEmpty()) {
                plans.add(assignItems(items, chosen));
                return;
            }
            // Dead end: no suppliers left, or even the broadest suppliers could not cover what is left.
            if (remaining == 0 || uncovered.size() > remaining * mostCategoriesOneSupplierCovers) {
                return;
            }
            String target = uncovered.get(0);
            List<Candidate> triedHere = new ArrayList<>();
            for (Candidate candidate : candidates) {
                if (!candidate.covers(target) || alreadyTried.contains(candidate)) {
                    continue;
                }
                checkTimeout(startNanos);
                steps++;
                chosen.addLast(candidate);
                addCoversUsing(remaining - 1, uncovered.stream().filter(c -> !candidate.covers(c)).toList());
                chosen.removeLast();
                alreadyTried.add(candidate);
                triedHere.add(candidate);
            }
            triedHere.forEach(alreadyTried::remove);
        }
    }

    private void checkTimeout(long startNanos) {
        if (timeout.enabled() && System.nanoTime() - startNanos >= timeout.duration().toNanos()) {
            throw new SearchTimedOut();
        }
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
