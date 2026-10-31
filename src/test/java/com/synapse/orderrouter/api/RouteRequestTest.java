package com.synapse.orderrouter.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import com.synapse.orderrouter.api.RouteRequest.LineItem;
import com.synapse.orderrouter.routing.Order;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class RouteRequestTest {

    private static final List<LineItem> LINES = List.of(
            new LineItem("P-CANE", BigDecimal.valueOf(2)),
            new LineItem("P-OXY", BigDecimal.valueOf(1)));

    @Test
    void toOrderCarriesMailOrderAndLinesInOrder() {
        Order order = new RouteRequest("ORD-T", "10015", true, LINES).toOrder();

        assertThat(order.mailOrder()).isTrue();
        assertThat(order.lines()).extracting(Order.Line::productCode, Order.Line::quantity)
                .containsExactly(tuple("P-CANE", 2), tuple("P-OXY", 1));
    }

    @Test
    void toOrderLeavesAFiveDigitZipAlone() {
        assertThat(new RouteRequest("ORD-T", "10015", false, LINES).toOrder().zip()).isEqualTo("10015");
    }

    @Test
    void toOrderLeftPadsAFourDigitZip() {
        assertThat(new RouteRequest("ORD-T", "2130", false, LINES).toOrder().zip()).isEqualTo("02130");
    }
}
