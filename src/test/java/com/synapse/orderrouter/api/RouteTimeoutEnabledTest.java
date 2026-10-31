package com.synapse.orderrouter.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** With the timeout on and set to zero, every search is stopped immediately, which makes the path testable. */
@SpringBootTest(properties = {"router.search.timeout.enabled=true", "router.search.timeout.duration=PT0S"})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class RouteTimeoutEnabledTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MeterRegistry registry;

    private long timeouts() {
        Timer timer = registry.find("order.route").tag("outcome", "timeout").timer();
        return timer == null ? 0 : timer.count();
    }

    @Test
    void aTimedOutOrderReturns200WithAnErrorCountsAsTimeoutAndLogsAWarning(CapturedOutput output) throws Exception {
        long before = timeouts();

        mockMvc.perform(post("/api/route").contentType(MediaType.APPLICATION_JSON).content("""
                        {"order_id": "T-1", "customer_zip": "10015", "mail_order": false,
                         "items": [{"product_code": "WC-STD-001", "quantity": 1}]}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feasible").value(false))
                .andExpect(jsonPath("$.errors[0]").value("Order could not be routed within the time limit."))
                .andExpect(jsonPath("$.routing").doesNotExist());

        assertThat(timeouts()).isEqualTo(before + 1);
        assertThat(output).contains("WARN", "order_id=T-1", "reason=timeout");
        assertThat(output).doesNotContain("10015");
    }
}
