package com.synapse.orderrouter.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.synapse.orderrouter.routing.RouterService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Whatever goes wrong inside, the caller gets the documented shape and nothing about the cause. */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class UnexpectedErrorTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RouterService router;

    @Test
    void anUnexpectedFailureStillReturns200WithAGenericError(CapturedOutput output) throws Exception {
        when(router.route(any())).thenThrow(new IllegalStateException("secret-detail"));

        mockMvc.perform(post("/api/route").contentType(MediaType.APPLICATION_JSON).content("""
                        {"order_id": "ORD-ERR", "customer_zip": "10015", "mail_order": false,
                         "items": [{"product_code": "WC-STD-001", "quantity": 1}]}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feasible").value(false))
                .andExpect(jsonPath("$.errors[0]").value("Order could not be routed due to an unexpected error."))
                .andExpect(jsonPath("$.routing").doesNotExist());

        assertThat(output).contains("ERROR", "Unexpected error", "IllegalStateException");
        assertThat(output).doesNotContain("secret-detail");
    }

    @Test
    void anExceptionWithoutAStackTraceStillReturns200() throws Exception {
        // The JVM can drop the stack trace from a frequently thrown exception such as a NullPointerException.
        when(router.route(any())).thenThrow(new IllegalStateException("no trace", null, false, false));

        mockMvc.perform(post("/api/route").contentType(MediaType.APPLICATION_JSON).content("""
                        {"order_id": "ORD-ERR", "customer_zip": "10015", "mail_order": false,
                         "items": [{"product_code": "WC-STD-001", "quantity": 1}]}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feasible").value(false));
    }
}
