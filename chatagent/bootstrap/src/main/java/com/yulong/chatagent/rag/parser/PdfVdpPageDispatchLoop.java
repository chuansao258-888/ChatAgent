package com.yulong.chatagent.rag.parser;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs the page-image VDP dispatch loop while keeping document/per-page budgets stable.
 */
@Slf4j
final class PdfVdpPageDispatchLoop {

    private final Executor pageDispatchExecutor;
    private final int pageMaxInFlight;
    private final PdfPageRenderer pageRenderer;
    private final PdfVdpCache cache;
    private final PdfVdpResultSupport resultSupport;

    PdfVdpPageDispatchLoop(Executor pageDispatchExecutor,
                           int pageMaxInFlight,
                           PdfPageRenderer pageRenderer,
                           PdfVdpCache cache,
                           PdfVdpResultSupport resultSupport) {
        this.pageDispatchExecutor = pageDispatchExecutor == null ? Runnable::run : pageDispatchExecutor;
        this.pageMaxInFlight = Math.max(1, pageMaxInFlight);
        this.pageRenderer = pageRenderer;
        this.cache = cache;
        this.resultSupport = resultSupport;
    }

    Map<Integer, VdpPageResult> dispatch(PDDocument document,
                                         VdpEngine pageImageEngine,
                                         VdpOptions options,
                                         PdfVdpCache.PageCacheContext pageCacheContext,
                                         List<Integer> remainingVisualPageIndices,
                                         Map<Integer, VdpPageResult> cachedResults,
                                         long documentTimeoutMs,
                                         long perPageTimeoutMs) {
        long documentDeadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(documentTimeoutMs);
        PDFRenderer pdfRenderer = new PDFRenderer(document);
        List<VisualPageDispatch> inFlight = new ArrayList<>(pageMaxInFlight);
        int nextPageCursor = 0;
        Map<Integer, VdpPageResult> results = new java.util.HashMap<>(cachedResults);
        while (nextPageCursor < remainingVisualPageIndices.size() || !inFlight.isEmpty()) {
            if (Thread.currentThread().isInterrupted()) {
                cancelInFlightVisualPages(inFlight, results, pageCacheContext, "Visual page dispatch interrupted");
                markRemainingPagesAsFailed(results, remainingVisualPageIndices, nextPageCursor, pageCacheContext.engineId(), "Visual page dispatch interrupted");
                break;
            }
            if (collectCompletedVisualPages(inFlight, results, pageCacheContext)) {
                continue;
            }
            if (expireTimedOutVisualPages(inFlight, results, pageCacheContext, perPageTimeoutMs)) {
                continue;
            }
            if (remainingBudgetMs(documentDeadlineNanos) <= 0) {
                cancelInFlightVisualPages(inFlight, results, pageCacheContext, "Visual-track document budget exhausted");
                markRemainingPagesAsFailed(results, remainingVisualPageIndices, nextPageCursor, pageCacheContext.engineId(), "Visual-track document budget exhausted");
                break;
            }
            while (nextPageCursor < remainingVisualPageIndices.size() && inFlight.size() < pageMaxInFlight) {
                int pageIndex = remainingVisualPageIndices.get(nextPageCursor);
                if (remainingBudgetMs(documentDeadlineNanos) <= 0) {
                    break;
                }
                long dispatchStartedAtNanos = System.nanoTime();
                try {
                    PdfPageRenderer.RenderedPageImage renderedPageImage = pageRenderer.renderPageAsPng(pdfRenderer, pageIndex);
                    inFlight.add(submitVisualPage(pageImageEngine, pageIndex, renderedPageImage, options, dispatchStartedAtNanos));
                } catch (OutOfMemoryError error) {
                    log.error("PDF visual-track page rendering ran out of memory: pageNumber={}", pageIndex + 1, error);
                    results.put(pageIndex, PdfVdpResultSupport.failedPageResult(pageIndex, "Page rendering exceeded memory budget"));
                } catch (Exception e) {
                    results.put(pageIndex, PdfVdpResultSupport.failedPageResult(pageIndex, "Page rendering failed: " + e.getMessage()));
                }
                nextPageCursor++;
            }
            long waitBudgetMs = Math.min(remainingBudgetMs(documentDeadlineNanos), nextCompletionBudgetMs(inFlight, perPageTimeoutMs));
            if (waitBudgetMs <= 0) {
                continue;
            }
            try {
                awaitNextVisualCompletion(inFlight, waitBudgetMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                cancelInFlightVisualPages(inFlight, results, pageCacheContext, "Visual page dispatch interrupted");
                markRemainingPagesAsFailed(results, remainingVisualPageIndices, nextPageCursor, pageCacheContext.engineId(), "Visual page dispatch interrupted");
                break;
            }
            collectCompletedVisualPages(inFlight, results, pageCacheContext);
            expireTimedOutVisualPages(inFlight, results, pageCacheContext, perPageTimeoutMs);
        }
        return results;
    }

    private VisualPageDispatch submitVisualPage(VdpEngine pageImageEngine,
                                                int pageIndex,
                                                PdfPageRenderer.RenderedPageImage renderedPageImage,
                                                VdpOptions options,
                                                long dispatchStartedAtNanos) {
        try {
            CompletableFuture<VdpPageResult> future = CompletableFuture.supplyAsync(
                    () -> parseVisualPage(pageImageEngine, pageIndex, renderedPageImage, options),
                    pageDispatchExecutor
            );
            return new VisualPageDispatch(pageIndex, dispatchStartedAtNanos, future);
        } catch (RejectedExecutionException e) {
            renderedPageImage.clear();
            return new VisualPageDispatch(
                    pageIndex,
                    dispatchStartedAtNanos,
                    CompletableFuture.completedFuture(PdfVdpResultSupport.failedPageResult(pageIndex, "VDP page dispatcher is saturated"))
            );
        }
    }

    private void awaitNextVisualCompletion(List<VisualPageDispatch> inFlight, long waitBudgetMs) throws InterruptedException {
        if (inFlight.isEmpty()) {
            return;
        }
        CompletableFuture<?>[] futures = inFlight.stream()
                .map(VisualPageDispatch::future)
                .toArray(CompletableFuture[]::new);
        try {
            CompletableFuture.anyOf(futures).get(waitBudgetMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // No page finished within the current budget window; the caller decides whether
            // this should become a per-page timeout or a document-budget exhaustion.
        } catch (java.util.concurrent.ExecutionException e) {
            // Exceptional completion is resolved in collectCompletedVisualPages().
        }
    }

    private long remainingBudgetMs(long deadlineNanos) {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            return 0L;
        }
        return Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
    }

    private long nextCompletionBudgetMs(List<VisualPageDispatch> inFlight, long perPageTimeoutMs) {
        if (inFlight.isEmpty()) {
            return 0L;
        }
        long now = System.nanoTime();
        long minRemainingMs = Long.MAX_VALUE;
        for (VisualPageDispatch dispatch : inFlight) {
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(now - dispatch.submittedAtNanos());
            long remainingMs = perPageTimeoutMs - elapsedMs;
            if (remainingMs <= 0) {
                return 0L;
            }
            minRemainingMs = Math.min(minRemainingMs, remainingMs);
        }
        return minRemainingMs == Long.MAX_VALUE ? 0L : minRemainingMs;
    }

    private boolean expireTimedOutVisualPages(List<VisualPageDispatch> inFlight,
                                              Map<Integer, VdpPageResult> results,
                                              PdfVdpCache.PageCacheContext pageCacheContext,
                                              long perPageTimeoutMs) {
        if (inFlight.isEmpty()) {
            return false;
        }
        long now = System.nanoTime();
        boolean expired = false;
        for (Iterator<VisualPageDispatch> iterator = inFlight.iterator(); iterator.hasNext(); ) {
            VisualPageDispatch dispatch = iterator.next();
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(now - dispatch.submittedAtNanos());
            if (elapsedMs < perPageTimeoutMs) {
                continue;
            }
            dispatch.future().cancel(true);
            VdpPageResult timedOutResult = PdfVdpResultSupport.failedPageResult(dispatch.pageIndex(), "Visual page timed out after " + perPageTimeoutMs + " ms");
            results.putIfAbsent(dispatch.pageIndex(), timedOutResult);
            cache.put(pageCacheContext, dispatch.pageIndex(), timedOutResult);
            resultSupport.recordPageTimeout(pageCacheContext.engineId());
            iterator.remove();
            expired = true;
        }
        return expired;
    }

    private boolean collectCompletedVisualPages(List<VisualPageDispatch> inFlight,
                                                Map<Integer, VdpPageResult> results,
                                                PdfVdpCache.PageCacheContext pageCacheContext) {
        boolean collected = false;
        for (Iterator<VisualPageDispatch> iterator = inFlight.iterator(); iterator.hasNext(); ) {
            VisualPageDispatch dispatch = iterator.next();
            if (!dispatch.future().isDone()) {
                continue;
            }
            VdpPageResult resolved = resolveCompletedVisualPage(dispatch);
            results.putIfAbsent(dispatch.pageIndex(), resolved);
            cache.put(pageCacheContext, dispatch.pageIndex(), resolved);
            resultSupport.recordResolvedVisualPage(pageCacheContext.engineId(), resolved);
            iterator.remove();
            collected = true;
        }
        return collected;
    }

    private VdpPageResult resolveCompletedVisualPage(VisualPageDispatch dispatch) {
        try {
            VdpPageResult result = dispatch.future().join();
            if (result == null) {
                return PdfVdpResultSupport.failedPageResult(dispatch.pageIndex(), "Visual page dispatch returned null");
            }
            return result;
        } catch (CancellationException e) {
            return PdfVdpResultSupport.failedPageResult(dispatch.pageIndex(), "Visual page dispatch cancelled");
        } catch (CompletionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            return PdfVdpResultSupport.failedPageResult(dispatch.pageIndex(), "Visual page dispatch failed: " + cause.getMessage());
        }
    }

    private void cancelInFlightVisualPages(List<VisualPageDispatch> inFlight,
                                           Map<Integer, VdpPageResult> results,
                                           PdfVdpCache.PageCacheContext pageCacheContext,
                                           String reason) {
        for (Iterator<VisualPageDispatch> iterator = inFlight.iterator(); iterator.hasNext(); ) {
            VisualPageDispatch dispatch = iterator.next();
            dispatch.future().cancel(true);
            VdpPageResult failedResult = PdfVdpResultSupport.failedPageResult(dispatch.pageIndex(), reason);
            VdpPageResult existing = results.putIfAbsent(dispatch.pageIndex(), failedResult);
            if (existing == null) {
                cache.put(pageCacheContext, dispatch.pageIndex(), failedResult);
                resultSupport.recordResolvedVisualPage(pageCacheContext.engineId(), failedResult);
            }
            iterator.remove();
        }
    }

    private void markRemainingPagesAsFailed(Map<Integer, VdpPageResult> results,
                                            List<Integer> visualPageIndices,
                                            int startCursor,
                                            String engineId,
                                            String reason) {
        for (int cursor = startCursor; cursor < visualPageIndices.size(); cursor++) {
            int pageIndex = visualPageIndices.get(cursor);
            VdpPageResult failedResult = PdfVdpResultSupport.failedPageResult(pageIndex, reason);
            VdpPageResult existing = results.putIfAbsent(pageIndex, failedResult);
            if (existing == null) {
                resultSupport.recordResolvedVisualPage(engineId, failedResult);
            }
        }
    }

    private VdpPageResult parseVisualPage(VdpEngine pageImageEngine,
                                          int pageIndex,
                                          PdfPageRenderer.RenderedPageImage renderedPageImage,
                                          VdpOptions options) {
        if (pageImageEngine == null || !pageImageEngine.supportedModes().contains(VdpMode.PAGE_IMAGE)) {
            renderedPageImage.clear();
            return PdfVdpResultSupport.failedPageResult(pageIndex, "VDP engine does not support page-image mode");
        }
        try {
            VdpPageResult result = pageImageEngine.parsePage(
                    () -> new ByteArrayInputStream(renderedPageImage.bytes(), 0, renderedPageImage.length()),
                    "png",
                    options
            );
            if (result == null) {
                return PdfVdpResultSupport.failedPageResult(pageIndex, "VDP engine returned null");
            }
            return new VdpPageResult(pageIndex, result.markdown(), result.status(), result.metadata());
        } catch (OutOfMemoryError error) {
            log.error("PDF visual-track page parsing ran out of memory: pageNumber={}", pageIndex + 1, error);
            return PdfVdpResultSupport.failedPageResult(pageIndex, "Visual page parsing exceeded memory budget");
        } catch (Exception e) {
            log.warn("PDF visual-track page parsing failed: pageNumber={}, error={}", pageIndex + 1, e.getMessage());
            return PdfVdpResultSupport.failedPageResult(pageIndex, e.getMessage());
        } finally {
            // Clear rendered page bytes after dispatch because they contain user-uploaded page imagery.
            renderedPageImage.clear();
        }
    }

    private record VisualPageDispatch(int pageIndex, long submittedAtNanos, CompletableFuture<VdpPageResult> future) {
    }
}
