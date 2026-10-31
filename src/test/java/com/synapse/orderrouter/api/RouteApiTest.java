package com.synapse.orderrouter.api;

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

    /** Posts a sample order as-is, including its extra priority and notes fields, which must be ignored. */
    private ResultActions postSampleOrder(String orderId) throws Exception {
        JsonNode orders = objectMapper.readTree(new ClassPathResource("sample_orders.json").getInputStream());
        for (JsonNode order : orders) {
            if (order.get("order_id").asText().equals(orderId)) {
                return postOrder(order.toString());
            }
        }
        throw new AssertionError("no sample order " + orderId);
    }

    // Expected suppliers were confirmed against an independent brute-force calculation over the real CSVs.

    @Test
    void sample001RoutesBothItemsToOneLocalSupplier() throws Exception {
        postSampleOrder("ORD-001")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feasible").value(true))
                .andExpect(jsonPath("$.routing.length()").value(1))
                .andExpect(jsonPath("$.routing[0].supplier_id").value("SUP-0636"))
                .andExpect(jsonPath("$.routing[0].items[*].product_code")
                        .value(org.hamcrest.Matchers.contains("WC-STD-001", "OX-PORT-024")))
                .andExpect(jsonPath("$.routing[0].items[*].fulfillment_mode")
                        .value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("local"))));
    }

    @Test
    void sample002ConsolidatesAllFourItemsWithOneLocalSupplier() throws Exception {
        postSampleOrder("ORD-002")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feasible").value(true))
                .andExpect(jsonPath("$.routing.length()").value(1))
                .andExpect(jsonPath("$.routing[0].supplier_id").value("SUP-0928"))
                .andExpect(jsonPath("$.routing[0].items.length()").value(4))
                .andExpect(jsonPath("$.routing[0].items[*].fulfillment_mode")
                        .value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("local"))));
    }

    @Test
    void sample003MailOrderGoesToTheTopRatedShippingSupplierWithQuantitiesKept() throws Exception {
        postSampleOrder("ORD-003")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feasible").value(true))
                .andExpect(jsonPath("$.routing.length()").value(1))
                .andExpect(jsonPath("$.routing[0].supplier_id").value("SUP-023"))
                .andExpect(jsonPath("$.routing[0].items[*].product_code")
                        .value(org.hamcrest.Matchers.contains("CP-STD-031", "CP-MSK-FF-035", "NB-COMP-039")))
                .andExpect(jsonPath("$.routing[0].items[1].quantity").value(2))
                .andExpect(jsonPath("$.routing[0].items[*].fulfillment_mode")
                        .value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("mail_order"))));
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

    @Test
    void aNullEntryInItemsReturns200WithFeasibleFalse() throws Exception {
        postOrder("""
                {"order_id": "ORD-X", "customer_zip": "10015", "mail_order": false, "items": [null]}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feasible").value(false))
                .andExpect(jsonPath("$.errors").isNotEmpty());
    }

    @Test
    void aFractionalQuantityIsRejectedInsteadOfSilentlyTruncated() throws Exception {
        postOrder("""
                {"order_id": "ORD-X", "customer_zip": "10015", "mail_order": true,
                 "items": [{"product_code": "WC-STD-001", "quantity": 1.9}]}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feasible").value(false))
                .andExpect(jsonPath("$.errors[0]")
                        .value("Quantity for WC-STD-001 must be a whole number, but was 1.9."))
                .andExpect(jsonPath("$.routing").doesNotExist());
    }

    @Test
    void aNonJsonContentTypeReturns200WithFeasibleFalse() throws Exception {
        mockMvc.perform(post("/api/route").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feasible").value(false))
                .andExpect(jsonPath("$.errors").isNotEmpty());
    }
}
