package com.yulong.chatagent.rag.parser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Plans which visual-track pages still need dispatch after consulting the page cache.
 */
final class PdfVdpBatchPlanner {

    private final PdfVdpCache cache;

    PdfVdpBatchPlanner(PdfVdpCache cache) {
        this.cache = cache;
    }

    VisualDispatchPlan plan(List<PdfQualityRouter.PageRoutingDecision> routingDecisions,
                            PdfVdpCache.PageCacheContext pageCacheContext) {
        List<Integer> remainingVisualPageIndices = new ArrayList<>();
        Map<Integer, VdpPageResult> cachedResults = new HashMap<>();
        for (int pageIndex = 0; pageIndex < routingDecisions.size(); pageIndex++) {
            if (!routingDecisions.get(pageIndex).isVisualTrack()) {
                continue;
            }
            VdpPageResult cached = cache.get(pageCacheContext, pageIndex);
            if (cached != null) {
                cachedResults.put(pageIndex, cached);
            } else {
                remainingVisualPageIndices.add(pageIndex);
            }
        }
        return new VisualDispatchPlan(remainingVisualPageIndices, cachedResults);
    }

    record VisualDispatchPlan(List<Integer> remainingVisualPageIndices,
                              Map<Integer, VdpPageResult> cachedResults) {
    }
}
