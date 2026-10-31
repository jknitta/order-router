package com.synapse.orderrouter.routing;

import com.synapse.orderrouter.catalog.Catalog;
import com.synapse.orderrouter.catalog.ServiceArea;
import com.synapse.orderrouter.catalog.Supplier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/** Deterministic made-up catalogs for performance tests. Every supplier serves the same ZIP. */
final class SyntheticCatalog {

    static final String ZIP = "10015";

    private final Catalog catalog;
    private final int categoryCount;
    private final int productsPerCategory;

    private SyntheticCatalog(Catalog catalog, int categoryCount, int productsPerCategory) {
        this.catalog = catalog;
        this.categoryCount = categoryCount;
        this.productsPerCategory = productsPerCategory;
    }

    /** Each supplier covers each category with probability `coverage`; every category gets at least one supplier. */
    static SyntheticCatalog build(int supplierCount, int categoryCount, int productsPerCategory, double coverage,
            long seed) {
        Random random = new Random(seed);
        List<Set<String>> categoriesBySupplier = new ArrayList<>();
        for (int s = 0; s < supplierCount; s++) {
            Set<String> categories = new HashSet<>();
            for (int c = 0; c < categoryCount; c++) {
                if (random.nextDouble() < coverage) {
                    categories.add("category-" + c);
                }
            }
            categoriesBySupplier.add(categories);
        }
        for (int c = 0; c < categoryCount; c++) {
            String category = "category-" + c;
            if (categoriesBySupplier.stream().noneMatch(set -> set.contains(category))) {
                categoriesBySupplier.get(c % supplierCount).add(category);
            }
        }
        List<Supplier> suppliers = new ArrayList<>();
        for (int s = 0; s < supplierCount; s++) {
            double score = 4 + random.nextInt(61) / 10.0;
            Set<String> categories = categoriesBySupplier.get(s);
            if (categories.isEmpty()) {
                categories.add("category-0");
            }
            suppliers.add(new Supplier(String.format("SUP-%05d", s), "Supplier " + s, ServiceArea.parse(ZIP),
                    categories, score, false));
        }
        Map<String, String> products = new HashMap<>();
        for (int c = 0; c < categoryCount; c++) {
            for (int p = 0; p < productsPerCategory; p++) {
                products.put(code(c, p), "category-" + c);
            }
        }
        return new SyntheticCatalog(new Catalog(suppliers, products), categoryCount, productsPerCategory);
    }

    Catalog catalog() {
        return catalog;
    }

    /** An order with one product from each of the first `categories` categories. */
    Order orderOfCategories(int categories) {
        return orderOfProducts(categories, 1);
    }

    /** An order with `productsEach` products from each of the first `categories` categories. */
    Order orderOfProducts(int categories, int productsEach) {
        List<Order.Line> lines = new ArrayList<>();
        for (int c = 0; c < Math.min(categories, categoryCount); c++) {
            for (int p = 0; p < Math.min(productsEach, productsPerCategory); p++) {
                lines.add(new Order.Line(code(c, p), 1));
            }
        }
        return new Order(ZIP, false, lines);
    }

    private static String code(int category, int product) {
        return "P-" + category + "-" + product;
    }
}
