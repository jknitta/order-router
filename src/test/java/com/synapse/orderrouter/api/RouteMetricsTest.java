package com.synapse.orderrouter.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Every order is timed by outcome, and the numbers are readable without exposing anything about the order. */
@SpringBootTest
@AutoConfigureMockMvc
class RouteMetricsTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MeterRegistry registry;

    private long orders(String outcome) {
        Timer timer = registry.find("order.route").tag("outcome", outcome).timer();
        return timer == null ? 0 : timer.count();
    }

    private void postOrder(String body) throws Exception {
        mockMvc.perform(post("/api/route").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void aRoutedOrderIsCountedAsRouted() throws Exception {
        long before = orders("routed");

        postOrder("""
                {"order_id": "M-1", "customer_zip": "10015", "mail_order": false,
                 "items": [{"product_code": "WC-STD-001", "quantity": 1}]}
                """);

        assertThat(orders("routed")).isEqualTo(before + 1);
    }

    @Test
    void aValidationFailureIsCountedAsValidation() throws Exception {
        long before = orders("validation");

        postOrder("""
                {"order_id": "M-2", "customer_zip": "10015", "mail_order": false, "items": []}
                """);

        assertThat(orders("validation")).isEqualTo(before + 1);
    }

    @Test
    void anUnroutableOrderIsCountedAsUnroutable() throws Exception {
        long before = orders("unroutable");

        postOrder("""
                {"order_id": "M-3", "customer_zip": "00050", "mail_order": false,
                 "items": [{"product_code": "WC-STD-001", "quantity": 1}]}
                """);

        assertThat(orders("unroutable")).isEqualTo(before + 1);
    }

    @Test
    void theMetricsEndpointIsExposed() throws Exception {
        mockMvc.perform(get("/actuator/metrics")).andExpect(status().isOk());
    }

    @Test
    void metricTagsNeverCarryOrderData() throws Exception {
        postOrder("""
                {"order_id": "M-SECRET-ID", "customer_zip": "10015", "mail_order": false,
                 "items": [{"product_code": "WC-STD-001", "quantity": 1}]}
                """);

        registry.getMeters().forEach(meter -> meter.getId().getTags().forEach(tag -> {
            assertThat(tag.getValue()).doesNotContain("M-SECRET-ID").doesNotContain("10015")
                    .doesNotContain("WC-STD-001");
        }));
    }
}
