package com.yulong.chatagent.rag.parser;

import com.yulong.chatagent.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Supplier;

/**
 * PDF-first parser that preserves page boundaries and reports extraction quality.
 */
@Component
@Slf4j
public class PdfDocumentParser implements DocumentParser {

    private static final int MAX_FILE_BYTES = 30 * 1024 * 1024;
    private static final Pattern ALIGNED_WHITESPACE_PATTERN = Pattern.compile(" {3,}");
    private static final Pattern SENTENCE_PUNCTUATION_PATTERN = Pattern.compile("[。！？；;.!?]");
    private static final Pattern DIGIT_PATTERN = Pattern.compile("\\d");
    private static final double HEADING_FONT_DELTA_PT = 1.5d;

    private final VdpEngine vdpEngine;
    private final VdpPageCacheService vdpPageCacheService;
    private final Executor vdpPageDispatchExecutor;
    private final Executor vdpBatchExecutor;
    private final int charDensityThreshold;
    private final int shortTextFastTrackThreshold;
    private final int whitespaceAlignedLineThreshold;
    private final int pageMaxInFlight;
    private final long pageDispatchTimeoutMs;
    private final long knowledgeDocumentTimeoutMs;
    private final float renderDpi;

    @Autowired
    public PdfDocumentParser(VdpEngine vdpEngine,
                             ObjectProvider<VdpPageCacheService> vdpPageCacheServiceProvider,
                             @Qualifier("vdpPageDispatchExecutor") ObjectProvider<Executor> vdpPageDispatchExecutorProvider,
                             @Qualifier("vdpBatchExecutor") ObjectProvider<Executor> vdpBatchExecutorProvider,
                             @Value("${chatagent.rag.vdp.char-density-threshold:150}") int charDensityThreshold,
                             @Value("${chatagent.rag.vdp.short-text-fast-track-threshold:80}") int shortTextFastTrackThreshold,
                             @Value("${chatagent.rag.vdp.whitespace-alignment-line-threshold:2}") int whitespaceAlignedLineThreshold,
                             @Value("${chatagent.rag.vdp.pdf-page-max-in-flight:2}") int pageMaxInFlight,
                             @Value("${chatagent.rag.vdp.pdf-page-timeout-ms:5000}") long pageDispatchTimeoutMs,
                             @Value("${chatagent.rag.vdp.knowledge-document-timeout-ms:120000}") long knowledgeDocumentTimeoutMs,
                             @Value("${chatagent.rag.vdp.pdf-render-dpi:120}") float renderDpi) {
        this(
                vdpEngine,
                vdpPageCacheServiceProvider.getIfAvailable(),
                vdpPageDispatchExecutorProvider.getIfAvailable(() -> Runnable::run),
                vdpBatchExecutorProvider.getIfAvailable(() -> Runnable::run),
                charDensityThreshold,
                shortTextFastTrackThreshold,
                whitespaceAlignedLineThreshold,
                pageMaxInFlight,
                pageDispatchTimeoutMs,
                knowledgeDocumentTimeoutMs,
                renderDpi
        );
    }

    PdfDocumentParser() {
        this(new NoopVdpEngine(), null, Runnable::run, Runnable::run, 150, 80, 2, 2, 5000L, 120000L, 120f);
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
                vdpEngine,
                null,
                vdpPageDispatchExecutor,
                vdpPageDispatchExecutor,
                charDensityThreshold,
                shortTextFastTrackThreshold,
                whitespaceAlignedLineThreshold,
                pageMaxInFlight,
                pageDispatchTimeoutMs,
                knowledgeDocumentTimeoutMs,
                renderDpi
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
        this.vdpEngine = vdpEngine;
        this.vdpPageCacheService = vdpPageCacheService;
        this.vdpPageDispatchExecutor = vdpPageDispatchExecutor == null ? Runnable::run : vdpPageDispatchExecutor;
        this.vdpBatchExecutor = vdpBatchExecutor == null ? Runnable::run : vdpBatchExecutor;
        this.charDensityThreshold = Math.max(1, charDensityThreshold);
        this.shortTextFastTrackThreshold = Math.max(1, shortTextFastTrackThreshold);
        this.whitespaceAlignedLineThreshold = Math.max(1, whitespaceAlignedLineThreshold);
        this.pageMaxInFlight = Math.max(1, pageMaxInFlight);
        this.pageDispatchTimeoutMs = Math.max(1000L, pageDispatchTimeoutMs);
        this.knowledgeDocumentTimeoutMs = Math.max(this.pageDispatchTimeoutMs, knowledgeDocumentTimeoutMs);
        this.renderDpi = Math.max(72f, renderDpi);
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
            return ParseResult.builder()
                    .segments(List.of())
                    .parserType(ParserType.PDFBOX.getType())
                    .qualityLevel(QualityLevel.REJECTED)
                    .warnings(List.of("File exceeds 30MB limit"))
                    .build();
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
                return ParseResult.builder()
                        .segments(List.of())
                        .parserType(ParserType.PDFBOX.getType())
                        .qualityLevel(QualityLevel.REJECTED)
                        .warnings(List.of("File exceeds 30MB limit"))
                        .build();
            }
            try (RandomAccessReadBuffer readBuffer = new RandomAccessReadBuffer(stream);
                 PDDocument document = Loader.loadPDF(readBuffer)) {
                return extractPages(document, fileSizeBytes, options, streamSupplier);
            }
        } catch (Exception e) {
            throw new BizException("PDF parsing failed: " + e.getMessage());
        }
    }

    private ParseResult extractPages(PDDocument document,
                                     long fileSizeBytes,
                                     Map<String, Object> options,
                                     Supplier<InputStream> pdfStreamSupplier) throws Exception {
        int pageCount = document.getNumberOfPages();
        List<PageExtractionSnapshot> pageSnapshots = alignPageSnapshots(extractPageSnapshots(document), pageCount);
        List<String> cleanedPageTexts = pageSnapshots.stream()
                .map(PageExtractionSnapshot::text)
                .map(TextCleanupUtil::cleanup)
                .toList();
        List<PageRoutingDecision> routingDecisions = cleanedPageTexts.stream()
                .map(this::decideRoute)
                .toList();
        VdpOptions vdpOptions = buildVdpOptions(options);
        PipelineSource pipelineSource = resolvePipelineSource(options);
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
            PageExtractionSnapshot pageSnapshot = pageSnapshots.get(page - 1);
            PageFontProfile pageFontProfile = pageSnapshot.fontProfile();
            PageStructuredText structuredPageText = restoreStructuredNativeMarkdown(pageSnapshot, pageText);
            PageRoutingDecision routingDecision = routingDecisions.get(page - 1);
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
                && totalChars < Math.max(200, charDensityThreshold);
        boolean ocrCandidate = totalChars == 0
                || (charsPerPage < 50 && pageCount >= 2 && visualTrackPageCount == 0)
                || visualTrackUnrecoverable;
        QualityLevel qualityLevel = assessQuality(totalChars, charsPerPage, fileSizeBytes);
        String extractionMode = qualityLevel == QualityLevel.LOW && ocrCandidate
                ? "OCR_REQUIRED"
                : (visualTrackPageCount > 0 ? "PDF_VISUAL_ROUTED" : "NATIVE_TEXT");

        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("totalChars", totalChars);
        diagnostics.put("pageCount", pageCount);
        diagnostics.put("charsPerPage", charsPerPage);
        diagnostics.put("ocrCandidate", ocrCandidate);
        diagnostics.put("visualTrackPageCount", visualTrackPageCount);
        diagnostics.put("fastTrackPageCount", Math.max(0, pageCount - visualTrackPageCount));
        diagnostics.put("visualSuccessPageCount", visualSuccessPageCount);
        diagnostics.put("visualDegradedPageCount", visualDegradedPageCount);
        diagnostics.put("visualFailedPageCount", visualFailedPageCount);
        diagnostics.put("visualTrackUnrecoverable", visualTrackUnrecoverable);

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
    }

    private Map<Integer, VdpPageResult> dispatchVisualTrackPages(PDDocument document,
                                                                 List<PageRoutingDecision> routingDecisions,
                                                                 Map<String, Object> rawOptions,
                                                                 VdpOptions options,
                                                                 PipelineSource pipelineSource,
                                                                 Supplier<InputStream> pdfStreamSupplier) {
        List<Integer> remainingVisualPageIndices = new ArrayList<>();
        Map<Integer, VdpPageResult> results = new HashMap<>();
        PageCacheContext pageCacheContext = buildPageCacheContext(rawOptions, pipelineSource, options);
        for (int pageIndex = 0; pageIndex < routingDecisions.size(); pageIndex++) {
            if (routingDecisions.get(pageIndex).isVisualTrack()) {
                VdpPageResult cached = getCachedPageResult(pageCacheContext, pageIndex);
                if (cached != null) {
                    results.put(pageIndex, cached);
                } else {
                    remainingVisualPageIndices.add(pageIndex);
                }
            }
        }
        if (remainingVisualPageIndices.isEmpty()) {
            return results;
        }
        if (supportsBatchPdfDispatch()) {
            results.putAll(dispatchBatchVisualTrackPages(pdfStreamSupplier, remainingVisualPageIndices, options, pageCacheContext));
            return results;
        }

        long documentDeadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(resolveDocumentTimeoutMs(pipelineSource));
        int maxInFlight = Math.max(1, pageMaxInFlight);
        long perPageTimeoutMs = resolvePerPageTimeoutMs(pipelineSource);
        PDFRenderer pdfRenderer = new PDFRenderer(document);
        List<VisualPageDispatch> inFlight = new ArrayList<>(maxInFlight);
        int nextPageCursor = 0;
        while (nextPageCursor < remainingVisualPageIndices.size() || !inFlight.isEmpty()) {
            if (Thread.currentThread().isInterrupted()) {
                cancelInFlightVisualPages(inFlight, results, pageCacheContext, "Visual page dispatch interrupted");
                markRemainingPagesAsFailed(results, remainingVisualPageIndices, nextPageCursor, "Visual page dispatch interrupted");
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
                markRemainingPagesAsFailed(results, remainingVisualPageIndices, nextPageCursor, "Visual-track document budget exhausted");
                break;
            }
            while (nextPageCursor < remainingVisualPageIndices.size() && inFlight.size() < maxInFlight) {
                int pageIndex = remainingVisualPageIndices.get(nextPageCursor);
                if (remainingBudgetMs(documentDeadlineNanos) <= 0) {
                    break;
                }
                long dispatchStartedAtNanos = System.nanoTime();
                try {
                    RenderedPageImage renderedPageImage = renderPageAsPng(pdfRenderer, pageIndex);
                    inFlight.add(submitVisualPage(pageIndex, renderedPageImage, options, dispatchStartedAtNanos));
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
                markRemainingPagesAsFailed(results, remainingVisualPageIndices, nextPageCursor, "Visual page dispatch interrupted");
                break;
            }
            collectCompletedVisualPages(inFlight, results, pageCacheContext);
            expireTimedOutVisualPages(inFlight, results, pageCacheContext, perPageTimeoutMs);
        }
        return results;
    }

    private Map<Integer, VdpPageResult> dispatchBatchVisualTrackPages(Supplier<InputStream> pdfStreamSupplier,
                                                                      List<Integer> visualPageIndices,
                                                                      VdpOptions options,
                                                                      PageCacheContext pageCacheContext) {
        CompletableFuture<List<VdpPageResult>> future;
        try {
            future = CompletableFuture.supplyAsync(
                    () -> vdpEngine.parsePages(pdfStreamSupplier, visualPageIndices, options),
                    vdpBatchExecutor
            );
        } catch (RejectedExecutionException e) {
            return markAllBatchPagesFailed(visualPageIndices, pageCacheContext, "VDP batch dispatcher is saturated");
        }

        try {
            List<VdpPageResult> pageResults = future.get(resolveDocumentTimeoutMs(pageCacheContext.pipelineSource()), TimeUnit.MILLISECONDS);
            return normalizeBatchVisualResults(visualPageIndices, pageResults, pageCacheContext);
        } catch (TimeoutException e) {
            future.cancel(true);
            return markAllBatchPagesFailed(
                    visualPageIndices,
                    pageCacheContext,
                    "Visual-track batch timed out after " + resolveDocumentTimeoutMs(pageCacheContext.pipelineSource()) + " ms"
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

    private VisualPageDispatch submitVisualPage(int pageIndex,
                                                RenderedPageImage renderedPageImage,
                                                VdpOptions options,
                                                long dispatchStartedAtNanos) {
        try {
            CompletableFuture<VdpPageResult> future = CompletableFuture.supplyAsync(
                    () -> parseVisualPage(pageIndex, renderedPageImage, options),
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

    private long resolveDocumentTimeoutMs(PipelineSource pipelineSource) {
        return pipelineSource == PipelineSource.KNOWLEDGE ? knowledgeDocumentTimeoutMs : pageDispatchTimeoutMs;
    }

    private boolean supportsBatchPdfDispatch() {
        return vdpEngine.supportedModes().contains(VdpMode.PDF_PAGE_BATCH);
    }

    private PageCacheContext buildPageCacheContext(Map<String, Object> rawOptions,
                                                   PipelineSource pipelineSource,
                                                   VdpOptions options) {
        return new PageCacheContext(
                pipelineSource,
                stringOption(rawOptions, "sessionId"),
                stringOption(rawOptions, "documentCacheKey"),
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
                vdpEngine.engineId(),
                vdpEngine.promptVersion(),
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
                vdpEngine.engineId(),
                vdpEngine.promptVersion(),
                buildPageCacheDigest(pageCacheContext, pageIndex),
                pageCacheContext.sessionId(),
                result
        );
    }

    private String buildPageCacheDigest(PageCacheContext pageCacheContext, int pageIndex) {
        return "pdf-page:"
                + pageCacheContext.documentCacheKey()
                + ':'
                + pageIndex
                + ':'
                + Math.round(renderDpi * 10.0f)
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
            results.putIfAbsent(dispatch.pageIndex(), failedResult);
            cachePageResult(pageCacheContext, dispatch.pageIndex(), failedResult);
            iterator.remove();
        }
    }

    private void markRemainingPagesAsFailed(Map<Integer, VdpPageResult> results,
                                            List<Integer> visualPageIndices,
                                            int startCursor,
                                            String reason) {
        for (int cursor = startCursor; cursor < visualPageIndices.size(); cursor++) {
            int pageIndex = visualPageIndices.get(cursor);
            results.putIfAbsent(pageIndex, failedPageResult(pageIndex, reason));
        }
    }

    private Map<Integer, VdpPageResult> normalizeBatchVisualResults(List<Integer> visualPageIndices,
                                                                    List<VdpPageResult> pageResults,
                                                                    PageCacheContext pageCacheContext) {
        Map<Integer, VdpPageResult> results = new HashMap<>();
        Set<Integer> visualPageIndexSet = new HashSet<>(visualPageIndices);
        if (pageResults != null) {
            for (VdpPageResult pageResult : pageResults) {
                if (pageResult == null || !visualPageIndexSet.contains(pageResult.pageIndex())) {
                    continue;
                }
                results.put(pageResult.pageIndex(), pageResult);
                cachePageResult(pageCacheContext, pageResult.pageIndex(), pageResult);
            }
        }
        for (Integer pageIndex : visualPageIndices) {
            VdpPageResult missingResult = failedPageResult(pageIndex, "Visual-track batch did not return a result");
            results.putIfAbsent(pageIndex, missingResult);
            cachePageResult(pageCacheContext, pageIndex, missingResult);
        }
        return results;
    }

    private Map<Integer, VdpPageResult> markAllBatchPagesFailed(List<Integer> visualPageIndices,
                                                                PageCacheContext pageCacheContext,
                                                                String reason) {
        Map<Integer, VdpPageResult> results = new HashMap<>();
        for (Integer pageIndex : visualPageIndices) {
            VdpPageResult failedResult = failedPageResult(pageIndex, reason);
            results.put(pageIndex, failedResult);
            cachePageResult(pageCacheContext, pageIndex, failedResult);
        }
        return results;
    }

    private ParseSegment buildNativePageSegment(int pageIndex,
                                                String pageText,
                                                PageStructuredText structuredPageText,
                                                PageFontProfile pageFontProfile,
                                                PageRoutingDecision routingDecision) {
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
                                            PageStructuredText structuredPageText,
                                            PageFontProfile pageFontProfile,
                                            PageRoutingDecision routingDecision,
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

    private void attachFontMetadata(Map<String, Object> metadata, PageFontProfile pageFontProfile) {
        if (metadata == null || pageFontProfile == null || pageFontProfile.sampleCount() <= 0) {
            return;
        }
        metadata.put("dominantFontSizePt", pageFontProfile.dominantFontSizePt());
        metadata.put("maxFontSizePt", pageFontProfile.maxFontSizePt());
        metadata.put("minFontSizePt", pageFontProfile.minFontSizePt());
        metadata.put("fontSampleCount", pageFontProfile.sampleCount());
        metadata.put("headingLikePage", isHeadingLikePage(pageFontProfile));
    }

    private void attachStructureMetadata(Map<String, Object> metadata, PageStructuredText structuredPageText) {
        if (metadata == null || structuredPageText == null) {
            return;
        }
        metadata.put("fontAwareStructureRestored", structuredPageText.restored());
        if (structuredPageText.headingCount() > 0) {
            metadata.put("restoredHeadingCount", structuredPageText.headingCount());
        }
    }

    private VdpPageResult parseVisualPage(int pageIndex, RenderedPageImage renderedPageImage, VdpOptions options) {
        if (!vdpEngine.supportedModes().contains(VdpMode.PAGE_IMAGE)) {
            renderedPageImage.clear();
            return failedPageResult(pageIndex, "VDP engine does not support page-image mode");
        }
        try {
            VdpPageResult result = vdpEngine.parsePage(
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

    private RenderedPageImage renderPageAsPng(PDFRenderer pdfRenderer, int pageIndex) throws IOException {
        BufferedImage image = pdfRenderer.renderImageWithDPI(pageIndex, renderDpi, ImageType.RGB);
        try (UnsafeByteArrayOutputStream outputStream = new UnsafeByteArrayOutputStream()) {
            // Avoid an extra byte[] copy here; VLM dispatch already needs one encoded PNG payload.
            if (!ImageIO.write(image, "png", outputStream)) {
                throw new IOException("No ImageIO writer found for PNG");
            }
            return outputStream.toRenderedPageImage();
        } finally {
            if (image != null) {
                image.flush();
            }
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

    private PageRoutingDecision decideRoute(String nativeText) {
        String trimmed = nativeText == null ? "" : nativeText.trim();
        String normalized = normalizeInlineWhitespace(trimmed);
        int alignedWhitespaceLines = countAlignedWhitespaceLines(trimmed);
        if (!StringUtils.hasText(trimmed)) {
            return new PageRoutingDecision(PageRoute.VISUAL_TRACK, "EMPTY_NATIVE_TEXT", alignedWhitespaceLines);
        }
        if (alignedWhitespaceLines >= whitespaceAlignedLineThreshold) {
            return new PageRoutingDecision(PageRoute.VISUAL_TRACK, "ALIGNED_WHITESPACE", alignedWhitespaceLines);
        }
        if (trimmed.length() <= shortTextFastTrackThreshold) {
            if (looksLikeStructuredShortText(normalized)) {
                return new PageRoutingDecision(PageRoute.VISUAL_TRACK, "SHORT_STRUCTURED_TEXT", alignedWhitespaceLines);
            }
            return new PageRoutingDecision(PageRoute.FAST_TRACK, "SHORT_TEXT_FAST_TRACK", alignedWhitespaceLines);
        }
        if (trimmed.length() < charDensityThreshold && !looksLikeNarrativeSnippet(normalized)) {
            return new PageRoutingDecision(PageRoute.VISUAL_TRACK, "LOW_CHAR_DENSITY", alignedWhitespaceLines);
        }
        if (trimmed.length() < charDensityThreshold) {
            return new PageRoutingDecision(PageRoute.FAST_TRACK, "SHORT_NARRATIVE_FAST_TRACK", alignedWhitespaceLines);
        }
        return new PageRoutingDecision(PageRoute.FAST_TRACK, "NATIVE_TEXT", alignedWhitespaceLines);
    }

    private String normalizeInlineWhitespace(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ");
    }

    private boolean looksLikeNarrativeSnippet(String normalizedText) {
        if (!StringUtils.hasText(normalizedText)) {
            return false;
        }
        return SENTENCE_PUNCTUATION_PATTERN.matcher(normalizedText).find();
    }

    private boolean looksLikeStructuredShortText(String normalizedText) {
        if (!StringUtils.hasText(normalizedText)) {
            return false;
        }
        if (normalizedText.contains("|") || normalizedText.contains("\t")) {
            return true;
        }
        if (!DIGIT_PATTERN.matcher(normalizedText).find()) {
            return false;
        }
        String[] tokens = normalizedText.split(" ");
        if (tokens.length < 3) {
            return false;
        }
        int compactTokenCount = 0;
        for (String token : tokens) {
            if (token.length() <= 4) {
                compactTokenCount++;
            }
        }
        return compactTokenCount >= Math.max(3, tokens.length - 1);
    }

    private PageStructuredText restoreStructuredNativeMarkdown(PageExtractionSnapshot pageSnapshot, String fallbackText) {
        if (pageSnapshot == null || !StringUtils.hasText(fallbackText) || !isHeadingLikePage(pageSnapshot.fontProfile())) {
            return PageStructuredText.plain(fallbackText);
        }
        List<PageLineSnapshot> lineSnapshots = pageSnapshot.lineSnapshots();
        if (lineSnapshots == null || lineSnapshots.isEmpty()) {
            return PageStructuredText.plain(fallbackText);
        }

        List<String> restoredLines = new ArrayList<>(lineSnapshots.size() * 2);
        int headingCount = 0;
        for (int lineIndex = 0; lineIndex < lineSnapshots.size(); lineIndex++) {
            PageLineSnapshot lineSnapshot = lineSnapshots.get(lineIndex);
            String lineText = TextCleanupUtil.cleanup(lineSnapshot.text(), true, true, false, 1);
            if (!StringUtils.hasText(lineText)) {
                if (!restoredLines.isEmpty() && !restoredLines.get(restoredLines.size() - 1).isEmpty()) {
                    restoredLines.add("");
                }
                continue;
            }
            Integer headingLevel = resolveHeadingLevel(lineIndex, lineText, lineSnapshot.fontProfile(), pageSnapshot.fontProfile());
            if (headingLevel != null) {
                if (!restoredLines.isEmpty() && !restoredLines.get(restoredLines.size() - 1).isEmpty()) {
                    restoredLines.add("");
                }
                restoredLines.add("#".repeat(headingLevel) + " " + lineText);
                restoredLines.add("");
                headingCount++;
                continue;
            }
            restoredLines.add(lineText);
        }

        String restoredText = TextCleanupUtil.cleanup(String.join("\n", restoredLines));
        if (headingCount <= 0 || !StringUtils.hasText(restoredText)) {
            return PageStructuredText.plain(fallbackText);
        }
        return new PageStructuredText(restoredText, true, headingCount);
    }

    private Integer resolveHeadingLevel(int lineIndex,
                                        String lineText,
                                        PageFontProfile lineFontProfile,
                                        PageFontProfile pageFontProfile) {
        if (lineFontProfile == null || pageFontProfile == null || lineFontProfile.sampleCount() <= 0) {
            return null;
        }
        if (!looksLikeHeadingLine(lineText)) {
            return null;
        }
        double lineMax = lineFontProfile.maxFontSizePt();
        double lineDominant = lineFontProfile.dominantFontSizePt();
        double pageDominant = pageFontProfile.dominantFontSizePt();
        double pageMax = pageFontProfile.maxFontSizePt();
        boolean visuallyProminent = lineMax >= pageDominant + HEADING_FONT_DELTA_PT
                || lineDominant >= pageDominant + HEADING_FONT_DELTA_PT;
        if (!visuallyProminent) {
            return null;
        }
        if (lineIndex == 0 && lineMax >= pageMax - 0.1d && lineMax >= pageDominant + 2.0d) {
            return 2;
        }
        if (lineMax >= pageDominant + 2.5d || lineDominant >= pageDominant + 2.0d) {
            return 2;
        }
        return 3;
    }

    private boolean looksLikeHeadingLine(String lineText) {
        String normalized = normalizeInlineWhitespace(lineText);
        if (!StringUtils.hasText(normalized) || normalized.startsWith("#")) {
            return false;
        }
        if (normalized.length() > 120) {
            return false;
        }
        if (normalized.endsWith(".") || normalized.endsWith("。")) {
            return false;
        }
        if (looksLikeNarrativeSnippet(normalized)) {
            return false;
        }
        String[] tokens = normalized.split(" ");
        return tokens.length <= 14;
    }

    private int countAlignedWhitespaceLines(String nativeText) {
        if (!StringUtils.hasText(nativeText)) {
            return 0;
        }
        int alignedLines = 0;
        for (String line : nativeText.split("\\R")) {
            if (!StringUtils.hasText(line) || line.length() < 16) {
                continue;
            }
            Matcher matcher = ALIGNED_WHITESPACE_PATTERN.matcher(line);
            int whitespaceRuns = 0;
            while (matcher.find()) {
                whitespaceRuns++;
                if (whitespaceRuns >= 2) {
                    alignedLines++;
                    break;
                }
            }
        }
        return alignedLines;
    }

    private List<PageExtractionSnapshot> extractPageSnapshots(PDDocument document) throws IOException {
        PageCollectingTextStripper stripper = new PageCollectingTextStripper();
        stripper.writeText(document, new PageCaptureWriter(stripper));
        return stripper.pageSnapshots();
    }

    private List<PageExtractionSnapshot> alignPageSnapshots(List<PageExtractionSnapshot> extractedPageSnapshots, int pageCount) {
        if (pageCount <= 0) {
            return List.of();
        }
        List<PageExtractionSnapshot> alignedPageSnapshots = new ArrayList<>(pageCount);
        if (extractedPageSnapshots != null && !extractedPageSnapshots.isEmpty()) {
            alignedPageSnapshots.addAll(extractedPageSnapshots.subList(0, Math.min(extractedPageSnapshots.size(), pageCount)));
        }
        while (alignedPageSnapshots.size() < pageCount) {
            alignedPageSnapshots.add(PageExtractionSnapshot.empty());
        }
        return alignedPageSnapshots;
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

    private enum PageRoute {
        FAST_TRACK,
        VISUAL_TRACK
    }

    private record PageRoutingDecision(PageRoute route, String reason, int alignedWhitespaceLines) {

        private boolean isVisualTrack() {
            return route == PageRoute.VISUAL_TRACK;
        }
    }

    private static final class PageCollectingTextStripper extends PDFTextStripper {

        private final List<PageExtractionSnapshot> pageSnapshots = new ArrayList<>();
        private StringBuilder currentPageText;
        private PageFontAccumulator currentPageFontAccumulator;

        private PageCollectingTextStripper() throws IOException {
            super();
        }

        @Override
        protected void startPage(PDPage page) throws IOException {
            currentPageText = new StringBuilder();
            currentPageFontAccumulator = new PageFontAccumulator();
            currentPageLines = new ArrayList<>();
            currentLineText = new StringBuilder();
            currentLineFontAccumulator = new PageFontAccumulator();
            super.startPage(page);
        }

        @Override
        protected void endPage(PDPage page) throws IOException {
            super.endPage(page);
            flushCurrentLine();
            pageSnapshots.add(new PageExtractionSnapshot(
                    currentPageText == null ? "" : currentPageText.toString(),
                    currentPageFontAccumulator == null ? PageFontProfile.empty() : currentPageFontAccumulator.toProfile(),
                    currentPageLines == null ? List.of() : List.copyOf(currentPageLines)
            ));
            currentPageText = null;
            currentPageFontAccumulator = null;
            currentPageLines = null;
            currentLineText = null;
            currentLineFontAccumulator = null;
        }

        @Override
        protected void processTextPosition(TextPosition text) {
            if (currentPageFontAccumulator != null && text != null) {
                currentPageFontAccumulator.record(text.getFontSizeInPt());
            }
            super.processTextPosition(text);
        }

        @Override
        protected void writeString(String text, List<TextPosition> textPositions) throws IOException {
            if (currentLineText != null && text != null) {
                currentLineText.append(text);
            }
            if (currentLineFontAccumulator != null && textPositions != null) {
                for (TextPosition textPosition : textPositions) {
                    if (textPosition != null) {
                        currentLineFontAccumulator.record(textPosition.getFontSizeInPt());
                    }
                }
            }
            super.writeString(text, textPositions);
        }

        @Override
        protected void writeLineSeparator() throws IOException {
            flushCurrentLine();
            super.writeLineSeparator();
        }

        private void append(char[] cbuf, int off, int len) {
            if (currentPageText != null && len > 0) {
                currentPageText.append(cbuf, off, len);
            }
        }

        private void flushCurrentLine() {
            if (currentPageLines == null || currentLineText == null || currentLineFontAccumulator == null) {
                return;
            }
            String lineText = TextCleanupUtil.cleanup(currentLineText.toString(), true, true, false, 1);
            if (StringUtils.hasText(lineText)) {
                currentPageLines.add(new PageLineSnapshot(lineText, currentLineFontAccumulator.toProfile()));
            }
            currentLineText = new StringBuilder();
            currentLineFontAccumulator = new PageFontAccumulator();
        }

        private List<PageExtractionSnapshot> pageSnapshots() {
            return List.copyOf(pageSnapshots);
        }

        private List<PageLineSnapshot> currentPageLines;
        private StringBuilder currentLineText;
        private PageFontAccumulator currentLineFontAccumulator;
    }

    private static final class PageCaptureWriter extends Writer {

        private final PageCollectingTextStripper stripper;

        private PageCaptureWriter(PageCollectingTextStripper stripper) {
            this.stripper = stripper;
        }

        @Override
        public void write(char[] cbuf, int off, int len) {
            stripper.append(cbuf, off, len);
        }

        @Override
        public void flush() {
            // no-op: page capture is in-memory only
        }

        @Override
        public void close() {
            // no-op: page capture is in-memory only
        }
    }

    private record VisualPageDispatch(int pageIndex, long submittedAtNanos, CompletableFuture<VdpPageResult> future) {
    }

    private record PageCacheContext(PipelineSource pipelineSource,
                                    String sessionId,
                                    String documentCacheKey,
                                    String languageHint,
                                    boolean recognizeFormulas) {
    }

    private record RenderedPageImage(byte[] bytes, int length) {

        private void clear() {
            if (bytes != null) {
                Arrays.fill(bytes, (byte) 0);
            }
        }
    }

    private static final class UnsafeByteArrayOutputStream extends ByteArrayOutputStream {

        private RenderedPageImage toRenderedPageImage() {
            return new RenderedPageImage(buf, count);
        }
    }

    private record PageExtractionSnapshot(String text,
                                          PageFontProfile fontProfile,
                                          List<PageLineSnapshot> lineSnapshots) {

        private static PageExtractionSnapshot empty() {
            return new PageExtractionSnapshot("", PageFontProfile.empty(), List.of());
        }
    }

    private record PageLineSnapshot(String text, PageFontProfile fontProfile) {
    }

    private record PageStructuredText(String text, boolean restored, int headingCount) {

        private static PageStructuredText plain(String text) {
            return new PageStructuredText(text == null ? "" : text, false, 0);
        }
    }

    private record PageFontProfile(double dominantFontSizePt,
                                   double maxFontSizePt,
                                   double minFontSizePt,
                                   int sampleCount) {

        private static PageFontProfile empty() {
            return new PageFontProfile(0d, 0d, 0d, 0);
        }
    }

    private static final class PageFontAccumulator {

        private final Map<Integer, Integer> fontSizeBuckets = new HashMap<>();
        private double maxFontSizePt;
        private double minFontSizePt = Double.MAX_VALUE;
        private int sampleCount;

        private void record(float fontSizePt) {
            if (fontSizePt <= 0f || Float.isNaN(fontSizePt) || Float.isInfinite(fontSizePt)) {
                return;
            }
            sampleCount++;
            double normalized = Math.round(fontSizePt * 10.0d) / 10.0d;
            maxFontSizePt = Math.max(maxFontSizePt, normalized);
            minFontSizePt = Math.min(minFontSizePt, normalized);
            int bucket = (int) Math.round(normalized * 10.0d);
            fontSizeBuckets.merge(bucket, 1, Integer::sum);
        }

        private PageFontProfile toProfile() {
            if (sampleCount <= 0 || fontSizeBuckets.isEmpty()) {
                return PageFontProfile.empty();
            }
            int dominantBucket = 0;
            int dominantCount = -1;
            for (Map.Entry<Integer, Integer> entry : fontSizeBuckets.entrySet()) {
                if (entry.getValue() > dominantCount) {
                    dominantBucket = entry.getKey();
                    dominantCount = entry.getValue();
                }
            }
            return new PageFontProfile(
                    dominantBucket / 10.0d,
                    maxFontSizePt,
                    minFontSizePt == Double.MAX_VALUE ? 0d : minFontSizePt,
                    sampleCount
            );
        }
    }

    private boolean isHeadingLikePage(PageFontProfile pageFontProfile) {
        return pageFontProfile != null
                && pageFontProfile.sampleCount() > 0
                && pageFontProfile.maxFontSizePt() >= 14.0d
                && pageFontProfile.maxFontSizePt() - pageFontProfile.dominantFontSizePt() >= 2.0d;
    }
}
