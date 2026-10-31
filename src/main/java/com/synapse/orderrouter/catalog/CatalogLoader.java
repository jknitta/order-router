package com.synapse.orderrouter.catalog;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

public final class CatalogLoader {

    private static final CSVFormat WITH_HEADER = CSVFormat.DEFAULT.builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .setTrim(true)
            .build();

    private CatalogLoader() {
    }

    public static Catalog load(Reader suppliersCsv, Reader productsCsv) {
        try {
            return new Catalog(readSuppliers(suppliersCsv), readProducts(productsCsv));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read catalog CSV", e);
        }
    }

    private static List<Supplier> readSuppliers(Reader reader) throws IOException {
        List<Supplier> suppliers = new ArrayList<>();
        try (CSVParser parser = WITH_HEADER.parse(reader)) {
            for (CSVRecord row : parser) {
                suppliers.add(new Supplier(
                        row.get("supplier_id"),
                        row.get("suplier_name"), // sic: the column is misspelled in suppliers.csv
                        ServiceArea.parse(row.get("service_zips")),
                        categories(row.get("product_categories")),
                        score(row.get("customer_satisfaction_score")),
                        "y".equalsIgnoreCase(row.get("can_mail_order?"))));
            }
        }
        return suppliers;
    }

    private static Map<String, String> readProducts(Reader reader) throws IOException {
        Map<String, String> categoryByCode = new HashMap<>();
        try (CSVParser parser = WITH_HEADER.parse(reader)) {
            for (CSVRecord row : parser) {
                categoryByCode.putIfAbsent(row.get("product_code"), normalizeCategory(row.get("category")));
            }
        }
        return categoryByCode;
    }

    private static Set<String> categories(String commaSeparated) {
        return Arrays.stream(commaSeparated.split(","))
                .map(CatalogLoader::normalizeCategory)
                .filter(category -> !category.isEmpty())
                .collect(Collectors.toSet());
    }

    private static String normalizeCategory(String category) {
        return category.trim().toLowerCase(Locale.ROOT);
    }

    /** "no ratings yet", or anything else that is not a number, scores as the midpoint. */
    private static double score(String raw) {
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return Supplier.UNRATED_SCORE;
        }
    }
}
