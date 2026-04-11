package com.yulong.chatagent.rag.parser;

import com.yulong.chatagent.exception.BizException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * PDF-first parser that preserves page boundaries and reports extraction quality.
 */
@Component
@Slf4j
public class PdfDocumentParser implements DocumentParser {

    private static final int MAX_FILE_BYTES = 30 * 1024 * 1024;

    private final MeterRegistry meterRegistry;
    private final PdfQualityRouter qualityRouter;
    private final PdfPageTextExtractor textExtractor;
    private final PdfVdpDispatcher vdpDispatcher;
    private final PdfSegmentAssembler segmentAssembler;

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
        this.meterRegistry = meterRegistry;
        PdfPageRenderer pageRenderer = new PdfPageRenderer(renderDpi);
        this.qualityRouter = new PdfQualityRouter(charDensityThreshold, shortTextFastTrackThreshold, whitespaceAlignedLineThreshold);
        this.textExtractor = new PdfPageTextExtractor();
        PdfVdpCache vdpCache = new PdfVdpCache(vdpPageCacheService, pageRenderer.renderDpi());
        PdfVdpBatchPlanner batchPlanner = new PdfVdpBatchPlanner(vdpCache);
        this.vdpDispatcher = new PdfVdpDispatcher(
                engineRouter,
                vdpCache,
                batchPlanner,
                vdpPageDispatchExecutor,
                vdpBatchExecutor,
                pageMaxInFlight,
                pageDispatchTimeoutMs,
                knowledgeDocumentTimeoutMs,
                meterRegistry,
                pageRenderer,
                qualityRouter
        );
        this.segmentAssembler = new PdfSegmentAssembler(textExtractor);
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
            Map<Integer, VdpPageResult> visualResults = vdpDispatcher.dispatchVisualTrackPages(
                    document,
                    routingDecisions,
                    options,
                    vdpOptions,
                    pipelineSource,
                    pdfStreamSupplier
            );
            PdfSegmentAssembler.SegmentAssemblyResult assembly = segmentAssembler.assemble(
                    cleanedPageTexts,
                    pageSnapshots,
                    routingDecisions,
                    visualResults
            );

            int totalChars = assembly.totalChars();
            int visualTrackPageCount = assembly.visualTrackPageCount();
            int visualSuccessPageCount = assembly.visualSuccessPageCount();
            int visualDegradedPageCount = assembly.visualDegradedPageCount();
            int visualFailedPageCount = assembly.visualFailedPageCount();
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
                    .segments(assembly.segments())
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

    private ParseResult oversizedFileResult() {
        return ParseResult.builder()
                .segments(List.of())
                .parserType(ParserType.PDFBOX.getType())
                .qualityLevel(QualityLevel.REJECTED)
                .warnings(List.of("File exceeds 30MB limit"))
                .build();
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
}
