package com.synapse.orderrouter.api;

import com.synapse.orderrouter.routing.RouterService;
import com.synapse.orderrouter.routing.RoutingResult.Routed;
import com.synapse.orderrouter.routing.RoutingResult.Unroutable;
import java.util.List;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Always answers 200; the feasible field says whether routing succeeded. */
@RestController
@RequestMapping("/api")
public class RouteController {

    private final RouteRequestValidator validator;
    private final RouterService router;

    public RouteController(RouteRequestValidator validator, RouterService router) {
        this.validator = validator;
        this.router = router;
    }

    @PostMapping("/route")
    public RouteResponse route(@RequestBody RouteRequest request) {
        List<String> errors = validator.validate(request);
        if (!errors.isEmpty()) {
            return RouteResponse.failure(errors);
        }
        return switch (router.route(request.toOrder())) {
            case Routed routed -> RouteResponse.success(routed.plan());
            case Unroutable unroutable -> RouteResponse.failure(unroutable.errors());
        };
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public RouteResponse unreadableBody() {
        return RouteResponse.failure(List.of("Request body must be valid JSON in the documented format."));
    }
}
