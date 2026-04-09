package com.yulong.chatagent.rag.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PdfDocumentParserTest {

    @Test
    void shouldExtractPageSegmentsFromPdfInSingleParse() throws Exception {
        PdfDocumentParser parser = new PdfDocumentParser(new NoopVdpEngine(), Runnable::run, 80, 40, 2, 2, 5000L, 120000L, 144f);
        byte[] pdfBytes = createPdf(
                "Page one covers onboarding tasks, account setup, access control, policy acknowledgements, access review requirements, and employee orientation details across multiple teams.",
                "Page two explains annual leave policy, approval routing, handoff rules, team coverage expectations, exception handling, and manager responsibilities in additional detail."
        );

        ParseResult result = parser.parse(pdfBytes, "application/pdf", Map.of());

        assertThat(result.getParserType()).isEqualTo(ParserType.PDFBOX.getType());
        assertThat(result.getSegments()).hasSize(2);
        assertThat(result.getSegments().get(0).type()).isEqualTo(SegmentType.PAGE);
        assertThat(result.getSegments().get(0).metadata()).containsEntry("pageNumber", 1);
        assertThat(result.getSegments().get(0).text()).contains("onboarding tasks");
        assertThat(result.getSegments().get(1).metadata()).containsEntry("pageNumber", 2);
        assertThat(result.getSegments().get(1).text()).contains("annual leave policy");
        assertThat(result.getExtractionMode()).isEqualTo("NATIVE_TEXT");
        assertThat(result.getQualityLevel()).isIn(QualityLevel.HIGH, QualityLevel.MEDIUM);
    }

    @Test
    void shouldExtractPdfFromStreamSupplierWithoutReadingIntoSingleByteArray() throws Exception {
        PdfDocumentParser parser = new PdfDocumentParser(new NoopVdpEngine(), Runnable::run, 80, 40, 2, 2, 5000L, 120000L, 144f);
        byte[] pdfBytes = createPdf(
                "Page one covers onboarding tasks, account setup, access control, policy acknowledgements, access review requirements, and employee orientation details across multiple teams.",
                "Page two explains annual leave policy, approval routing, handoff rules, team coverage expectations, exception handling, and manager responsibilities in additional detail."
        );

        ParseResult result = parser.parse(
                () -> new ByteArrayInputStream(pdfBytes),
                "application/pdf",
                Map.of("fileSizeBytes", pdfBytes.length)
        );

        assertThat(result.getParserType()).isEqualTo(ParserType.PDFBOX.getType());
        assertThat(result.getSegments()).hasSize(2);
        assertThat(result.getSegments().get(1).text()).contains("annual leave policy");
    }

    @Test
    void shouldRejectOversizedPdfStreamWhenFileSizeIsUnknown() {
        PdfDocumentParser parser = new PdfDocumentParser(new NoopVdpEngine(), Runnable::run, 80, 40, 2, 2, 5000L, 120000L, 144f);

        ParseResult result = parser.parse(
                () -> new FixedLengthInputStream((31L * 1024L * 1024L) + 1L),
                "application/pdf",
                Map.of()
        );

        assertThat(result.getParserType()).isEqualTo(ParserType.PDFBOX.getType());
        assertThat(result.getQualityLevel()).isEqualTo(QualityLevel.REJECTED);
        assertThat(result.getWarnings()).contains("File exceeds 30MB limit");
        assertThat(result.getSegments()).isEmpty();
    }

    @Test
    void shouldRouteSparsePageThroughVisualTrackAndPreserveMarkdownOutput() throws Exception {
        StubVdpEngine vdpEngine = new StubVdpEngine(List.of(
                new VdpPageResult(
                        0,
                        "| Item | Value |\n|---|---|\n| A | 1 |",
                        VdpPageStatus.SUCCESS,
                        Map.of("contentOrigin", "VDP_TRANSCRIBED", "visualType", "TABLE")
                )
        ));
        PdfDocumentParser parser = new PdfDocumentParser(vdpEngine, Runnable::run, 120, 20, 2, 2, 5000L, 120000L, 144f);
        byte[] pdfBytes = createPdf(
                "A1 B2 C3 D4 E5 F6 G7 H8 I9 J10 K11 L12 M13 N14 O15",
                "Page two explains annual leave policy, approval routing, handoff rules, team coverage expectations, exception handling, and manager responsibilities in additional detail."
        );

        ParseResult result = parser.parse(pdfBytes, "application/pdf", Map.of("languageHint", "zh"));

        assertThat(result.getExtractionMode()).isEqualTo("PDF_VISUAL_ROUTED");
        assertThat(result.getSegments()).hasSize(2);
        assertThat(result.getSegments().get(0).text()).contains("| Item | Value |");
        assertThat(result.getSegments().get(0).metadata())
                .containsEntry("pageRoute", "VISUAL_TRACK")
                .containsEntry("visualType", "TABLE")
                .containsEntry("contentOrigin", "VDP_TRANSCRIBED")
                .containsEntry("vdpStatus", "SUCCESS");
        assertThat(result.getSegments().get(1).metadata())
                .containsEntry("pageRoute", "FAST_TRACK")
                .containsEntry("contentOrigin", "NATIVE");
        assertThat(vdpEngine.getInvocationCount()).isEqualTo(1);
        assertThat(vdpEngine.getLastImageByteCount()).isPositive();
        assertThat(vdpEngine.getLastOptions().languageHint()).isEqualTo("zh");
        assertThat(vdpEngine.getLastOptions().extra()).containsOnlyKeys("pipelineSource");
    }

    @Test
    void shouldPreferBatchPdfDispatchWhenEngineSupportsPageBatchMode() throws Exception {
        BatchStubVdpEngine vdpEngine = new BatchStubVdpEngine(List.of(
                new VdpPageResult(
                        0,
                        "| Batch | Result |\n|---|---|\n| A | 1 |",
                        VdpPageStatus.SUCCESS,
                        Map.of("contentOrigin", "VDP_TRANSCRIBED", "visualType", "TABLE")
                )
        ));
        PdfDocumentParser parser = new PdfDocumentParser(vdpEngine, Runnable::run, 120, 20, 2, 2, 5000L, 120000L, 144f);
        byte[] pdfBytes = createPdf("A1 B2 C3 D4 E5 F6 G7 H8 I9 J10 K11 L12 M13 N14 O15");

        ParseResult result = parser.parse(pdfBytes, "application/pdf", Map.of("pipelineSource", PipelineSource.KNOWLEDGE));

        assertThat(result.getExtractionMode()).isEqualTo("PDF_VISUAL_ROUTED");
        assertThat(result.getSegments()).hasSize(1);
        assertThat(result.getSegments().get(0).text()).contains("| Batch | Result |");
        assertThat(vdpEngine.getBatchInvocationCount()).isEqualTo(1);
        assertThat(vdpEngine.getPageInvocationCount()).isZero();
    }

    @Test
    void shouldUseAsyncBatchDispatchWithoutCallingSynchronousParsePages() throws Exception {
        VdpEngine asyncOnlyBatchEngine = new VdpEngine() {
            @Override
            public List<VdpPageResult> parsePages(Supplier<InputStream> pdfStream, List<Integer> pageIndices, VdpOptions options) {
                throw new AssertionError("parsePages should not be called");
            }

            @Override
            public CompletableFuture<List<VdpPageResult>> parsePagesAsync(Supplier<InputStream> pdfStream,
                                                                          List<Integer> pageIndices,
                                                                          VdpOptions options,
                                                                          java.util.concurrent.Executor executor) {
                return CompletableFuture.completedFuture(List.of(
                        new VdpPageResult(0, "| Async | Batch |", VdpPageStatus.SUCCESS, Map.of("visualType", "TABLE"))
                ));
            }

            @Override
            public EnumSet<VdpMode> supportedModes() {
                return EnumSet.of(VdpMode.PDF_PAGE_BATCH);
            }

            @Override
            public String engineId() {
                return "async-batch";
            }
        };
        PdfDocumentParser parser = new PdfDocumentParser(asyncOnlyBatchEngine, Runnable::run, 120, 20, 2, 2, 5000L, 120000L, 144f);
        byte[] pdfBytes = createPdf("A1 B2 C3 D4");

        ParseResult result = parser.parse(pdfBytes, "application/pdf", Map.of("pipelineSource", PipelineSource.KNOWLEDGE));

        assertThat(result.getExtractionMode()).isEqualTo("PDF_VISUAL_ROUTED");
        assertThat(result.getSegments()).hasSize(1);
        assertThat(result.getSegments().get(0).text()).contains("| Async | Batch |");
    }

    @Test
    void shouldIsolateBatchDispatchFromSaturatedPageExecutor() throws Exception {
        BatchStubVdpEngine vdpEngine = new BatchStubVdpEngine(List.of(
                new VdpPageResult(
                        0,
                        "| Batch | Isolated |\n|---|---|\n| A | 1 |",
                        VdpPageStatus.SUCCESS,
                        Map.of("contentOrigin", "VDP_TRANSCRIBED", "visualType", "TABLE")
                )
        ));
        PdfDocumentParser parser = new PdfDocumentParser(
                vdpEngine,
                null,
                command -> {
                    throw new RejectedExecutionException("page executor saturated");
                },
                Runnable::run,
                120,
                20,
                2,
                2,
                5000L,
                120000L,
                144f
        );
        byte[] pdfBytes = createPdf("A1 B2 C3 D4 E5 F6 G7 H8 I9 J10 K11 L12 M13 N14 O15");

        ParseResult result = parser.parse(pdfBytes, "application/pdf", Map.of("pipelineSource", PipelineSource.KNOWLEDGE));

        assertThat(result.getExtractionMode()).isEqualTo("PDF_VISUAL_ROUTED");
        assertThat(result.getSegments()).hasSize(1);
        assertThat(result.getSegments().get(0).text()).contains("| Batch | Isolated |");
        assertThat(vdpEngine.getBatchInvocationCount()).isEqualTo(1);
        assertThat(vdpEngine.getPageInvocationCount()).isZero();
    }

    @Test
    void shouldQueueSecondBatchDispatchInsteadOfRejectingWhenBatchExecutorHasCapacity() throws Exception {
        CountDownLatch firstBatchStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstBatch = new CountDownLatch(1);
        ThreadPoolExecutor batchExecutor = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1)
        );
        ExecutorService callerExecutor = Executors.newFixedThreadPool(2);
        try {
            VdpEngine queuedBatchEngine = new VdpEngine() {
                private final AtomicInteger batchCalls = new AtomicInteger();

                @Override
                public List<VdpPageResult> parsePages(Supplier<InputStream> pdfStream, List<Integer> pageIndices, VdpOptions options) {
                    int call = batchCalls.incrementAndGet();
                    if (call == 1) {
                        firstBatchStarted.countDown();
                        try {
                            releaseFirstBatch.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(e);
                        }
                    }
                    return List.of(new VdpPageResult(
                            0,
                            "| queued | batch |\n|---|---|\n| A | 1 |",
                            VdpPageStatus.SUCCESS,
                            Map.of("visualType", "TABLE")
                    ));
                }

                @Override
                public EnumSet<VdpMode> supportedModes() {
                    return EnumSet.of(VdpMode.PDF_PAGE_BATCH);
                }

                @Override
                public String engineId() {
                    return "queued-batch";
                }
            };
            PdfDocumentParser parser = new PdfDocumentParser(
                    queuedBatchEngine,
                    null,
                    Runnable::run,
                    batchExecutor,
                    120,
                    20,
                    2,
                    2,
                    5000L,
                    120000L,
                    144f
            );
            byte[] pdfBytes = createPdf("A1 B2 C3 D4");

            CompletableFuture<ParseResult> first = CompletableFuture.supplyAsync(
                    () -> parser.parse(pdfBytes, "application/pdf", Map.of("pipelineSource", PipelineSource.KNOWLEDGE)),
                    callerExecutor
            );
            assertThat(firstBatchStarted.await(5, TimeUnit.SECONDS)).isTrue();

            CompletableFuture<ParseResult> second = CompletableFuture.supplyAsync(
                    () -> parser.parse(pdfBytes, "application/pdf", Map.of("pipelineSource", PipelineSource.KNOWLEDGE)),
                    callerExecutor
            );

            long waitDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (batchExecutor.getQueue().size() < 1 && System.nanoTime() < waitDeadline) {
                Thread.sleep(10L);
            }
            assertThat(batchExecutor.getQueue().size()).isEqualTo(1);

            releaseFirstBatch.countDown();

            ParseResult firstResult = first.get(5, TimeUnit.SECONDS);
            ParseResult secondResult = second.get(5, TimeUnit.SECONDS);

            assertThat(firstResult.getExtractionMode()).isEqualTo("PDF_VISUAL_ROUTED");
            assertThat(secondResult.getExtractionMode()).isEqualTo("PDF_VISUAL_ROUTED");
            assertThat(firstResult.getSegments().get(0).text()).contains("| queued | batch |");
            assertThat(secondResult.getSegments().get(0).text()).contains("| queued | batch |");
        } finally {
            releaseFirstBatch.countDown();
            batchExecutor.shutdownNow();
            callerExecutor.shutdownNow();
            batchExecutor.awaitTermination(5, TimeUnit.SECONDS);
            callerExecutor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void shouldFallbackToNativeTextWhenVisualTrackReturnsEmptyMarkdown() throws Exception {
        StubVdpEngine vdpEngine = new StubVdpEngine(List.of(
                new VdpPageResult(
                        0,
                        "",
                        VdpPageStatus.DEGRADED,
                        Map.of(
                                "contentOrigin", "VDP_TRANSCRIBED",
                                "visualType", "TABLE",
                                "degraded", true,
                                "interpretiveNote", "[图像解析失败]: timeout"
                        )
                )
        ));
        PdfDocumentParser parser = new PdfDocumentParser(vdpEngine, Runnable::run, 500, 20, 2, 2, 5000L, 120000L, 144f);
        String nativeText = "Quarterly sales summary with region totals approval notes ownership details and reconciliation context for finance review";
        byte[] pdfBytes = createPdf(nativeText);

        ParseResult result = parser.parse(pdfBytes, "application/pdf", Map.of());

        assertThat(result.getExtractionMode()).isEqualTo("PDF_VISUAL_ROUTED");
        assertThat(result.getSegments()).hasSize(1);
        assertThat(result.getSegments().get(0).text()).contains("Quarterly sales summary");
        assertThat(result.getSegments().get(0).metadata())
                .containsEntry("pageRoute", "VISUAL_TRACK")
                .containsEntry("contentOrigin", "NATIVE")
                .containsEntry("visualFallback", "NATIVE_TEXT")
                .containsEntry("vdpStatus", "DEGRADED")
                .containsEntry("degraded", true)
                .containsEntry("interpretiveNote", "[图像解析失败]: timeout");
    }

    @Test
    void shouldKeepShortNarrativePageOnFastTrackInsteadOfSendingToVlm() throws Exception {
        StubVdpEngine vdpEngine = new StubVdpEngine(List.of(
                new VdpPageResult(0, "should not be used", VdpPageStatus.SUCCESS, Map.of())
        ));
        PdfDocumentParser parser = new PdfDocumentParser(vdpEngine, Runnable::run, 500, 20, 2, 2, 5000L, 120000L, 144f);
        byte[] pdfBytes = createPdf("This page intentionally stays brief.");

        ParseResult result = parser.parse(pdfBytes, "application/pdf", Map.of());

        assertThat(result.getExtractionMode()).isEqualTo("NATIVE_TEXT");
        assertThat(result.getSegments()).hasSize(1);
        assertThat(result.getSegments().get(0).text()).contains("intentionally stays brief");
        assertThat(result.getSegments().get(0).metadata())
                .containsEntry("pageRoute", "FAST_TRACK")
                .containsEntry("pageRouteReason", "SHORT_NARRATIVE_FAST_TRACK");
        assertThat(vdpEngine.getInvocationCount()).isZero();
    }

    @Test
    void shouldAttachFontMetadataToNativePageSegments() throws Exception {
        PdfDocumentParser parser = new PdfDocumentParser(new NoopVdpEngine(), Runnable::run, 80, 40, 2, 2, 5000L, 120000L, 144f);
        byte[] pdfBytes = createPdf("This page uses the standard body font metadata path.");

        ParseResult result = parser.parse(pdfBytes, "application/pdf", Map.of());

        assertThat(result.getSegments()).hasSize(1);
        assertThat(result.getSegments().get(0).metadata())
                .containsEntry("pageRoute", "FAST_TRACK")
                .containsKey("dominantFontSizePt")
                .containsKey("maxFontSizePt")
                .containsKey("minFontSizePt")
                .containsKey("fontSampleCount")
                .containsKey("headingLikePage");
        assertThat(((Number) result.getSegments().get(0).metadata().get("dominantFontSizePt")).doubleValue()).isPositive();
        assertThat(((Number) result.getSegments().get(0).metadata().get("fontSampleCount")).intValue()).isPositive();
    }

    @Test
    void shouldRestoreMarkdownHeadingFromLargerFontLine() throws Exception {
        PdfDocumentParser parser = new PdfDocumentParser(new NoopVdpEngine(), Runnable::run, 80, 40, 2, 2, 5000L, 120000L, 144f);
        byte[] pdfBytes = createPdfWithHeading(
                "Leave Policy Overview",
                "This section explains carry-over rules, manager approval, and planning expectations for the next quarter."
        );

        ParseResult result = parser.parse(pdfBytes, "application/pdf", Map.of());

        assertThat(result.getSegments()).hasSize(1);
        assertThat(result.getSegments().get(0).text()).startsWith("## Leave Policy Overview");
        assertThat(result.getSegments().get(0).text()).contains("carry-over rules");
        assertThat(result.getSegments().get(0).metadata())
                .containsEntry("fontAwareStructureRestored", true)
                .containsEntry("restoredHeadingCount", 1);
    }

    @Test
    void shouldReuseCachedPdfPageResultBeforeRenderingAgain() throws Exception {
        StubVdpEngine vdpEngine = new StubVdpEngine(List.of(
                new VdpPageResult(0, "| Cached | Page |\n|---|---|\n| A | 1 |", VdpPageStatus.SUCCESS, Map.of("visualType", "TABLE"))
        ));
        @SuppressWarnings("unchecked")
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        VdpPageCacheService cacheService = new VdpPageCacheService(
                new ObjectMapper(),
                provider,
                new VdpCacheProperties()
        );
        PdfDocumentParser parser = new PdfDocumentParser(
                vdpEngine,
                cacheService,
                Runnable::run,
                Runnable::run,
                150,
                80,
                2,
                2,
                5000L,
                120000L,
                144f
        );
        byte[] pdfBytes = createPdf("A1 B2 C3");
        Map<String, Object> options = Map.of(
                "pipelineSource", PipelineSource.SESSION,
                "sessionId", "session-1",
                "documentCacheKey", "session-file:file-1"
        );

        ParseResult first = parser.parse(pdfBytes, "application/pdf", options);
        ParseResult second = parser.parse(pdfBytes, "application/pdf", options);

        assertThat(first.getSegments().get(0).text()).contains("| Cached | Page |");
        assertThat(second.getSegments().get(0).text()).contains("| Cached | Page |");
        assertThat(vdpEngine.getInvocationCount()).isEqualTo(1);
    }

    @Test
    void shouldRouteShortStructuredSnippetToVisualTrack() throws Exception {
        StubVdpEngine vdpEngine = new StubVdpEngine(List.of(
                new VdpPageResult(0, "| A | B |\n|---|---|\n| 1 | 2 |", VdpPageStatus.SUCCESS, Map.of("visualType", "TABLE"))
        ));
        PdfDocumentParser parser = new PdfDocumentParser(vdpEngine, Runnable::run, 150, 80, 2, 2, 5000L, 120000L, 144f);
        byte[] pdfBytes = createPdf("A1 B2 C3");

        ParseResult result = parser.parse(pdfBytes, "application/pdf", Map.of());

        assertThat(result.getExtractionMode()).isEqualTo("PDF_VISUAL_ROUTED");
        assertThat(result.getSegments()).hasSize(1);
        assertThat(result.getSegments().get(0).text()).contains("| A | B |");
        assertThat(result.getSegments().get(0).metadata())
                .containsEntry("pageRoute", "VISUAL_TRACK")
                .containsEntry("pageRouteReason", "SHORT_STRUCTURED_TEXT");
        assertThat(vdpEngine.getInvocationCount()).isEqualTo(1);
    }

    @Test
    void shouldUseResolvedBatchEngineCacheNamespaceBeforeDispatch() throws Exception {
        StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        AtomicReference<String> stored = new AtomicReference<>();
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenAnswer(invocation -> stored.get());
        @SuppressWarnings("unchecked")
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(stringRedisTemplate);
        VdpPageCacheService cacheService = new VdpPageCacheService(
                new ObjectMapper(),
                provider,
                new VdpCacheProperties()
        );
        BatchStubVdpEngine batchEngine = new BatchStubVdpEngine(List.of(
                new VdpPageResult(0, "| should not run |", VdpPageStatus.SUCCESS, Map.of("visualType", "TABLE"))
        ), "mineru", "batch-v2");
        StubVdpEngine pageEngine = new StubVdpEngine(List.of(
                new VdpPageResult(0, "| should not run |", VdpPageStatus.SUCCESS, Map.of("visualType", "TABLE"))
        ), 0L, "vlm", "page-v1");
        NoopVdpEngine noopEngine = new NoopVdpEngine();
        VdpEngineRoutingProperties properties = new VdpEngineRoutingProperties();
        properties.setPreferredBatchEngine("mineru");
        properties.setPreferredPageImageEngine("vlm");
        VdpEngineRouter router = new VdpEngineRouter(List.of(pageEngine, batchEngine, noopEngine), properties, noopEngine);
        PdfDocumentParser parser = new PdfDocumentParser(
                router,
                cacheService,
                command -> {
                    throw new RejectedExecutionException("page executor should stay idle");
                },
                Runnable::run,
                150,
                80,
                2,
                2,
                5000L,
                120000L,
                144f
        );
        String documentCacheKey = "knowledge-content:doc-1";
        stored.set(new ObjectMapper().writeValueAsString(
                new VdpPageResult(0, "| Cached | Batch |\n|---|---|\n| A | 1 |", VdpPageStatus.SUCCESS, Map.of("visualType", "TABLE"))
        ));
        byte[] pdfBytes = createPdf("A1 B2 C3");

        ParseResult result = parser.parse(
                pdfBytes,
                "application/pdf",
                Map.of(
                        "pipelineSource", PipelineSource.KNOWLEDGE,
                        "documentCacheKey", documentCacheKey
                )
        );

        assertThat(result.getSegments()).hasSize(1);
        assertThat(result.getSegments().get(0).text()).contains("| Cached | Batch |");
        assertThat(batchEngine.getBatchInvocationCount()).isZero();
        assertThat(pageEngine.getInvocationCount()).isZero();
    }

    @Test
    void shouldFallbackWhenVisualDispatchTimesOut() throws Exception {
        ExecutorService executorService = Executors.newFixedThreadPool(1);
        try {
            StubVdpEngine vdpEngine = new StubVdpEngine(List.of(
                    new VdpPageResult(0, "| slow | result |", VdpPageStatus.SUCCESS, Map.of("visualType", "TABLE"))
            ), 1500L);
            PdfDocumentParser parser = new PdfDocumentParser(vdpEngine, executorService, 500, 20, 2, 1, 1000L, 120000L, 144f);
            String nativeText = "Quarterly sales summary with region totals approval notes ownership details and reconciliation context for finance review";
            byte[] pdfBytes = createPdf(nativeText);

            ParseResult result = parser.parse(
                    pdfBytes,
                    "application/pdf",
                    Map.of("pipelineSource", PipelineSource.SESSION)
            );

            assertThat(result.getExtractionMode()).isEqualTo("PDF_VISUAL_ROUTED");
            assertThat(result.getSegments()).hasSize(1);
            assertThat(result.getSegments().get(0).text()).contains("Quarterly sales summary");
            assertThat(result.getSegments().get(0).metadata())
                    .containsEntry("pageRoute", "VISUAL_TRACK")
                    .containsEntry("vdpStatus", "FAILED")
                    .containsEntry("visualFallback", "NATIVE_TEXT");
            assertThat(result.getSegments().get(0).metadata().get("interpretiveNote").toString())
                    .containsAnyOf("timed out", "budget exhausted");
        } finally {
            executorService.shutdownNow();
            executorService.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void shouldRecordTimeoutAndDocumentParseMetrics() throws Exception {
        ExecutorService executorService = Executors.newFixedThreadPool(1);
        try {
            SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
            StubVdpEngine vdpEngine = new StubVdpEngine(List.of(
                    new VdpPageResult(0, "| slow | result |", VdpPageStatus.SUCCESS, Map.of("visualType", "TABLE"))
            ), 1500L);
            PdfDocumentParser parser = new PdfDocumentParser(
                    vdpEngine,
                    null,
                    executorService,
                    executorService,
                    500,
                    20,
                    2,
                    1,
                    1000L,
                    120000L,
                    144f,
                    meterRegistry
            );
            byte[] pdfBytes = createPdf("Quarterly sales summary with region totals approval notes ownership details and reconciliation context for finance review");

            parser.parse(pdfBytes, "application/pdf", Map.of("pipelineSource", PipelineSource.SESSION));

            assertThat(meterRegistry.get("vdp.page.timeout")
                    .tags("engineId", "stub-page")
                    .counter()
                    .count()).isEqualTo(1.0d);
            assertThat(meterRegistry.get("vdp.document.parse.latency")
                    .tags("pipelineSource", "SESSION", "extractionMode", "PDF_VISUAL_ROUTED")
                    .timer()
                    .count()).isEqualTo(1L);
        } finally {
            executorService.shutdownNow();
            executorService.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void shouldAllowKnowledgePipelineToUseLongerVisualPageBudget() throws Exception {
        ExecutorService executorService = Executors.newFixedThreadPool(1);
        try {
            StubVdpEngine vdpEngine = new StubVdpEngine(List.of(
                    new VdpPageResult(0, "| knowledge | result |", VdpPageStatus.SUCCESS, Map.of("visualType", "TABLE"))
            ), 1500L);
            PdfDocumentParser parser = new PdfDocumentParser(vdpEngine, executorService, 500, 20, 2, 1, 1000L, 3000L, 144f);
            String nativeText = "Quarterly sales summary with region totals approval notes ownership details and reconciliation context for finance review";
            byte[] pdfBytes = createPdf(nativeText);

            ParseResult result = parser.parse(
                    pdfBytes,
                    "application/pdf",
                    Map.of("pipelineSource", PipelineSource.KNOWLEDGE)
            );

            assertThat(result.getExtractionMode()).isEqualTo("PDF_VISUAL_ROUTED");
            assertThat(result.getSegments()).hasSize(1);
            assertThat(result.getSegments().get(0).text()).contains("| knowledge | result |");
            assertThat(result.getSegments().get(0).metadata())
                    .containsEntry("pageRoute", "VISUAL_TRACK")
                    .containsEntry("vdpStatus", "SUCCESS");
        } finally {
            executorService.shutdownNow();
            executorService.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void shouldHonorBatchEngineSuggestedTimeoutForKnowledgePipeline() throws Exception {
        BatchStubVdpEngine batchEngine = new BatchStubVdpEngine(
                List.of(new VdpPageResult(0, "| delayed | batch |", VdpPageStatus.SUCCESS, Map.of("visualType", "TABLE"))),
                "mineru",
                "batch-v1",
                1500L,
                3000L
        );
        NoopVdpEngine noopEngine = new NoopVdpEngine();
        VdpEngineRoutingProperties properties = new VdpEngineRoutingProperties();
        properties.setPreferredBatchEngine("mineru");
        VdpEngineRouter router = new VdpEngineRouter(List.of(batchEngine, noopEngine), properties, noopEngine);
        PdfDocumentParser parser = new PdfDocumentParser(
                router,
                null,
                Runnable::run,
                Runnable::run,
                150,
                80,
                2,
                2,
                1000L,
                1000L,
                144f
        );
        byte[] pdfBytes = createPdf("A1 B2 C3");

        ParseResult result = parser.parse(
                pdfBytes,
                "application/pdf",
                Map.of("pipelineSource", PipelineSource.KNOWLEDGE)
        );

        assertThat(result.getExtractionMode()).isEqualTo("PDF_VISUAL_ROUTED");
        assertThat(result.getSegments()).hasSize(1);
        assertThat(result.getSegments().get(0).text()).contains("| delayed | batch |");
        assertThat(batchEngine.getBatchInvocationCount()).isEqualTo(1);
    }

    @Test
    void shouldMarkPdfAsOcrRequiredWhenVisualTrackFailsWithoutNativeFallback() throws Exception {
        StubVdpEngine vdpEngine = new StubVdpEngine(List.of(
                new VdpPageResult(
                        0,
                        "",
                        VdpPageStatus.DEGRADED,
                        Map.of(
                                "contentOrigin", "VDP_TRANSCRIBED",
                                "visualType", "IMAGE",
                                "degraded", true,
                                "interpretiveNote", "[图像解析失败]: no-content"
                        )
                )
        ));
        PdfDocumentParser parser = new PdfDocumentParser(vdpEngine, Runnable::run, 150, 80, 2, 2, 5000L, 120000L, 144f);
        byte[] pdfBytes = createPdf("");

        ParseResult result = parser.parse(pdfBytes, "application/pdf", Map.of());

        assertThat(result.getExtractionMode()).isEqualTo("OCR_REQUIRED");
        assertThat(result.getQualityLevel()).isEqualTo(QualityLevel.LOW);
        assertThat(result.getSegments()).hasSize(1);
        assertThat(result.getSegments().get(0).text()).isEmpty();
        assertThat(result.getSegments().get(0).metadata())
                .containsEntry("pageRoute", "VISUAL_TRACK")
                .containsEntry("visualFallback", "EMPTY_PAGE")
                .containsEntry("vdpStatus", "DEGRADED")
                .containsEntry("interpretiveNote", "[图像解析失败]: no-content");
    }

    @Test
    void shouldRecordOcrRequiredMetric() throws Exception {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        StubVdpEngine vdpEngine = new StubVdpEngine(List.of(
                new VdpPageResult(0, "", VdpPageStatus.DEGRADED, Map.of("visualType", "IMAGE", "interpretiveNote", "empty"))
        ));
        PdfDocumentParser parser = new PdfDocumentParser(
                vdpEngine,
                null,
                Runnable::run,
                Runnable::run,
                150,
                80,
                2,
                2,
                5000L,
                120000L,
                144f,
                meterRegistry
        );

        parser.parse(createPdf(""), "application/pdf", Map.of());

        assertThat(meterRegistry.get("vdp.document.ocr_required")
                .tags("pipelineSource", "KNOWLEDGE")
                .counter()
                .count()).isEqualTo(1.0d);
        assertThat(meterRegistry.get("vdp.page.degraded")
                .tags("engineId", "stub-page")
                .counter()
                .count()).isEqualTo(1.0d);
    }

    @Test
    void shouldRecordVisualPageSuccessMetric() throws Exception {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        StubVdpEngine vdpEngine = new StubVdpEngine(List.of(
                new VdpPageResult(0, "| Item | Value |\n|---|---|\n| A | 1 |", VdpPageStatus.SUCCESS, Map.of("visualType", "TABLE"))
        ));
        PdfDocumentParser parser = new PdfDocumentParser(
                vdpEngine,
                null,
                Runnable::run,
                Runnable::run,
                150,
                80,
                2,
                2,
                5000L,
                120000L,
                144f,
                meterRegistry
        );

        parser.parse(createPdf("A1 B2 C3"), "application/pdf", Map.of());

        assertThat(meterRegistry.get("vdp.page.success")
                .tags("engineId", "stub-page")
                .counter()
                .count()).isEqualTo(1.0d);
    }

    private byte[] createPdf(String... pageTexts) throws Exception {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            for (String pageText : pageTexts) {
                PDPage page = new PDPage();
                document.addPage(page);
                if (pageText != null && !pageText.isEmpty()) {
                    try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                        contentStream.beginText();
                        contentStream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                        contentStream.newLineAtOffset(72, 720);
                        contentStream.showText(pageText);
                        contentStream.endText();
                    }
                }
            }
            document.save(outputStream);
            return outputStream.toByteArray();
        }
    }

    private byte[] createPdfWithHeading(String heading, String body) throws Exception {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                contentStream.beginText();
                contentStream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 18);
                contentStream.newLineAtOffset(72, 720);
                contentStream.showText(heading);
                contentStream.newLineAtOffset(0, -28);
                contentStream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                contentStream.showText(body);
                contentStream.endText();
            }
            document.save(outputStream);
            return outputStream.toByteArray();
        }
    }

    private static final class StubVdpEngine implements VdpEngine {

        private final List<VdpPageResult> results;
        private final long delayMs;
        private final String engineId;
        private final String promptVersion;
        private final AtomicInteger invocationCount = new AtomicInteger();
        private volatile int lastImageByteCount;
        private volatile VdpOptions lastOptions;

        private StubVdpEngine(List<VdpPageResult> results) {
            this(results, 0L);
        }

        private StubVdpEngine(List<VdpPageResult> results, long delayMs) {
            this(results, delayMs, "stub-page", "v1");
        }

        private StubVdpEngine(List<VdpPageResult> results, long delayMs, String engineId, String promptVersion) {
            this.results = results;
            this.delayMs = delayMs;
            this.engineId = engineId;
            this.promptVersion = promptVersion;
        }

        @Override
        public VdpPageResult parsePage(Supplier<InputStream> imageStream, String imageFormat, VdpOptions options) {
            try (InputStream stream = imageStream.get()) {
                lastImageByteCount = stream.readAllBytes().length;
                if (delayMs > 0) {
                    Thread.sleep(delayMs);
                }
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            lastOptions = options;
            int callIndex = invocationCount.getAndIncrement();
            VdpPageResult result = results.get(Math.min(callIndex, results.size() - 1));
            return result;
        }

        @Override
        public EnumSet<VdpMode> supportedModes() {
            return EnumSet.of(VdpMode.PAGE_IMAGE);
        }

        @Override
        public String engineId() {
            return engineId;
        }

        @Override
        public String promptVersion() {
            return promptVersion;
        }

        private int getInvocationCount() {
            return invocationCount.get();
        }

        private int getLastImageByteCount() {
            return lastImageByteCount;
        }

        private VdpOptions getLastOptions() {
            return lastOptions;
        }
    }

    private static final class BatchStubVdpEngine implements VdpEngine {

        private final List<VdpPageResult> results;
        private final String engineId;
        private final String promptVersion;
        private final long delayMs;
        private final long suggestedTimeoutMs;
        private final AtomicInteger batchInvocationCount = new AtomicInteger();
        private final AtomicInteger pageInvocationCount = new AtomicInteger();

        private BatchStubVdpEngine(List<VdpPageResult> results) {
            this(results, "stub-batch", "v1");
        }

        private BatchStubVdpEngine(List<VdpPageResult> results, String engineId, String promptVersion) {
            this(results, engineId, promptVersion, 0L, 0L);
        }

        private BatchStubVdpEngine(List<VdpPageResult> results,
                                   String engineId,
                                   String promptVersion,
                                   long delayMs,
                                   long suggestedTimeoutMs) {
            this.results = results;
            this.engineId = engineId;
            this.promptVersion = promptVersion;
            this.delayMs = delayMs;
            this.suggestedTimeoutMs = suggestedTimeoutMs;
        }

        @Override
        public List<VdpPageResult> parsePages(Supplier<InputStream> pdfStream, List<Integer> pageIndices, VdpOptions options) {
            try (InputStream ignored = pdfStream.get()) {
                // validate the supplier contract returns a readable stream
                if (delayMs > 0) {
                    Thread.sleep(delayMs);
                }
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            batchInvocationCount.incrementAndGet();
            return results;
        }

        @Override
        public VdpPageResult parsePage(Supplier<InputStream> imageStream, String imageFormat, VdpOptions options) {
            pageInvocationCount.incrementAndGet();
            return new VdpPageResult(0, "", VdpPageStatus.FAILED, Map.of());
        }

        @Override
        public EnumSet<VdpMode> supportedModes() {
            return EnumSet.of(VdpMode.PDF_PAGE_BATCH);
        }

        @Override
        public String engineId() {
            return engineId;
        }

        @Override
        public String promptVersion() {
            return promptVersion;
        }

        @Override
        public long suggestedDocumentTimeoutMs(PipelineSource pipelineSource) {
            return pipelineSource == PipelineSource.KNOWLEDGE ? suggestedTimeoutMs : 0L;
        }

        private int getBatchInvocationCount() {
            return batchInvocationCount.get();
        }

        private int getPageInvocationCount() {
            return pageInvocationCount.get();
        }
    }

    private static final class FixedLengthInputStream extends InputStream {

        private long remainingBytes;

        private FixedLengthInputStream(long totalBytes) {
            this.remainingBytes = totalBytes;
        }

        @Override
        public int read() {
            if (remainingBytes <= 0) {
                return -1;
            }
            remainingBytes--;
            return 0;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (remainingBytes <= 0) {
                return -1;
            }
            int bytesToRead = (int) Math.min(len, Math.min(Integer.MAX_VALUE, remainingBytes));
            remainingBytes -= bytesToRead;
            return bytesToRead;
        }
    }
}
