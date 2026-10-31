package com.synapse.orderrouter.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.synapse.orderrouter.api.RouteRequest.LineItem;
import com.synapse.orderrouter.catalog.Catalog;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class RouteRequestValidatorTest {

    private static final String NO_ITEMS = "Order must include at least one line item.";
    private static final String BAD_ZIP = "Order must include a valid customer_zip.";

    private final RouteRequestValidator validator = new RouteRequestValidator(
            new Catalog(List.of(), Map.of("WC-1", "wheelchair", "OX-1", "oxygen")));

    private static RouteRequest request(String zip, LineItem... items) {
        return new RouteRequest("ORD-T", zip, false, List.of(items));
    }

    @Test
    void validRequestHasNoErrors() {
        assertThat(validator.validate(request("10015", new LineItem("WC-1", 1)))).isEmpty();
    }

    @Test
    void emptyItemsIsRejected() {
        assertThat(validator.validate(request("10015"))).containsExactly(NO_ITEMS);
    }

    @Test
    void nullItemsIsRejected() {
        RouteRequest noItems = new RouteRequest("ORD-T", "10015", false, null);

        assertThat(validator.validate(noItems)).containsExactly(NO_ITEMS);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"abc", "123", "1234567", "-1234", "12 45"})
    void invalidCustomerZipIsRejected(String zip) {
        assertThat(validator.validate(request(zip, new LineItem("WC-1", 1)))).containsExactly(BAD_ZIP);
    }

    @Test
    void fourDigitCustomerZipIsPaddedAndAccepted() {
        assertThat(validator.validate(request("2130", new LineItem("WC-1", 1)))).isEmpty();
    }

    @Test
    void missingItemsAndBadZipAreReportedTogether() {
        assertThat(validator.validate(request(null))).containsExactly(NO_ITEMS, BAD_ZIP);
    }

    @Test
    void unknownProductCodeIsRejectedAndNamed() {
        List<String> errors = validator.validate(request("10015", new LineItem("WC-1", 1), new LineItem("BAD-9", 1)));

        assertThat(errors).hasSize(1);
        assertThat(errors.get(0)).contains("BAD-9");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void nonPositiveQuantityIsRejectedAndNamed(int quantity) {
        List<String> errors = validator.validate(request("10015", new LineItem("WC-1", quantity)));

        assertThat(errors).hasSize(1);
        assertThat(errors.get(0)).contains("WC-1");
    }

    @Test
    void missingQuantityIsRejected() {
        List<String> errors = validator.validate(request("10015", new LineItem("WC-1", null)));

        assertThat(errors).hasSize(1);
        assertThat(errors.get(0)).contains("WC-1");
    }
}
