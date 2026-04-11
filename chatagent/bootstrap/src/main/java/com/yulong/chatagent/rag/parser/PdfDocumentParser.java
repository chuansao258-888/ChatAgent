package com.yulong.chatagent.rag.parser;

import com.yulong.chatagent.exception.BizException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import java.util.function.Supplier;

/**
 * PDF-first parser that preserves page boundaries and reports extraction quality.
 */
@Component
@Slf4j
public class PdfDocumentParser implements DocumentParser {

    private static final int MAX_FILE_BYTES = 30 * 1024 * 1024;

    private final VdpEngineRouter engineRouter;
    private final VdpPageCacheService vdpPageCacheService;
    private final Executor vdpPageDispatchExecutor;
    private final Executor vdpBatchExecutor;
    private final int pageMaxInFlight;
    private final long pageDispatchTimeoutMs;
    private final long knowledgeDocumentTimeoutMs;
    private final MeterRegistry meterRegistry;
    private final PdfPageRenderer pageRenderer;
    private final PdfQualityRouter qualityRouter;
    private final PdfPageTextExtractor textExtractor;

    @Autowired
    public PdfDocumentParser(VdpEngineRouter engineRouter,
                             ObjectProvider<VdpPageCacheService> vdpPageCacheServiceProvider,
                             @Qualifier("vdpPageDispatchExecutor") ObjectProvider<Executor> vdpPageDispatchExecutorProvider,
                             @Qualifier("vdpBatchExecutor") ObjectProvider<Executor> vdpBatchExecutorProvider,
                             @Value("${chatagent.rag.vdp.char-density-threshold:150}") int charDensityThreshold,
                             @Value("${chatagent.rag.vdp.short-text-fast-track-threshold:80}") int shortTextFastTrackThreshold,
                             @Value("${chatagent.rag.vdp.whitespace-alignment-line-threshold:2}") int whitespaceAlignedLineThreshold,
                             @Value("${chatagent.rag.vdp.pdf-page-max-in-flight:2}") int pageMaxInFlight,
                             @Value("${chatagent.rag.vdp.pdf-page-timeout-ms:5000}") long pageDispatchTimeoutMs,
                             @Value("${chatagent.rag.vdp.knowledge-document-timeout-ms:300000}") long knowledgeDocumentTimeoutMs,
                             @Value("${chatagent.rag.vdp.pdf-render-dpi:120}") float renderDpi,
                             ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this(
                engineRouter,
                vdpPageCacheServiceProvider.getIfAvailable(),
                vdpPageDispatchExecutorProvider.getIfAvailable(() -> Runnable::run),
                vdpBatchExecutorProvider.getIfAvailable(() -> Runnable::run),
                charDensityThreshold,
                shortTextFastTrackThreshold,
                whitespaceAlignedLineThreshold,
                pageMaxInFlight,
                pageDispatchTimeoutMs,
                knowledgeDocumentTimeoutMs,
                renderDpi,
                meterRegistryProvider.getIfAvailable()
        );
    }

    PdfDocumentParser() {
        this(VdpEngineRouter.forTesting(new NoopVdpEngine()), null, Runnable::run, Runnable::run, 150, 80, 2, 2, 5000L, 300000L, 120f, null);
    }

    PdfDocumentParser(VdpEngine vdpEngine,
                      Executor vdpPageDispatchExecutor,
                      int charDensityThreshold,
                      int shortTextFastTrackThreshold,
                      int whitespaceAlignedLineThreshold,
                      int pageMaxInFlight,
                      long pageDispatchTimeoutMs,
                      long knowledgeDocumentTimeoutMs,
                      float renderDpi) {
        this(
                VdpEngineRouter.forTesting(vdpEngine),
                null,
                vdpPageDispatchExecutor,
                vdpPageDispatchExecutor,
                charDensityThreshold,
                shortTextFastTrackThreshold,
                whitespaceAlignedLineThreshold,
                pageMaxInFlight,
                pageDispatchTimeoutMs,
                knowledgeDocumentTimeoutMs,
                renderDpi,
                null
        );
    }

    PdfDocumentParser(VdpEngineRouter engineRouter,
                      Executor vdpPageDispatchExecutor,
                      int charDensityThreshold,
                      int shortTextFastTrackThreshold,
                      int whitespaceAlignedLineThreshold,
                      int pageMaxInFlight,
                      long pageDispatchTimeoutMs,
                      long knowledgeDocumentTimeoutMs,
                      float renderDpi) {
        this(
                engineRouter,
                null,
                vdpPageDispatchExecutor,
                vdpPageDispatchExecutor,
                charDensityThreshold,
                shortTextFastTrackThreshold,
                whitespaceAlignedLineThreshold,
                pageMaxInFlight,
                pageDispatchTimeoutMs,
                knowledgeDocumentTimeoutMs,
                renderDpi,
                null
        );
    }

    PdfDocumentParser(VdpEngine vdpEngine,
                      VdpPageCacheService vdpPageCacheService,
                      Executor vdpPageDispatchExecutor,
                      Executor vdpBatchExecutor,
                      int charDensityThreshold,
                      int shortTextFastTrackThreshold,
                      int whitespaceAlignedLineThreshold,
                      int pageMaxInFlight,
                      long pageDispatchTimeoutMs,
                      long knowledgeDocumentTimeoutMs,
                      float renderDpi) {
        this(
                VdpEngineRouter.forTesting(vdpEngine),
                vdpPageCacheService,
                vdpPageDispatchExecutor,
                vdpBatchExecutor,
                charDensityThreshold,
                shortTextFastTrackThreshold,
                whitespaceAlignedLineThreshold,
                pageMaxInFlight,
                pageDispatchTimeoutMs,
                knowledgeDocumentTimeoutMs,
                renderDpi,
                null
        );
    }

    PdfDocumentParser(VdpEngine vdpEngine,
                      VdpPageCacheService vdpPageCacheService,
                      Executor vdpPageDispatchExecutor,
                      Executor vdpBatchExecutor,
                      int charDensityThreshold,
                      int shortTextFastTrackThreshold,
                      int whitespaceAlignedLineThreshold,
                      int pageMaxInFlight,
                      long pageDispatchTimeoutMs,
                      long knowledgeDocumentTimeoutMs,
                      float renderDpi,
                      MeterRegistry meterRegistry) {
        this(
                VdpEngineRouter.forTesting(vdpEngine),
                vdpPageCacheService,
                vdpPageDispatchExecutor,
                vdpBatchExecutor,
                charDensityThreshold,
                shortTextFastTrackThreshold,
                whitespaceAlignedLineThreshold,
                pageMaxInFlight,
                pageDispatchTimeoutMs,
                knowledgeDocumentTimeoutMs,
                renderDpi,
                meterRegistry
        );
    }

    PdfDocumentParser(VdpEngineRouter engineRouter,
                      VdpPageCacheService vdpPageCacheService,
                      Executor vdpPageDispatchExecutor,
                      Executor vdpBatchExecutor,
                      int charDensityThreshold,
                      int shortTextFastTrackThreshold,
                      int whitespaceAlignedLineThreshold,
                      int pageMaxInFlight,
                      long pageDispatchTimeoutMs,
                      long knowledgeDocumentTimeoutMs,
                      float renderDpi) {
        this(
                engineRouter,
                vdpPageCacheService,
                vdpPageDispatchExecutor,
                vdpBatchExecutor,
                charDensityThreshold,
                shortTextFastTrackThreshold,
                whitespaceAlignedLineThreshold,
                pageMaxInFlight,
                pageDispatchTimeoutMs,
                knowledgeDocumentTimeoutMs,
                renderDpi,
                null
        );
    }

    PdfDocumentParser(VdpEngineRouter engineRouter,
                      VdpPageCacheService vdpPageCacheService,
                      Executor vdpPageDispatchExecutor,
                      Executor vdpBatchExecutor,
                      int charDensityThreshold,
                      int shortTextFastTrackThreshold,
                      int whitespaceAlignedLineThreshold,
                      int pageMaxInFlight,
                      long pageDispatchTimeoutMs,
                      long knowledgeDocumentTimeoutMs,
                      float renderDpi,
                      MeterRegistry meterRegistry) {
        this.engineRouter = engineRouter == null ? VdpEngineRouter.forTesting(new NoopVdpEngine()) : engineRouter;
        this.vdpPageCacheService = vdpPageCacheService;
        this.vdpPageDispatchExecutor = vdpPageDispatchExecutor == null ? Runnable::run : vdpPageDispatchExecutor;
        this.vdpBatchExecutor = vdpBatchExecutor == null ? Runnable::run : vdpBatchExecutor;
        this.pageMaxInFlight = Math.max(1, pageMaxInFlight);
        this.pageDispatchTimeoutMs = Math.max(1000L, pageDispatchTimeoutMs);
        this.knowledgeDocumentTimeoutMs = Math.max(this.pageDispatchTimeoutMs, knowledgeDocumentTimeoutMs);
        this.meterRegistry = meterRegistry;
        this.pageRenderer = new PdfPageRenderer(renderDpi);
        this.qualityRouter = new PdfQualityRouter(charDensityThreshold, shortTextFastTrackThreshold, whitespaceAlignedLineThreshold);
        this.textExtractor = new PdfPageTextExtractor();
    }

    @Override
    public String getParserType() {
        return ParserType.PDFBOX.getType();
    }

    @Override
    public ParseResult parse(byte[] content, String mimeType, Map<String, Object> options) {
        if (content == null || content.length == 0) {
            return ParseResult.builder()
                    .segments(List.of())
                    .parserType(ParserType.PDFBOX.getType())
                    .build();
        }
        if (content.length > MAX_FILE_BYTES) {
            return oversizedFileResult();
        }

        // TODO Phase 5b: retire byte[] entry point once all callers are stream-native.
        try (PDDocument document = Loader.loadPDF(content)) {
            return extractPages(document, content.length, options, () -> new ByteArrayInputStream(content));
        } catch (Exception e) {
            log.error("PDF parsing failed: mimeType={}", mimeType, e);
            throw new BizException("PDF parsing failed: " + e.getMessage());
        }
    }

    @Override
    public ParseResult parse(Supplier<InputStream> streamSupplier, String mimeType, Map<String, Object> options) {
        try (InputStream stream = streamSupplier.get()) {
            if (stream == null) {
                return ParseResult.ofText("");
            }
            long fileSizeBytes = resolveFileSizeBytes(options);
            if (fileSizeBytes > MAX_FILE_BYTES) {
                return oversizedFileResult();
            }
            InputStream guardedStream = fileSizeBytes > 0
                    ? stream
                    : new SizeLimitedInputStream(stream, MAX_FILE_BYTES);
            try (guardedStream;
                 RandomAccessReadBuffer readBuffer = new RandomAccessReadBuffer(guardedStream);
                 PDDocument document = Loader.loadPDF(readBuffer)) {
                long resolvedFileSizeBytes = fileSizeBytes > 0
                        ? fileSizeBytes
                        : guardedStream instanceof SizeLimitedInputStream sizeLimitedInputStream
                        ? sizeLimitedInputStream.observedBytes()
                        : 0L;
                return extractPages(document, resolvedFileSizeBytes, options, streamSupplier);
            }
        } catch (FileSizeLimitExceededException e) {
            return oversizedFileResult();
        } catch (Exception e) {
            throw new BizException("PDF parsing failed: " + e.getMessage());
        }
    }

    private ParseResult extractPages(PDDocument document,
                                     long fileSizeBytes,
                                     Map<String, Object> options,
                                     Supplier<InputStream> pdfStreamSupplier) throws Exception {
        PipelineSource pipelineSource = resolvePipelineSource(options);
        Timer.Sample sample = VdpMetricsSupport.start(meterRegistry);
        String extractionMode = "ERROR";
        try {
            int pageCount = document.getNumberOfPages();
            List<PdfPageTextExtractor.PageExtractionSnapshot> pageSnapshots = textExtractor.alignPageSnapshots(textExtractor.extractPageSnapshots(document), pageCount);
            List<String> cleanedPageTexts = pageSnapshots.stream()
                    .map(PdfPageTextExtractor.PageExtractionSnapshot::text)
                    .map(TextCleanupUtil::cleanup)
                    .toList();
            List<PdfQualityRouter.PageRoutingDecision> routingDecisions = cleanedPageTexts.stream()
                    .map(qualityRouter::decideRoute)
                    .toList();
            List<String> visualTrackPages = qualityRouter.summarizeVisualTrackPages(routingDecisions);
            VdpOptions vdpOptions = buildVdpOptions(options);
            Map<Integer, VdpPageResult> visualResults = dispatchVisualTrackPages(
                    document,
                    routingDecisions,
                    options,
                    vdpOptions,
                    pipelineSource,
                    pdfStreamSupplier
            );
            List<ParseSegment> segments = new ArrayList<>(cleanedPageTexts.size());
            int totalChars = 0;
            int visualTrackPageCount = 0;
            int visualSuccessPageCount = 0;
            int visualDegradedPageCount = 0;
            int visualFailedPageCount = 0;

            for (int page = 1; page <= cleanedPageTexts.size(); page++) {
                String pageText = cleanedPageTexts.get(page - 1);
                PdfPageTextExtractor.PageExtractionSnapshot pageSnapshot = pageSnapshots.get(page - 1);
                PdfPageTextExtractor.PageFontProfile pageFontProfile = pageSnapshot.fontProfile();
                PdfPageTextExtractor.PageStructuredText structuredPageText = textExtractor.restoreStructuredNativeMarkdown(pageSnapshot, pageText);
                PdfQualityRouter.PageRoutingDecision routingDecision = routingDecisions.get(page - 1);
                ParseSegment segment;
                if (routingDecision.isVisualTrack()) {
                    visualTrackPageCount++;
                    VdpPageResult visualResult = visualResults.getOrDefault(
                            page - 1,
                            failedPageResult(page - 1, "Visual page result missing after dispatch")
                    );
                    if (visualResult.status() == VdpPageStatus.SUCCESS) {
                        visualSuccessPageCount++;
                    } else if (visualResult.status() == VdpPageStatus.FAILED) {
                        visualFailedPageCount++;
                    } else {
                        visualDegradedPageCount++;
                    }
                    segment = buildVisualSegment(page - 1, pageText, structuredPageText, pageFontProfile, routingDecision, visualResult);
                } else {
                    segment = buildNativePageSegment(page - 1, pageText, structuredPageText, pageFontProfile, routingDecision);
                }
                totalChars += segment.charCount();
                segments.add(segment);
            }

            double charsPerPage = pageCount > 0 ? (double) totalChars / pageCount : 0;
            boolean visualTrackUnrecoverable = visualTrackPageCount > 0
                    && visualSuccessPageCount == 0
                    && totalChars < Math.max(200, 150);
            boolean ocrCandidate = totalChars == 0
                    || (charsPerPage < 50 && pageCount >= 2 && visualTrackPageCount == 0)
                    || visualTrackUnrecoverable;
            QualityLevel qualityLevel = assessQuality(totalChars, charsPerPage, fileSizeBytes);
            extractionMode = qualityLevel == QualityLevel.LOW && ocrCandidate
                    ? "OCR_REQUIRED"
                    : (visualTrackPageCount > 0 ? "PDF_VISUAL_ROUTED" : "NATIVE_TEXT");

            if ("OCR_REQUIRED".equals(extractionMode)) {
                VdpMetricsSupport.increment(
                        meterRegistry,
                        "vdp.document.ocr_required",
                        "pipelineSource",
                        VdpMetricsSupport.pipelineSourceTag(pipelineSource)
                );
            }

            Map<String, Object> diagnostics = new LinkedHashMap<>();
            diagnostics.put("totalChars", totalChars);
            diagnostics.put("pageCount", pageCount);
            diagnostics.put("charsPerPage", charsPerPage);
            diagnostics.put("ocrCandidate", ocrCandidate);
            diagnostics.put("visualTrackPageCount", visualTrackPageCount);
            diagnostics.put("fastTrackPageCount", Math.max(0, pageCount - visualTrackPageCount));
            diagnostics.put("visualTrackPages", visualTrackPages);
            diagnostics.put("visualSuccessPageCount", visualSuccessPageCount);
            diagnostics.put("visualDegradedPageCount", visualDegradedPageCount);
            diagnostics.put("visualFailedPageCount", visualFailedPageCount);
            diagnostics.put("visualTrackUnrecoverable", visualTrackUnrecoverable);

            log.info("PDF parse completed: pipelineSource={}, extractionMode={}, qualityLevel={}, pageCount={}, visualTrackPageCount={}, visualTrackPages={}, visualSuccessPageCount={}, visualDegradedPageCount={}, visualFailedPageCount={}",
                    pipelineSource,
                    extractionMode,
                    qualityLevel,
                    pageCount,
                    visualTrackPageCount,
                    visualTrackPages,
                    visualSuccessPageCount,
                    visualDegradedPageCount,
                    visualFailedPageCount);

            List<String> warnings = new ArrayList<>();
            if (qualityLevel == QualityLevel.LOW) {
                warnings.add(ocrCandidate ? "Low extraction quality; OCR required" : "Low extraction quality");
            }
            if (visualDegradedPageCount > 0) {
                warnings.add("Visual-track degraded on %d page(s)".formatted(visualDegradedPageCount));
            }
            if (visualFailedPageCount > 0) {
                warnings.add("Visual-track failed on %d page(s)".formatted(visualFailedPageCount));
            }

            return ParseResult.builder()
                    .segments(segments)
                    .parserType(ParserType.PDFBOX.getType())
                    .extractionMode(extractionMode)
                    .qualityLevel(qualityLevel)
                    .diagnostics(diagnostics)
                    .warnings(warnings)
                    .metadata(Map.of(
                            "pageCount", pageCount,
                            "visualTrackPageCount", visualTrackPageCount
                    ))
                    .build();
        } finally {
            VdpMetricsSupport.stop(
                    meterRegistry,
                    sample,
                    "vdp.document.parse.latency",
                    "pipelineSource",
                    VdpMetricsSupport.pipelineSourceTag(pipelineSource),
                    "extractionMode",
                    extractionMode
            );
        }
    }

    private Map<Integer, VdpPageResult> dispatchVisualTrackPages(PDDocument document,
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
            PageCacheContext batchCacheContext = buildPageCacheContext(rawOptions, pipelineSource, options, batchEngine);
            VisualDispatchPlan cachedBatchPlan = planVisualDispatch(routingDecisions, batchCacheContext);
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
                    pageImageEngine.engineId(),
                    visualTrackPages);
        }
        PageCacheContext pageCacheContext = buildPageCacheContext(rawOptions, pipelineSource, options, pageImageEngine);
        VisualDispatchPlan visualDispatchPlan = planVisualDispatch(routingDecisions, pageCacheContext);
        if (visualDispatchPlan.remainingVisualPageIndices().isEmpty()) {
            return visualDispatchPlan.cachedResults();
        }

        long documentTimeoutMs = resolveDocumentTimeoutMs(pipelineSource, pageImageEngine);
        long documentDeadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(documentTimeoutMs);
        int maxInFlight = Math.max(1, pageMaxInFlight);
        long perPageTimeoutMs = resolvePerPageTimeoutMs(pipelineSource);
        PDFRenderer pdfRenderer = new PDFRenderer(document);
        List<VisualPageDispatch> inFlight = new ArrayList<>(maxInFlight);
        int nextPageCursor = 0;
        List<Integer> remainingVisualPageIndices = visualDispatchPlan.remainingVisualPageIndices();
        Map<Integer, VdpPageResult> results = new HashMap<>(visualDispatchPlan.cachedResults());
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
            while (nextPageCursor < remainingVisualPageIndices.size() && inFlight.size() < maxInFlight) {
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
                    results.put(pageIndex, failedPageResult(pageIndex, "Page rendering exceeded memory budget"));
                } catch (Exception e) {
                    results.put(pageIndex, failedPageResult(pageIndex, "Page rendering failed: " + e.getMessage()));
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

    private VisualDispatchPlan planVisualDispatch(List<PdfQualityRouter.PageRoutingDecision> routingDecisions,
                                                  PageCacheContext pageCacheContext) {
        List<Integer> remainingVisualPageIndices = new ArrayList<>();
        Map<Integer, VdpPageResult> cachedResults = new HashMap<>();
        for (int pageIndex = 0; pageIndex < routingDecisions.size(); pageIndex++) {
            if (!routingDecisions.get(pageIndex).isVisualTrack()) {
                continue;
            }
            VdpPageResult cached = getCachedPageResult(pageCacheContext, pageIndex);
            if (cached != null) {
                cachedResults.put(pageIndex, cached);
            } else {
                remainingVisualPageIndices.add(pageIndex);
            }
        }
        return new VisualDispatchPlan(remainingVisualPageIndices, cachedResults);
    }

    private Map<Integer, VdpPageResult> dispatchBatchVisualTrackPages(VdpEngine batchEngine,
                                                                      Supplier<InputStream> pdfStreamSupplier,
                                                                      List<Integer> visualPageIndices,
                                                                      VdpOptions options,
                                                                      PageCacheContext pageCacheContext) {
        CompletableFuture<List<VdpPageResult>> future;
        try {
            future = batchEngine.parsePagesAsync(pdfStreamSupplier, visualPageIndices, options, vdpBatchExecutor);
        } catch (RejectedExecutionException e) {
            return markAllBatchPagesFailed(visualPageIndices, pageCacheContext, "VDP batch dispatcher is saturated");
        }

        try {
            long timeoutMs = resolveDocumentTimeoutMs(pageCacheContext.pipelineSource(), batchEngine);
            List<VdpPageResult> pageResults = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            return normalizeBatchVisualResults(visualPageIndices, pageResults, pageCacheContext);
        } catch (TimeoutException e) {
            future.cancel(true);
            return markAllBatchPagesTimedOut(
                    visualPageIndices,
                    pageCacheContext,
                    "Visual-track batch timed out after " + resolveDocumentTimeoutMs(pageCacheContext.pipelineSource(), batchEngine) + " ms"
            );
        } catch (ExecutionException e) {
            future.cancel(true);
            Throwable cause = e.getCause() == null ? e : e.getCause();
            return markAllBatchPagesFailed(visualPageIndices, pageCacheContext, "Visual-track batch failed: " + cause.getMessage());
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return markAllBatchPagesFailed(visualPageIndices, pageCacheContext, "Visual-track batch interrupted");
        }
    }

    private VisualPageDispatch submitVisualPage(VdpEngine pageImageEngine,
                                                int pageIndex,
                                                PdfPageRenderer.RenderedPageImage renderedPageImage,
                                                VdpOptions options,
                                                long dispatchStartedAtNanos) {
        try {
            CompletableFuture<VdpPageResult> future = CompletableFuture.supplyAsync(
                    () -> parseVisualPage(pageImageEngine, pageIndex, renderedPageImage, options),
                    vdpPageDispatchExecutor
            );
            return new VisualPageDispatch(pageIndex, dispatchStartedAtNanos, future);
        } catch (RejectedExecutionException e) {
            renderedPageImage.clear();
            return new VisualPageDispatch(
                    pageIndex,
                    dispatchStartedAtNanos,
                    CompletableFuture.completedFuture(failedPageResult(pageIndex, "VDP page dispatcher is saturated"))
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
        } catch (ExecutionException e) {
            // Exceptional completion is resolved in collectCompletedVisualPages().
        }
    }

    private long resolveDocumentTimeoutMs(PipelineSource pipelineSource, VdpEngine engine) {
        long configuredTimeoutMs = pipelineSource == PipelineSource.KNOWLEDGE ? knowledgeDocumentTimeoutMs : pageDispatchTimeoutMs;
        long suggestedTimeoutMs = engine == null ? 0L : engine.suggestedDocumentTimeoutMs(pipelineSource);
        return Math.max(configuredTimeoutMs, suggestedTimeoutMs);
    }

    private PageCacheContext buildPageCacheContext(Map<String, Object> rawOptions,
                                                   PipelineSource pipelineSource,
                                                   VdpOptions options,
                                                   VdpEngine resolvedEngine) {
        return new PageCacheContext(
                pipelineSource,
                stringOption(rawOptions, "sessionId"),
                stringOption(rawOptions, "documentCacheKey"),
                resolvedEngine == null ? "unknown" : resolvedEngine.engineId(),
                resolvedEngine == null ? "default" : resolvedEngine.promptVersion(),
                options == null ? null : options.languageHint(),
                options != null && options.recognizeFormulas()
        );
    }

    private VdpPageResult getCachedPageResult(PageCacheContext pageCacheContext, int pageIndex) {
        if (vdpPageCacheService == null || !StringUtils.hasText(pageCacheContext.documentCacheKey())) {
            return null;
        }
        return vdpPageCacheService.get(
                pageCacheContext.pipelineSource(),
                pageCacheContext.engineId(),
                pageCacheContext.promptVersion(),
                buildPageCacheDigest(pageCacheContext, pageIndex),
                pageCacheContext.sessionId()
        );
    }

    private void cachePageResult(PageCacheContext pageCacheContext, int pageIndex, VdpPageResult result) {
        if (vdpPageCacheService == null || !StringUtils.hasText(pageCacheContext.documentCacheKey()) || result == null) {
            return;
        }
        vdpPageCacheService.put(
                pageCacheContext.pipelineSource(),
                pageCacheContext.engineId(),
                pageCacheContext.promptVersion(),
                buildPageCacheDigest(pageCacheContext, pageIndex),
                pageCacheContext.sessionId(),
                result
        );
    }

    private void cachePageResults(PageCacheContext pageCacheContext, Map<Integer, VdpPageResult> pageResults) {
        if (vdpPageCacheService == null
                || pageResults == null
                || pageResults.isEmpty()
                || !StringUtils.hasText(pageCacheContext.documentCacheKey())) {
            return;
        }
        Map<String, VdpPageResult> entriesByDigest = new LinkedHashMap<>();
        pageResults.forEach((pageIndex, result) -> entriesByDigest.put(buildPageCacheDigest(pageCacheContext, pageIndex), result));
        vdpPageCacheService.putAll(
                pageCacheContext.pipelineSource(),
                pageCacheContext.engineId(),
                pageCacheContext.promptVersion(),
                entriesByDigest,
                pageCacheContext.sessionId()
        );
    }

    private String buildPageCacheDigest(PageCacheContext pageCacheContext, int pageIndex) {
        return "pdf-page:"
                + pageCacheContext.documentCacheKey()
                + ':'
                + pageIndex
                + ':'
                + Math.round(pageRenderer.renderDpi() * 10.0f)
                + ':'
                + normalizeCachePart(pageCacheContext.languageHint())
                + ':'
                + (pageCacheContext.recognizeFormulas() ? '1' : '0');
    }

    private String normalizeCachePart(String value) {
        if (!StringUtils.hasText(value)) {
            return "default";
        }
        return value.trim().toLowerCase();
    }

    private void recordPageTimeout(String engineId) {
        VdpMetricsSupport.increment(
                meterRegistry,
                "vdp.page.timeout",
                "engineId",
                VdpMetricsSupport.tagValue(engineId, "unknown")
        );
    }

    private void recordResolvedVisualPage(String engineId, VdpPageResult result) {
        if (result == null || result.status() == null) {
            return;
        }
        String metricName = switch (result.status()) {
            case SUCCESS -> "vdp.page.success";
            case DEGRADED -> "vdp.page.degraded";
            case FAILED -> "vdp.page.failed";
        };
        VdpMetricsSupport.increment(
                meterRegistry,
                metricName,
                "engineId",
                VdpMetricsSupport.tagValue(engineId, "unknown")
        );
    }

    private long resolvePerPageTimeoutMs(PipelineSource pipelineSource) {
        return pipelineSource == PipelineSource.KNOWLEDGE ? knowledgeDocumentTimeoutMs : pageDispatchTimeoutMs;
    }

    private long remainingBudgetMs(long deadlineNanos) {
        long remainingNanos = Math.max(0L, deadlineNanos - System.nanoTime());
        if (remainingNanos == 0L) {
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
            long remainingMs = Math.max(0L, perPageTimeoutMs - elapsedMs);
            minRemainingMs = Math.min(minRemainingMs, remainingMs);
        }
        return minRemainingMs == Long.MAX_VALUE ? 0L : minRemainingMs;
    }

    private boolean expireTimedOutVisualPages(List<VisualPageDispatch> inFlight,
                                              Map<Integer, VdpPageResult> results,
                                              PageCacheContext pageCacheContext,
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
            VdpPageResult timedOutResult = failedPageResult(dispatch.pageIndex(), "Visual page timed out after " + perPageTimeoutMs + " ms");
            results.putIfAbsent(
                    dispatch.pageIndex(),
                    timedOutResult
            );
            cachePageResult(pageCacheContext, dispatch.pageIndex(), timedOutResult);
            recordPageTimeout(pageCacheContext.engineId());
            iterator.remove();
            expired = true;
        }
        return expired;
    }

    private boolean collectCompletedVisualPages(List<VisualPageDispatch> inFlight,
                                                Map<Integer, VdpPageResult> results,
                                                PageCacheContext pageCacheContext) {
        boolean collected = false;
        for (Iterator<VisualPageDispatch> iterator = inFlight.iterator(); iterator.hasNext(); ) {
            VisualPageDispatch dispatch = iterator.next();
            if (!dispatch.future().isDone()) {
                continue;
            }
            VdpPageResult resolved = resolveCompletedVisualPage(dispatch);
            results.putIfAbsent(dispatch.pageIndex(), resolved);
            cachePageResult(pageCacheContext, dispatch.pageIndex(), resolved);
            recordResolvedVisualPage(pageCacheContext.engineId(), resolved);
            iterator.remove();
            collected = true;
        }
        return collected;
    }

    private VdpPageResult resolveCompletedVisualPage(VisualPageDispatch dispatch) {
        try {
            VdpPageResult result = dispatch.future().join();
            if (result == null) {
                return failedPageResult(dispatch.pageIndex(), "Visual page dispatch returned null");
            }
            return result;
        } catch (CancellationException e) {
            return failedPageResult(dispatch.pageIndex(), "Visual page dispatch cancelled");
        } catch (CompletionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            return failedPageResult(dispatch.pageIndex(), "Visual page dispatch failed: " + cause.getMessage());
        }
    }

    private void cancelInFlightVisualPages(List<VisualPageDispatch> inFlight,
                                           Map<Integer, VdpPageResult> results,
                                           PageCacheContext pageCacheContext,
                                           String reason) {
        for (Iterator<VisualPageDispatch> iterator = inFlight.iterator(); iterator.hasNext(); ) {
            VisualPageDispatch dispatch = iterator.next();
            dispatch.future().cancel(true);
            VdpPageResult failedResult = failedPageResult(dispatch.pageIndex(), reason);
            VdpPageResult existing = results.putIfAbsent(dispatch.pageIndex(), failedResult);
            if (existing == null) {
                cachePageResult(pageCacheContext, dispatch.pageIndex(), failedResult);
                recordResolvedVisualPage(pageCacheContext.engineId(), failedResult);
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
            VdpPageResult failedResult = failedPageResult(pageIndex, reason);
            VdpPageResult existing = results.putIfAbsent(pageIndex, failedResult);
            if (existing == null) {
                recordResolvedVisualPage(engineId, failedResult);
            }
        }
    }

    private Map<Integer, VdpPageResult> normalizeBatchVisualResults(List<Integer> visualPageIndices,
                                                                    List<VdpPageResult> pageResults,
                                                                    PageCacheContext pageCacheContext) {
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
                    recordResolvedVisualPage(pageCacheContext.engineId(), pageResult);
                }
            }
        }
        cachePageResults(pageCacheContext, allPageResults);
        for (Integer pageIndex : visualPageIndices) {
            VdpPageResult missingResult = failedPageResult(pageIndex, "Visual-track batch did not return a result");
            VdpPageResult existing = results.putIfAbsent(pageIndex, missingResult);
            if (existing == null) {
                cachePageResult(pageCacheContext, pageIndex, missingResult);
                recordResolvedVisualPage(pageCacheContext.engineId(), missingResult);
            }
        }
        return results;
    }

    private ParseResult oversizedFileResult() {
        return ParseResult.builder()
                .segments(List.of())
                .parserType(ParserType.PDFBOX.getType())
                .qualityLevel(QualityLevel.REJECTED)
                .warnings(List.of("File exceeds 30MB limit"))
                .build();
    }

    private Map<Integer, VdpPageResult> markAllBatchPagesFailed(List<Integer> visualPageIndices,
                                                                PageCacheContext pageCacheContext,
                                                                String reason) {
        Map<Integer, VdpPageResult> results = new HashMap<>();
        for (Integer pageIndex : visualPageIndices) {
            VdpPageResult failedResult = failedPageResult(pageIndex, reason);
            results.put(pageIndex, failedResult);
            cachePageResult(pageCacheContext, pageIndex, failedResult);
            recordResolvedVisualPage(pageCacheContext.engineId(), failedResult);
        }
        return results;
    }

    private Map<Integer, VdpPageResult> markAllBatchPagesTimedOut(List<Integer> visualPageIndices,
                                                                  PageCacheContext pageCacheContext,
                                                                  String reason) {
        Map<Integer, VdpPageResult> results = new HashMap<>();
        for (Integer pageIndex : visualPageIndices) {
            VdpPageResult timedOutResult = failedPageResult(pageIndex, reason);
            results.put(pageIndex, timedOutResult);
            cachePageResult(pageCacheContext, pageIndex, timedOutResult);
            recordPageTimeout(pageCacheContext.engineId());
        }
        return results;
    }

    private ParseSegment buildNativePageSegment(int pageIndex,
                                                String pageText,
                                                PdfPageTextExtractor.PageStructuredText structuredPageText,
                                                PdfPageTextExtractor.PageFontProfile pageFontProfile,
                                                PdfQualityRouter.PageRoutingDecision routingDecision) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("pageNumber", pageIndex + 1);
        metadata.put("pageRoute", routingDecision.route().name());
        metadata.put("pageRouteReason", routingDecision.reason());
        metadata.put("alignedWhitespaceLines", routingDecision.alignedWhitespaceLines());
        metadata.put("nativeCharCount", pageText.length());
        metadata.put("contentOrigin", "NATIVE");
        attachFontMetadata(metadata, pageFontProfile);
        attachStructureMetadata(metadata, structuredPageText);
        return new ParseSegment(structuredPageText.text(), pageIndex, SegmentType.PAGE, metadata);
    }

    private ParseSegment buildVisualSegment(int pageIndex,
                                            String nativeText,
                                            PdfPageTextExtractor.PageStructuredText structuredPageText,
                                            PdfPageTextExtractor.PageFontProfile pageFontProfile,
                                            PdfQualityRouter.PageRoutingDecision routingDecision,
                                            VdpPageResult visualResult) {
        String markdown = TextCleanupUtil.cleanup(visualResult.markdown());
        Map<String, Object> metadata = new LinkedHashMap<>(visualResult.metadata());
        metadata.put("pageNumber", pageIndex + 1);
        metadata.put("pageRoute", routingDecision.route().name());
        metadata.put("pageRouteReason", routingDecision.reason());
        metadata.put("alignedWhitespaceLines", routingDecision.alignedWhitespaceLines());
        metadata.put("nativeCharCount", nativeText.length());
        metadata.put("vdpStatus", visualResult.status().name());
        metadata.putIfAbsent("visualType", "IMAGE");
        metadata.putIfAbsent("contentOrigin", "VDP_TRANSCRIBED");
        attachFontMetadata(metadata, pageFontProfile);
        if (visualResult.status() != VdpPageStatus.SUCCESS) {
            metadata.put("degraded", true);
        }

        if (StringUtils.hasText(markdown)) {
            return new ParseSegment(markdown, pageIndex, SegmentType.PAGE, metadata);
        }
        if (StringUtils.hasText(nativeText)) {
            metadata.put("contentOrigin", "NATIVE");
            metadata.put("visualFallback", "NATIVE_TEXT");
            metadata.put("degraded", true);
            attachStructureMetadata(metadata, structuredPageText);
            return new ParseSegment(structuredPageText.text(), pageIndex, SegmentType.PAGE, metadata);
        }
        metadata.put("visualFallback", "EMPTY_PAGE");
        metadata.put("degraded", true);
        metadata.putIfAbsent("interpretiveNote", "[此页内容解析失败]");
        return new ParseSegment("", pageIndex, SegmentType.PAGE, metadata);
    }

    private void attachFontMetadata(Map<String, Object> metadata, PdfPageTextExtractor.PageFontProfile pageFontProfile) {
        if (metadata == null || pageFontProfile == null || pageFontProfile.sampleCount() <= 0) {
            return;
        }
        metadata.put("dominantFontSizePt", pageFontProfile.dominantFontSizePt());
        metadata.put("maxFontSizePt", pageFontProfile.maxFontSizePt());
        metadata.put("minFontSizePt", pageFontProfile.minFontSizePt());
        metadata.put("fontSampleCount", pageFontProfile.sampleCount());
        metadata.put("headingLikePage", textExtractor.isHeadingLikePage(pageFontProfile));
    }

    private void attachStructureMetadata(Map<String, Object> metadata, PdfPageTextExtractor.PageStructuredText structuredPageText) {
        if (metadata == null || structuredPageText == null) {
            return;
        }
        metadata.put("fontAwareStructureRestored", structuredPageText.restored());
        if (structuredPageText.headingCount() > 0) {
            metadata.put("restoredHeadingCount", structuredPageText.headingCount());
        }
    }

    private VdpPageResult parseVisualPage(VdpEngine pageImageEngine,
                                          int pageIndex,
                                          PdfPageRenderer.RenderedPageImage renderedPageImage,
                                          VdpOptions options) {
        if (pageImageEngine == null || !pageImageEngine.supportedModes().contains(VdpMode.PAGE_IMAGE)) {
            renderedPageImage.clear();
            return failedPageResult(pageIndex, "VDP engine does not support page-image mode");
        }
        try {
            VdpPageResult result = pageImageEngine.parsePage(
                    () -> new ByteArrayInputStream(renderedPageImage.bytes(), 0, renderedPageImage.length()),
                    "png",
                    options
            );
            if (result == null) {
                return failedPageResult(pageIndex, "VDP engine returned null");
            }
            return new VdpPageResult(pageIndex, result.markdown(), result.status(), result.metadata());
        } catch (OutOfMemoryError error) {
            log.error("PDF visual-track page parsing ran out of memory: pageNumber={}", pageIndex + 1, error);
            return failedPageResult(pageIndex, "Visual page parsing exceeded memory budget");
        } catch (Exception e) {
            log.warn("PDF visual-track page parsing failed: pageNumber={}, error={}", pageIndex + 1, e.getMessage());
            return failedPageResult(pageIndex, e.getMessage());
        } finally {
            // Clear rendered page bytes after dispatch because they contain user-uploaded page imagery.
            renderedPageImage.clear();
        }
    }

    private VdpPageResult failedPageResult(int pageIndex, String reason) {
        String normalizedReason = StringUtils.hasText(reason) ? reason.trim() : "Unknown visual parsing failure";
        return new VdpPageResult(
                pageIndex,
                "",
                VdpPageStatus.FAILED,
                Map.of(
                        "contentOrigin", "VDP_TRANSCRIBED",
                        "visualType", "IMAGE",
                        "degraded", true,
                        "engineId", "pdf-visual-track",
                        "interpretiveNote", "[此页内容解析失败]: " + normalizedReason
                )
        );
    }

    private VdpOptions buildVdpOptions(Map<String, Object> options) {
        Map<String, Object> extra = new LinkedHashMap<>();
        PipelineSource pipelineSource = resolvePipelineSource(options);
        extra.put("pipelineSource", pipelineSource);
        String sessionId = stringOption(options, "sessionId");
        if (StringUtils.hasText(sessionId)) {
            extra.put("sessionId", sessionId.trim());
        }
        return new VdpOptions(
                booleanOption(options, "recognizeFormulas"),
                stringOption(options, "languageHint"),
                extra
        );
    }

    private PipelineSource resolvePipelineSource(Map<String, Object> options) {
        Object value = options == null ? null : options.get("pipelineSource");
        return value instanceof PipelineSource source ? source : PipelineSource.KNOWLEDGE;
    }

    private boolean booleanOption(Map<String, Object> options, String key) {
        Object value = options == null ? null : options.get(key);
        return value instanceof Boolean bool && bool;
    }

    private String stringOption(Map<String, Object> options, String key) {
        Object value = options == null ? null : options.get(key);
        return value == null ? null : value.toString();
    }

    private QualityLevel assessQuality(int totalChars, double charsPerPage, long fileSizeBytes) {
        if (totalChars == 0) {
            return QualityLevel.LOW;
        }
        if (fileSizeBytes >= 1_000_000 && totalChars < 200) {
            return QualityLevel.LOW;
        }
        if (charsPerPage < 30) {
            return QualityLevel.LOW;
        }
        if (charsPerPage >= 80) {
            return QualityLevel.HIGH;
        }
        return QualityLevel.MEDIUM;
    }

    private long resolveFileSizeBytes(Map<String, Object> options) {
        if (options == null) {
            return 0L;
        }
        Object value = options.get("fileSizeBytes");
        if (value instanceof Number number) {
            return Math.max(0L, number.longValue());
        }
        return 0L;
    }

    @Override
    public boolean supports(DetectedFileType type) {
        return type != null && type.isPdf();
    }

    private static final class FileSizeLimitExceededException extends IOException {

        private FileSizeLimitExceededException(String message) {
            super(message);
        }
    }

    private static final class SizeLimitedInputStream extends FilterInputStream {

        private final long maxBytes;
        private long observedBytes;

        private SizeLimitedInputStream(InputStream inputStream, long maxBytes) {
            super(inputStream);
            this.maxBytes = Math.max(1L, maxBytes);
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                recordBytes(1);
            }
            return value;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int read = super.read(b, off, len);
            if (read > 0) {
                recordBytes(read);
            }
            return read;
        }

        private void recordBytes(int bytesRead) throws FileSizeLimitExceededException {
            observedBytes += bytesRead;
            if (observedBytes > maxBytes) {
                throw new FileSizeLimitExceededException("PDF exceeds " + maxBytes + " bytes");
            }
        }

        private long observedBytes() {
            return observedBytes;
        }
    }

    private record VisualPageDispatch(int pageIndex, long submittedAtNanos, CompletableFuture<VdpPageResult> future) {
    }

    private record VisualDispatchPlan(List<Integer> remainingVisualPageIndices,
                                      Map<Integer, VdpPageResult> cachedResults) {
    }

    private record PageCacheContext(PipelineSource pipelineSource,
                                    String sessionId,
                                    String documentCacheKey,
                                    String engineId,
                                    String promptVersion,
                                    String languageHint,
                                    boolean recognizeFormulas) {
    }
}
