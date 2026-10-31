package com.synapse.orderrouter;

import java.util.Set;

public record Supplier(
        String id,
        String name,
        ServiceArea area,
        Set<String> categories,
        double score,
        boolean canMailOrder) {

    /** Midpoint of the 1-10 scale, used for suppliers with "no ratings yet". */
    public static final double UNRATED_SCORE = 5.5;
}
