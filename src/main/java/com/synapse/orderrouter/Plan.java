package com.synapse.orderrouter;

import java.util.Comparator;
import java.util.List;

/** One way to fulfil an order: every item assigned to a supplier. */
public record Plan(List<Assignment> assignments) {

    public record Assignment(Supplier supplier, String productCode, int quantity, String category, boolean local) {
    }

    /** The distinct suppliers in the plan, ordered by id. */
    public List<Supplier> suppliers() {
        return assignments.stream()
                .map(Assignment::supplier)
                .distinct()
                .sorted(Comparator.comparing(Supplier::id))
                .toList();
    }

    public double averageScore() {
        return suppliers().stream().mapToDouble(Supplier::score).average().orElse(0);
    }

    public long localItemCount() {
        return assignments.stream().filter(Assignment::local).count();
    }
}
