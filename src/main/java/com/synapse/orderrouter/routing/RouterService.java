package com.synapse.orderrouter.routing;

import com.synapse.orderrouter.catalog.Catalog;
import org.springframework.stereotype.Service;

@Service
public class RouterService {

    public RouterService(Catalog catalog, SupplierSetRanker ranker) {
    }

    /** Routes an order that has already passed validation. */
    public RoutingResult route(Order order) {
        throw new UnsupportedOperationException("not implemented");
    }
}
