package com.synapse.orderrouter.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** One log line per routed order, with enough to trace a decision and nothing that identifies the customer. */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class RouteLoggingTest {

    @Autowired
    private MockMvc mockMvc;

    private void postOrder(String body) throws Exception {
        mockMvc.perform(post("/api/route").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void aRoutedOrderLogsItsIdOutcomeSuppliersAndDuration(CapturedOutput output) throws Exception {
        postOrder("""
                {"order_id": "ORD-LOG-1", "customer_zip": "10015", "mail_order": false,
                 "items": [{"product_code": "WC-STD-001", "quantity": 1},
                           {"product_code": "OX-PORT-024", "quantity": 1}]}
                """);

        assertThat(output).contains("order_id=ORD-LOG-1", "feasible=true", "suppliers=[SUP-0636]", "items=2",
                "mail_order=false");
        assertThat(output).containsPattern("duration_ms=\\d+");
    }

    @Test
    void logsNeverContainTheCustomerZipOrProductCodes(CapturedOutput output) throws Exception {
        postOrder("""
                {"order_id": "ORD-LOG-2", "customer_zip": "10015", "mail_order": false,
                 "items": [{"product_code": "WC-STD-001", "quantity": 1}]}
                """);

        assertThat(output).contains("order_id=ORD-LOG-2");
        assertThat(output).doesNotContain("10015").doesNotContain("WC-STD-001");
    }

    @Test
    void aValidationFailureLogsTheReasonAndTheErrorCount(CapturedOutput output) throws Exception {
        postOrder("""
                {"order_id": "ORD-LOG-3", "customer_zip": "10015", "mail_order": false, "items": []}
                """);

        assertThat(output).contains("order_id=ORD-LOG-3", "feasible=false", "reason=validation",
                "error_count=1");
    }

    @Test
    void anUnroutableOrderLogsTheReasonAndTheErrorCountButNoProductCode(CapturedOutput output) throws Exception {
        // ZIP 00050 is below every range in the data, so no supplier serves it.
        postOrder("""
                {"order_id": "ORD-LOG-4", "customer_zip": "00050", "mail_order": false,
                 "items": [{"product_code": "WC-STD-001", "quantity": 1}]}
                """);

        assertThat(output).contains("order_id=ORD-LOG-4", "feasible=false", "reason=unroutable",
                "error_count=1");
        assertThat(output).doesNotContain("WC-STD-001");
    }

    @Test
    void anUnreadableBodyLogsAWarningWithoutEchoingTheBody(CapturedOutput output) throws Exception {
        postOrder("{secret-body-text");

        assertThat(output).contains("WARN", "Unreadable request body");
        assertThat(output).doesNotContain("secret-body-text");
    }

    @Test
    void controlCharactersInTheOrderIdCannotForgeLogLines(CapturedOutput output) throws Exception {
        postOrder("""
                {"order_id": "ORD-EVIL\\nFORGED-LINE feasible=true", "customer_zip": "10015", "mail_order": false,
                 "items": []}
                """);

        assertThat(output).contains("order_id=ORD-EVIL_FORGED-LINE");
        assertThat(output.getAll().lines()).noneMatch(line -> line.startsWith("FORGED-LINE"));
    }

    @Test
    void callerSuppliedProductCodesInErrorsAreNotLogged(CapturedOutput output) throws Exception {
        postOrder("""
                {"order_id": "ORD-LOG-5", "customer_zip": "10015", "mail_order": false,
                 "items": [{"product_code": "BAD\\r\\nFORGED-CODE-LINE", "quantity": 1}]}
                """);

        assertThat(output).contains("order_id=ORD-LOG-5", "error_count=1");
        assertThat(output).doesNotContain("FORGED-CODE-LINE");
    }
}
