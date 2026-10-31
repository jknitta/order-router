package com.synapse.orderrouter.api;

import com.synapse.orderrouter.catalog.Supplier;
import com.synapse.orderrouter.routing.Plan;
import com.synapse.orderrouter.routing.RouterService;
import com.synapse.orderrouter.routing.RoutingResult.Routed;
import com.synapse.orderrouter.routing.RoutingResult.Unroutable;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Always answers 200; the feasible field says whether routing succeeded. Logs one line per order. The customer ZIP,
 * product codes and request bodies are deliberately never logged, because they can identify a patient's care.
 */
@RestController
@RequestMapping("/api")
public class RouteController {

    private static final Logger log = LoggerFactory.getLogger(RouteController.class);

    private final RouteRequestValidator validator;
    private final RouterService router;

    public RouteController(RouteRequestValidator validator, RouterService router) {
        this.validator = validator;
        this.router = router;
    }

    @PostMapping("/route")
    public RouteResponse route(@RequestBody RouteRequest request) {
        long startNanos = System.nanoTime();
        List<String> errors = validator.validate(request);
        if (!errors.isEmpty()) {
            logRejected(request, "validation", errors, startNanos);
            return RouteResponse.failure(errors);
        }
        return switch (router.route(request.toOrder())) {
            case Routed routed -> {
                logRouted(request, routed.plan(), startNanos);
                yield RouteResponse.success(routed.plan());
            }
            case Unroutable unroutable -> {
                logRejected(request, "unroutable", unroutable.errors(), startNanos);
                yield RouteResponse.failure(unroutable.errors());
            }
        };
    }

    private static void logRouted(RouteRequest request, Plan plan, long startNanos) {
        List<String> supplierIds = plan.suppliers().stream().map(Supplier::id).toList();
        log.info("Routed order_id={} feasible=true suppliers={} items={} mail_order={} duration_ms={}",
                request.orderId(), supplierIds, plan.assignments().size(), request.mailOrder(),
                millisSince(startNanos));
    }

    private static void logRejected(RouteRequest request, String reason, List<String> errors, long startNanos) {
        log.info("Rejected order_id={} feasible=false reason={} errors={} duration_ms={}",
                request.orderId(), reason, errors, millisSince(startNanos));
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public RouteResponse unreadableBody() {
        log.warn("Unreadable request body");
        return RouteResponse.failure(List.of("Request body must be valid JSON in the documented format."));
    }
}
