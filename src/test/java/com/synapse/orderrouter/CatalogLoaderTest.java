package com.synapse.orderrouter;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.StringReader;
import org.junit.jupiter.api.Test;

class CatalogLoaderTest {

    private static final String SUPPLIERS = """
            supplier_id,suplier_name,service_zips,product_categories,customer_satisfaction_score,can_mail_order?
            SUP-001,Local Medical Supply Co,"11410, 11419","knee scooter, CPAP",7,y
            SUP-002,Mail Only Inc,00100-99999," cane ",no ratings yet,N
            SUP-003,Top Rated LLC,10001,cane,10.0,n
            """;

    private static final String PRODUCTS = """
            product_code,product_name,category
            CP-STD-031,Standard CPAP,CPAP
            CP-MSK-035,CPAP Mask,cpap
            CN-001,Cane,cane
            CN-001,Cane,cane
            """;

    private final Catalog catalog = CatalogLoader.load(new StringReader(SUPPLIERS), new StringReader(PRODUCTS));

    private Supplier supplier(String id) {
        return catalog.suppliers().stream()
                .filter(s -> s.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no supplier " + id));
    }

    @Test
    void readsSupplierNameDespiteTypoInHeader() {
        assertThat(catalog.suppliers()).hasSize(3);
        assertThat(supplier("SUP-001").name()).isEqualTo("Local Medical Supply Co");
        assertThat(supplier("SUP-002").name()).isEqualTo("Mail Only Inc");
        assertThat(supplier("SUP-003").name()).isEqualTo("Top Rated LLC");
    }

    @Test
    void categoriesAreTrimmedAndLowercased() {
        assertThat(supplier("SUP-001").categories()).containsExactlyInAnyOrder("knee scooter", "cpap");
        assertThat(supplier("SUP-002").categories()).containsExactly("cane");
    }

    @Test
    void numericScoresParseAndNoRatingsYetBecomesTheMidpoint() {
        assertThat(supplier("SUP-001").score()).isEqualTo(7.0);
        assertThat(supplier("SUP-002").score()).isEqualTo(Supplier.UNRATED_SCORE);
        assertThat(supplier("SUP-003").score()).isEqualTo(10.0);
        assertThat(Supplier.UNRATED_SCORE).isEqualTo(5.5);
    }

    @Test
    void mailOrderFlagIsReadCaseInsensitively() {
        assertThat(supplier("SUP-001").canMailOrder()).isTrue();
        assertThat(supplier("SUP-002").canMailOrder()).isFalse();
        assertThat(supplier("SUP-003").canMailOrder()).isFalse();
    }

    @Test
    void serviceAreaIsParsedForEachSupplier() {
        assertThat(supplier("SUP-001").area().serves("11419")).isTrue();
        assertThat(supplier("SUP-001").area().serves("11420")).isFalse();
        assertThat(supplier("SUP-002").area().serves("02130")).isTrue();
    }

    @Test
    void productCategoriesAreLowercasedAndDuplicateCodesLoadOnce() {
        assertThat(catalog.productCategories())
                .hasSize(3)
                .containsEntry("CP-STD-031", "cpap")
                .containsEntry("CP-MSK-035", "cpap")
                .containsEntry("CN-001", "cane");
    }
}
