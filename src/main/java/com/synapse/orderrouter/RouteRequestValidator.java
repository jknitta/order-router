package com.synapse.orderrouter;

import java.util.List;

public class RouteRequestValidator {

    public RouteRequestValidator(Catalog catalog) {
    }

    /** Returns one message per problem; an empty list means the request is well-formed. */
    public List<String> validate(RouteRequest request) {
        throw new UnsupportedOperationException("not implemented");
    }
}
