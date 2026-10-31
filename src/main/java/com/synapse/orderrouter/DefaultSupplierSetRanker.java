package com.synapse.orderrouter;

import java.util.List;
import org.springframework.stereotype.Component;

/** Higher average score, then more items fulfilled locally, then the lowest supplier ids. */
@Component
public class DefaultSupplierSetRanker implements SupplierSetRanker {

    @Override
    public int compare(Plan a, Plan b) {
        int byScore = Double.compare(b.averageScore(), a.averageScore());
        if (byScore != 0) {
            return byScore;
        }
        int byLocal = Long.compare(b.localItemCount(), a.localItemCount());
        if (byLocal != 0) {
            return byLocal;
        }
        return compareIds(a.suppliers(), b.suppliers());
    }

    private static int compareIds(List<Supplier> a, List<Supplier> b) {
        for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
            int byId = a.get(i).id().compareTo(b.get(i).id());
            if (byId != 0) {
                return byId;
            }
        }
        return 0;
    }
}
