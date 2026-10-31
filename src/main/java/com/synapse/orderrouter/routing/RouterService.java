package com.synapse.orderrouter.routing;

import com.synapse.orderrouter.catalog.Catalog;
import com.synapse.orderrouter.catalog.ServiceArea;
import com.synapse.orderrouter.catalog.Supplier;
import com.synapse.orderrouter.routing.Plan.Assignment;
import com.synapse.orderrouter.routing.RouteRequest.LineItem;
import com.synapse.orderrouter.routing.RouteResponse.ShipmentItem;
import com.synapse.orderrouter.routing.RouteResponse.SupplierShipment;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class RouterService {

    private record Item(String productCode, int quantity, String category) {
    }

    /** A supplier and the items (as bits, by position in the order) it can handle. */
    private record Candidate(Supplier supplier, int coverage, boolean local) {
    }

    /** Which of two suppliers that can take the same item should get it: higher score, then local, then lower id. */
    private static final Comparator<Candidate> BEST_FIRST = Comparator
            .comparingDouble((Candidate c) -> c.supplier().score()).reversed()
            .thenComparing(Candidate::local, Comparator.reverseOrder())
            .thenComparing(c -> c.supplier().id());

    private final Catalog catalog;
    private final SupplierSetRanker ranker;

    public RouterService(Catalog catalog, SupplierSetRanker ranker) {
        this.catalog = catalog;
        this.ranker = ranker;
    }

    /** Routes a request that has already passed validation. */
    public RouteResponse route(RouteRequest request) {
        String zip = ServiceArea.normalize(request.customerZip()).orElseThrow();
        List<Item> items = mergeDuplicateLines(request.items());
        List<Candidate> candidates = eligibleCandidates(items, zip, request.mailOrder());

        List<String> errors = unroutableItemErrors(items, candidates);
        if (!errors.isEmpty()) {
            return new RouteResponse(false, null, errors);
        }
        return toResponse(fewestSuppliersPlan(items, bestPerCoverage(candidates)));
    }

    private List<Item> mergeDuplicateLines(List<LineItem> lines) {
        Map<String, Integer> quantityByCode = new LinkedHashMap<>();
        lines.forEach(line -> quantityByCode.merge(line.productCode(), line.quantity(), Integer::sum));
        return quantityByCode.entrySet().stream()
                .map(e -> new Item(e.getKey(), e.getValue(), catalog.productCategories().get(e.getKey())))
                .toList();
    }

    /** A supplier is eligible if it serves the ZIP, or if the order allows mail order and the supplier ships. */
    private List<Candidate> eligibleCandidates(List<Item> items, String zip, boolean mailOrder) {
        List<Candidate> candidates = new ArrayList<>();
        for (Supplier supplier : catalog.suppliers()) {
            boolean local = supplier.area().serves(zip);
            if (!local && !(mailOrder && supplier.canMailOrder())) {
                continue;
            }
            int coverage = 0;
            for (int i = 0; i < items.size(); i++) {
                if (supplier.categories().contains(items.get(i).category())) {
                    coverage |= 1 << i;
                }
            }
            if (coverage != 0) {
                candidates.add(new Candidate(supplier, coverage, local));
            }
        }
        return candidates;
    }

    private List<String> unroutableItemErrors(List<Item> items, List<Candidate> candidates) {
        List<String> errors = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            int bit = 1 << i;
            if (candidates.stream().noneMatch(c -> (c.coverage() & bit) != 0)) {
                Item item = items.get(i);
                errors.add("No eligible supplier for product " + item.productCode()
                        + " (category: " + item.category() + ").");
            }
        }
        return errors;
    }

    /**
     * Among suppliers that cover exactly the same items, only the best-ranked can ever win, so the others are
     * dropped. This keeps the subset search small.
     */
    private List<Candidate> bestPerCoverage(List<Candidate> candidates) {
        Map<Integer, Candidate> best = new HashMap<>();
        candidates.forEach(c -> best.merge(c.coverage(), c, (a, b) -> BEST_FIRST.compare(a, b) <= 0 ? a : b));
        return List.copyOf(best.values());
    }

    /** Tries 1 supplier, then 2, and so on; the first size that covers the order wins, best-ranked plan of that size. */
    private Plan fewestSuppliersPlan(List<Item> items, List<Candidate> candidates) {
        int allItems = (1 << items.size()) - 1;
        for (int size = 1; size <= items.size(); size++) {
            List<Plan> plans = new ArrayList<>();
            collectCovers(items, candidates, 0, size, 0, allItems, new ArrayDeque<>(), plans);
            if (!plans.isEmpty()) {
                return plans.stream().min(ranker).orElseThrow();
            }
        }
        throw new IllegalStateException("Every item has an eligible supplier, so a cover must exist");
    }

    private void collectCovers(List<Item> items, List<Candidate> candidates, int start, int remaining, int covered,
                               int allItems, Deque<Candidate> chosen, List<Plan> plans) {
        if (remaining == 0) {
            if (covered == allItems) {
                plans.add(assignItems(items, chosen));
            }
            return;
        }
        for (int i = start; i < candidates.size(); i++) {
            Candidate candidate = candidates.get(i);
            chosen.addLast(candidate);
            collectCovers(items, candidates, i + 1, remaining - 1, covered | candidate.coverage(), allItems,
                    chosen, plans);
            chosen.removeLast();
        }
    }

    private Plan assignItems(List<Item> items, Deque<Candidate> chosen) {
        List<Assignment> assignments = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            int bit = 1 << i;
            Candidate taker = chosen.stream()
                    .filter(c -> (c.coverage() & bit) != 0)
                    .min(BEST_FIRST)
                    .orElseThrow();
            Item item = items.get(i);
            assignments.add(new Assignment(taker.supplier(), item.productCode(), item.quantity(), item.category(),
                    taker.local()));
        }
        return new Plan(assignments);
    }

    private RouteResponse toResponse(Plan plan) {
        Map<String, List<Assignment>> bySupplierId = plan.assignments().stream()
                .collect(Collectors.groupingBy(a -> a.supplier().id(), TreeMap::new, Collectors.toList()));
        List<SupplierShipment> routing = bySupplierId.values().stream()
                .map(assignments -> new SupplierShipment(
                        assignments.get(0).supplier().id(),
                        assignments.get(0).supplier().name(),
                        assignments.stream().map(RouterService::toShipmentItem).toList()))
                .toList();
        return new RouteResponse(true, routing, null);
    }

    private static ShipmentItem toShipmentItem(Assignment a) {
        return new ShipmentItem(a.productCode(), a.quantity(), a.category(), a.local() ? "local" : "mail_order");
    }
}
