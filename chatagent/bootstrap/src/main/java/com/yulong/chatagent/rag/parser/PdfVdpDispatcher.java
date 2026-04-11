package com.yulong.chatagent.rag.parser;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Encapsulates PDF visual-track dispatch without changing the existing batch/page
 * routing, timeout, and fallback behavior.
 */
@Slf4j
final class PdfVdpDispatcher {

    private final VdpEngineRouter engineRouter;
    private final PdfVdpCache cache;
    private final PdfVdpBatchPlanner batchPlanner;
    private final Executor batchExecutor;
    private final long pageDispatchTimeoutMs;
    private final long knowledgeDocumentTimeoutMs;
    private final PdfQualityRouter qualityRouter;
    private final PdfVdpBatchResultNormalizer batchResultNormalizer;
    private final PdfVdpPageDispatchLoop pageDispatchLoop;

    PdfVdpDispatcher(VdpEngineRouter engineRouter,
                     PdfVdpCache cache,
                     PdfVdpBatchPlanner batchPlanner,
                     Executor pageDispatchExecutor,
                     Executor batchExecutor,
                     int pageMaxInFlight,
                     long pageDispatchTimeoutMs,
                     long knowledgeDocumentTimeoutMs,
                     MeterRegistry meterRegistry,
                     PdfPageRenderer pageRenderer,
                     PdfQualityRouter qualityRouter) {
        this.engineRouter = engineRouter == null ? VdpEngineRouter.forTesting(new NoopVdpEngine()) : engineRouter;
        this.cache = cache;
        this.batchPlanner = batchPlanner;
        this.batchExecutor = batchExecutor == null ? Runnable::run : batchExecutor;
        this.pageDispatchTimeoutMs = Math.max(1000L, pageDispatchTimeoutMs);
        this.knowledgeDocumentTimeoutMs = Math.max(this.pageDispatchTimeoutMs, knowledgeDocumentTimeoutMs);
        this.qualityRouter = qualityRouter;
        PdfVdpResultSupport resultSupport = new PdfVdpResultSupport(meterRegistry);
        this.batchResultNormalizer = new PdfVdpBatchResultNormalizer(cache, resultSupport);
        this.pageDispatchLoop = new PdfVdpPageDispatchLoop(pageDispatchExecutor, pageMaxInFlight, pageRenderer, cache, resultSupport);
    }

    Map<Integer, VdpPageResult> dispatchVisualTrackPages(PDDocument document,
                                                         List<PdfQualityRouter.PageRoutingDecision> routingDecisions,
                                                         Map<String, Object> rawOptions,
                                                         VdpOptions options,
                                                         PipelineSource pipelineSource,
                                                         Supplier<InputStream> pdfStreamSupplier) {
        List<String> visualTrackPages = qualityRouter.summarizeVisualTrackPages(routingDecisions);
        VdpEngine batchEngine = engineRouter.resolveForBatch(pipelineSource);
        if (batchEngine != null) {
            log.info("PDF visual-track dispatch selected batch engine: pipelineSource={}, engineId={}, visualTrackPages={}, mineruReceivesWholePdf=true",
                    pipelineSource,
                    batchEngine.engineId(),
                    visualTrackPages);
            PdfVdpCache.PageCacheContext batchCacheContext = cache.buildContext(rawOptions, pipelineSource, options, batchEngine);
            PdfVdpBatchPlanner.VisualDispatchPlan cachedBatchPlan = batchPlanner.plan(routingDecisions, batchCacheContext);
            if (cachedBatchPlan.remainingVisualPageIndices().isEmpty()) {
                return cachedBatchPlan.cachedResults();
            }
            Map<Integer, VdpPageResult> results = new HashMap<>(cachedBatchPlan.cachedResults());
            results.putAll(dispatchBatchVisualTrackPages(
                    batchEngine,
                    pdfStreamSupplier,
                    cachedBatchPlan.remainingVisualPageIndices(),
                    options,
                    batchCacheContext
            ));
            return results;
        }

        VdpEngine pageImageEngine = engineRouter.resolveForPageImage(pipelineSource);
        if (!visualTrackPages.isEmpty()) {
            log.info("PDF visual-track dispatch selected page-image engine: pipelineSource={}, engineId={}, visualTrackPages={}",
                    pipelineSource,
                    pageImageEngine == null ? "unknown" : pageImageEngine.engineId(),
                    visualTrackPages);
        }
        PdfVdpCache.PageCacheContext pageCacheContext = cache.buildContext(rawOptions, pipelineSource, options, pageImageEngine);
        PdfVdpBatchPlanner.VisualDispatchPlan visualDispatchPlan = batchPlanner.plan(routingDecisions, pageCacheContext);
        if (visualDispatchPlan.remainingVisualPageIndices().isEmpty()) {
            return visualDispatchPlan.cachedResults();
        }

        return pageDispatchLoop.dispatch(
                document,
                pageImageEngine,
                options,
                pageCacheContext,
                visualDispatchPlan.remainingVisualPageIndices(),
                visualDispatchPlan.cachedResults(),
                resolveDocumentTimeoutMs(pipelineSource, pageImageEngine),
                resolvePerPageTimeoutMs(pipelineSource)
        );
    }

    static VdpPageResult failedPageResult(int pageIndex, String reason) {
        return PdfVdpResultSupport.failedPageResult(pageIndex, reason);
    }

    private Map<Integer, VdpPageResult> dispatchBatchVisualTrackPages(VdpEngine batchEngine,
                                                                      Supplier<InputStream> pdfStreamSupplier,
                                                                      List<Integer> visualPageIndices,
                                                                      VdpOptions options,
                                                                      PdfVdpCache.PageCacheContext pageCacheContext) {
        CompletableFuture<List<VdpPageResult>> future;
        try {
            future = batchEngine.parsePagesAsync(pdfStreamSupplier, visualPageIndices, options, batchExecutor);
        } catch (RejectedExecutionException e) {
            return batchResultNormalizer.markAllFailed(visualPageIndices, pageCacheContext, "VDP batch dispatcher is saturated");
        }

        try {
            long timeoutMs = resolveDocumentTimeoutMs(pageCacheContext.pipelineSource(), batchEngine);
            List<VdpPageResult> pageResults = future.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            return batchResultNormalizer.normalize(visualPageIndices, pageResults, pageCacheContext);
        } catch (TimeoutException e) {
            future.cancel(true);
            return batchResultNormalizer.markAllTimedOut(
                    visualPageIndices,
                    pageCacheContext,
                    "Visual-track batch timed out after " + resolveDocumentTimeoutMs(pageCacheContext.pipelineSource(), batchEngine) + " ms"
            );
        } catch (ExecutionException e) {
            future.cancel(true);
            Throwable cause = e.getCause() == null ? e : e.getCause();
            return batchResultNormalizer.markAllFailed(visualPageIndices, pageCacheContext, "Visual-track batch failed: " + cause.getMessage());
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return batchResultNormalizer.markAllFailed(visualPageIndices, pageCacheContext, "Visual-track batch interrupted");
        }
    }

    private long resolveDocumentTimeoutMs(PipelineSource pipelineSource, VdpEngine engine) {
        long configuredTimeoutMs = pipelineSource == PipelineSource.KNOWLEDGE ? knowledgeDocumentTimeoutMs : pageDispatchTimeoutMs;
        long suggestedTimeoutMs = engine == null ? 0L : engine.suggestedDocumentTimeoutMs(pipelineSource);
        return Math.max(configuredTimeoutMs, suggestedTimeoutMs);
    }

    private long resolvePerPageTimeoutMs(PipelineSource pipelineSource) {
        return pipelineSource == PipelineSource.KNOWLEDGE ? knowledgeDocumentTimeoutMs : pageDispatchTimeoutMs;
    }
}
