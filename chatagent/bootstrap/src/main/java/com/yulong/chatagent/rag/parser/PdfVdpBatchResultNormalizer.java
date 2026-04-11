package com.yulong.chatagent.rag.parser;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Normalizes batch VDP responses and fills missing/timed-out pages with
 * behavior-compatible failed results.
 */
final class PdfVdpBatchResultNormalizer {

    private final PdfVdpCache cache;
    private final PdfVdpResultSupport resultSupport;

    PdfVdpBatchResultNormalizer(PdfVdpCache cache, PdfVdpResultSupport resultSupport) {
        this.cache = cache;
        this.resultSupport = resultSupport;
    }

    Map<Integer, VdpPageResult> normalize(List<Integer> visualPageIndices,
                                          List<VdpPageResult> pageResults,
                                          PdfVdpCache.PageCacheContext pageCacheContext) {
        Map<Integer, VdpPageResult> results = new HashMap<>();
        Set<Integer> visualPageIndexSet = new HashSet<>(visualPageIndices);
        Map<Integer, VdpPageResult> allPageResults = new LinkedHashMap<>();
        if (pageResults != null) {
            for (VdpPageResult pageResult : pageResults) {
                if (pageResult == null) {
                    continue;
                }
                allPageResults.put(pageResult.pageIndex(), pageResult);
                if (visualPageIndexSet.contains(pageResult.pageIndex())) {
                    results.put(pageResult.pageIndex(), pageResult);
                    resultSupport.recordResolvedVisualPage(pageCacheContext.engineId(), pageResult);
                }
            }
        }
        cache.putAll(pageCacheContext, allPageResults);
        for (Integer pageIndex : visualPageIndices) {
            VdpPageResult missingResult = PdfVdpResultSupport.failedPageResult(pageIndex, "Visual-track batch did not return a result");
            VdpPageResult existing = results.putIfAbsent(pageIndex, missingResult);
            if (existing == null) {
                cache.put(pageCacheContext, pageIndex, missingResult);
                resultSupport.recordResolvedVisualPage(pageCacheContext.engineId(), missingResult);
            }
        }
        return results;
    }

    Map<Integer, VdpPageResult> markAllFailed(List<Integer> visualPageIndices,
                                              PdfVdpCache.PageCacheContext pageCacheContext,
                                              String reason) {
        Map<Integer, VdpPageResult> results = new HashMap<>();
        for (Integer pageIndex : visualPageIndices) {
            VdpPageResult failedResult = PdfVdpResultSupport.failedPageResult(pageIndex, reason);
            results.put(pageIndex, failedResult);
            cache.put(pageCacheContext, pageIndex, failedResult);
            resultSupport.recordResolvedVisualPage(pageCacheContext.engineId(), failedResult);
        }
        return results;
    }

    Map<Integer, VdpPageResult> markAllTimedOut(List<Integer> visualPageIndices,
                                                PdfVdpCache.PageCacheContext pageCacheContext,
                                                String reason) {
        Map<Integer, VdpPageResult> results = new HashMap<>();
        for (Integer pageIndex : visualPageIndices) {
            VdpPageResult timedOutResult = PdfVdpResultSupport.failedPageResult(pageIndex, reason);
            results.put(pageIndex, timedOutResult);
            cache.put(pageCacheContext, pageIndex, timedOutResult);
            resultSupport.recordPageTimeout(pageCacheContext.engineId());
        }
        return results;
    }
}
