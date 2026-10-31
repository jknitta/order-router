package com.synapse.orderrouter;

import java.util.List;
import java.util.Map;

/** Everything loaded from the CSV files. productCategories maps product code to lowercase category. */
public record Catalog(List<Supplier> suppliers, Map<String, String> productCategories) {
}
