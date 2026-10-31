package com.synapse.orderrouter.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** The same zero-length timeout does nothing once the feature is switched off by configuration. */
@SpringBootTest(properties = {"router.search.timeout.enabled=false", "router.search.timeout.duration=PT0S"})
@AutoConfigureMockMvc
class RouteTimeoutDisabledTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void withTheTimeoutDisabledTheOrderRoutesNormally() throws Exception {
        mockMvc.perform(post("/api/route").contentType(MediaType.APPLICATION_JSON).content("""
                        {"order_id": "T-2", "customer_zip": "10015", "mail_order": false,
                         "items": [{"product_code": "WC-STD-001", "quantity": 1}]}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feasible").value(true));
    }
}
