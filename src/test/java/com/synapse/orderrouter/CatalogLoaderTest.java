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

    @Test
    void readsSupplierNameDespiteTypoInHeader() {
        assertThat(catalog.suppliers()).extracting(Supplier::name)
                .containsExactly("Local Medical Supply Co", "Mail Only Inc", "Top Rated LLC");
    }

    @Test
    void categoriesAreTrimmedAndLowercased() {
        assertThat(catalog.suppliers().get(0).categories()).containsExactlyInAnyOrder("knee scooter", "cpap");
        assertThat(catalog.suppliers().get(1).categories()).containsExactly("cane");
    }

    @Test
    void numericScoresParseAndNoRatingsYetBecomesTheMidpoint() {
        assertThat(catalog.suppliers()).extracting(Supplier::score)
                .containsExactly(7.0, Supplier.UNRATED_SCORE, 10.0);
        assertThat(Supplier.UNRATED_SCORE).isEqualTo(5.5);
    }

    @Test
    void mailOrderFlagIsReadCaseInsensitively() {
        assertThat(catalog.suppliers()).extracting(Supplier::canMailOrder)
                .containsExactly(true, false, false);
    }

    @Test
    void serviceAreaIsParsedForEachSupplier() {
        assertThat(catalog.suppliers().get(0).area().serves("11419")).isTrue();
        assertThat(catalog.suppliers().get(1).area().serves("02130")).isTrue();
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
