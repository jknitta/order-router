package com.synapse.orderrouter;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
class RouteApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private ResultActions postOrder(String body) throws Exception {
        return mockMvc.perform(post("/api/route").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void validationFailureReturns200WithOnlyFeasibleAndErrors() throws Exception {
        postOrder("""
                {"order_id": "ORD-X", "customer_zip": "10015", "mail_order": false, "items": []}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feasible").value(false))
                .andExpect(jsonPath("$.errors[0]").value("Order must include at least one line item."))
                .andExpect(jsonPath("$.routing").doesNotExist());
    }

    @Test
    void unknownProductCodeReturns200WithFeasibleFalse() throws Exception {
        postOrder("""
                {"order_id": "ORD-X", "customer_zip": "10015", "mail_order": true,
                 "items": [{"product_code": "NOPE-000", "quantity": 1}]}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feasible").value(false))
                .andExpect(jsonPath("$.errors[0]").value(org.hamcrest.Matchers.containsString("NOPE-000")));
    }

    @Test
    void malformedJsonReturns200WithFeasibleFalse() throws Exception {
        postOrder("{not json")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feasible").value(false))
                .andExpect(jsonPath("$.errors").isNotEmpty());
    }

    @Test
    void sampleOrdersAlwaysReturn200AndExtraRequestFieldsAreIgnored() throws Exception {
        JsonNode orders = objectMapper.readTree(new ClassPathResource("sample_orders.json").getInputStream());

        for (JsonNode order : orders) {
            postOrder(order.toString())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.feasible").isBoolean());
        }
    }

    @Test
    void successfulResponseHasTheSpecShape() throws Exception {
        // ORD-003 from the samples: mail order, CPAP + nebulizer, customer in Boston.
        postOrder("""
                {"order_id": "ORD-003", "customer_zip": "02130", "mail_order": true,
                 "items": [{"product_code": "CP-STD-031", "quantity": 1},
                           {"product_code": "CP-MSK-FF-035", "quantity": 2},
                           {"product_code": "NB-COMP-039", "quantity": 1}]}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feasible").value(true))
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.routing[0].supplier_id").isString())
                .andExpect(jsonPath("$.routing[0].supplier_name").isString())
                .andExpect(jsonPath("$.routing[0].items[0].product_code").isString())
                .andExpect(jsonPath("$.routing[0].items[0].quantity").isNumber())
                .andExpect(jsonPath("$.routing[0].items[0].category").isString())
                .andExpect(jsonPath("$.routing[0].items[0].fulfillment_mode")
                        .value(org.hamcrest.Matchers.anyOf(
                                org.hamcrest.Matchers.is("local"), org.hamcrest.Matchers.is("mail_order"))));
    }
}
