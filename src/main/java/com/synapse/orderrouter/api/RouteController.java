package com.synapse.orderrouter.api;

import com.synapse.orderrouter.catalog.Supplier;
import com.synapse.orderrouter.routing.Plan;
import com.synapse.orderrouter.routing.RouterService;
import com.synapse.orderrouter.routing.RoutingResult.Routed;
import com.synapse.orderrouter.routing.RoutingResult.TimedOut;
import com.synapse.orderrouter.routing.RoutingResult.Unroutable;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Always answers 200; the feasible field says whether routing succeeded. Logs one line per order. The customer ZIP,
 * product codes, error messages and request bodies are deliberately never logged, because they can identify a
 * patient's care.
 */
@RestController
@RequestMapping("/api")
public class RouteController {

    private static final String TIMEOUT_MESSAGE = "Order could not be routed within the time limit.";

    private static final Logger LOG = LoggerFactory.getLogger(RouteController.class);

    private final RouteRequestValidator validator;
    private final RouterService router;
    private final MeterRegistry meterRegistry;

    public RouteController(RouteRequestValidator validator, RouterService router, MeterRegistry meterRegistry) {
        this.validator = validator;
        this.router = router;
        this.meterRegistry = meterRegistry;
    }

    @PostMapping("/route")
    public RouteResponse route(@RequestBody RouteRequest request) {
        long startNanos = System.nanoTime();
        List<String> errors = validator.validate(request);
        if (!errors.isEmpty()) {
            logRejected(request, "validation", errors, recordOutcome("validation", startNanos));
            return RouteResponse.failure(errors);
        }
        return switch (router.route(request.toOrder())) {
            case Routed routed -> {
                logRouted(request, routed.plan(), recordOutcome("routed", startNanos));
                yield RouteResponse.success(routed.plan());
            }
            case TimedOut timedOut -> {
                long durationMillis = recordOutcome("timeout", startNanos);
                LOG.warn("Timed out order_id={} feasible=false reason=timeout duration_ms={}",
                        loggable(request.orderId()), durationMillis);
                yield RouteResponse.failure(List.of(TIMEOUT_MESSAGE));
            }
            case Unroutable unroutable -> {
                logRejected(request, "unroutable", unroutable.errors(), recordOutcome("unroutable", startNanos));
                yield RouteResponse.failure(unroutable.errors());
            }
        };
    }

    private static void logRouted(RouteRequest request, Plan plan, long durationMillis) {
        List<String> supplierIds = plan.suppliers().stream().map(Supplier::id).toList();
        LOG.info("Routed order_id={} feasible=true suppliers={} items={} mail_order={} duration_ms={}",
                loggable(request.orderId()), supplierIds, plan.assignments().size(), request.mailOrder(),
                durationMillis);
    }

    private static void logRejected(RouteRequest request, String reason, List<String> errors,
            long durationMillis) {
        LOG.info("Rejected order_id={} feasible=false reason={} error_count={} duration_ms={}",
                loggable(request.orderId()), reason, errors.size(), durationMillis);
    }

    /** Caller-supplied text must not be able to end a log line and start a fake one. */
    private static String loggable(String text) {
        return text == null ? null : text.replaceAll("\\p{Cntrl}", "_");
    }

    /** Times the order under a fixed outcome name (never order data) and returns the duration in milliseconds. */
    private long recordOutcome(String outcome, long startNanos) {
        long nanos = System.nanoTime() - startNanos;
        meterRegistry.timer("order.route", "outcome", outcome).record(nanos, TimeUnit.NANOSECONDS);
        return TimeUnit.NANOSECONDS.toMillis(nanos);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, HttpMediaTypeNotSupportedException.class})
    public RouteResponse unreadableBody() {
        LOG.warn("Unreadable request body");
        return RouteResponse.failure(List.of("Request body must be valid JSON in the documented format."));
    }

    /**
     * Last resort, so no request can produce anything but the documented shape. Only the exception type and where it
     * was thrown are logged: the message could contain request data.
     */
    @ExceptionHandler(Exception.class)
    public RouteResponse unexpectedFailure(Exception e) {
        // The JVM can omit the stack trace of a frequently thrown exception, so it may be empty.
        StackTraceElement[] trace = e.getStackTrace();
        LOG.error("Unexpected error type={} at={}", e.getClass().getName(), trace.length > 0 ? trace[0] : "unknown");
        return RouteResponse.failure(List.of("Order could not be routed due to an unexpected error."));
    }
}
