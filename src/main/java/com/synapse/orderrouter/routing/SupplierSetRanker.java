package com.synapse.orderrouter.routing;

import java.util.Comparator;

/**
 * Orders plans that use the same number of suppliers, best first. This is the seam for swapping in a different
 * ranking, or a rules engine, without touching the search.
 */
public interface SupplierSetRanker extends Comparator<Plan> {
}
