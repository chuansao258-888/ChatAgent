package com.yulong.chatagent.rag.ingestion;

import com.yulong.chatagent.knowledge.application.KnowledgeDocumentStatusSseService;
import com.yulong.chatagent.knowledge.port.KnowledgeChunkRepository;
import com.yulong.chatagent.knowledge.port.KnowledgeDocumentRepository;
import com.yulong.chatagent.rag.ingestion.model.KnowledgeChunkDraft;
import com.yulong.chatagent.rag.parser.DocumentParser;
import com.yulong.chatagent.rag.parser.DocumentParserSelector;
import com.yulong.chatagent.rag.parser.ParseResult;
import com.yulong.chatagent.rag.parser.ParseSegment;
import com.yulong.chatagent.rag.parser.PipelineSource;
import com.yulong.chatagent.rag.parser.QualityLevel;
import com.yulong.chatagent.rag.parser.SegmentType;
import com.yulong.chatagent.rag.retrieve.KnowledgeDocumentSignalService;
import com.yulong.chatagent.rag.application.DocumentStorageService;
import com.yulong.chatagent.rag.vector.milvus.KnowledgeBaseMilvusIndexer;
import com.yulong.chatagent.support.dto.KnowledgeChunkDTO;
import com.yulong.chatagent.support.dto.KnowledgeDocumentDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.security.MessageDigest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeDocumentIngestionServiceImplTest {

    @Mock
    private KnowledgeDocumentRepository knowledgeDocumentRepository;

    @Mock
    private KnowledgeChunkRepository knowledgeChunkRepository;

    @Mock
    private DocumentStorageService documentStorageService;

    @Mock
    private DocumentParserSelector documentParserSelector;

    @Mock
    private DocumentChunker documentChunker;

    @Mock
    private DocumentEnhancer documentEnhancer;

    @Mock
    private ChunkEnricher chunkEnricher;

    @Mock
    private KnowledgeBaseMilvusIndexer knowledgeBaseMilvusIndexer;

    @Mock
    private KnowledgeDocumentSignalService knowledgeDocumentSignalService;

    @Mock
    private KnowledgeDocumentStatusSseService knowledgeDocumentStatusSseService;

    @Mock
    private DocumentParser documentParser;

    @TempDir
    Path tempDir;

    private KnowledgeDocumentIngestionServiceImpl ingestionService;

    @BeforeEach
    void setUp() {
        ingestionService = new KnowledgeDocumentIngestionServiceImpl(
                knowledgeDocumentRepository,
                knowledgeChunkRepository,
                documentStorageService,
                documentParserSelector,
                documentChunker,
                documentEnhancer,
                chunkEnricher,
                knowledgeBaseMilvusIndexer,
                knowledgeDocumentSignalService,
                knowledgeDocumentStatusSseService
        );
    }

    @Test
    void shouldPersistDocumentSignalsAfterSuccessfulIngestion() throws Exception {
        Path storedFile = tempDir.resolve("doc.md");
        Files.writeString(storedFile, "leave policy");

        KnowledgeDocumentDTO document = KnowledgeDocumentDTO.builder()
                .id("doc-1")
                .knowledgeBaseId("kb-1")
                .storagePath("knowledge/doc-1")
                .originalFilename("doc.md")
                .mimeType("text/markdown")
                .retryCount(0)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        ParseResult parseResult = ParseResult.builder()
                .segments(List.of(new ParseSegment("leave policy", 0, SegmentType.FULL, Map.of())))
                .qualityLevel(QualityLevel.HIGH)
                .extractionMode("NATIVE_TEXT")
                .build();
        DocumentEnhancementResult enhancementResult = new DocumentEnhancementResult(
                null,
                List.of("leave policy"),
                List.of("How many leave days can be carried over?"),
                Map.of("doc_type", "policy", "contains_pii", false),
                "cache-1"
        );
        List<KnowledgeChunkDraft> drafts = List.of(new KnowledgeChunkDraft("carry over rules", "{}"));

        when(documentStorageService.fileExists("knowledge/doc-1")).thenReturn(true);
        when(documentStorageService.getFileSize("knowledge/doc-1")).thenReturn((long) Files.size(storedFile));
        when(documentStorageService.readPrefix(eq("knowledge/doc-1"), anyInt())).thenReturn("leave policy".getBytes());
        when(documentStorageService.openInputStream("knowledge/doc-1")).thenAnswer(invocation -> Files.newInputStream(storedFile));
        when(documentParserSelector.selectParser(any(), eq("doc.md"), eq("text/markdown"), eq(PipelineSource.KNOWLEDGE)))
                .thenReturn(documentParser);
        when(documentParser.parse(org.mockito.ArgumentMatchers.<java.util.function.Supplier<java.io.InputStream>>any(),
                eq("text/markdown"),
                any())).thenReturn(parseResult);
        when(documentEnhancer.enhance(any())).thenReturn(enhancementResult);
        when(documentChunker.chunk(any())).thenReturn(drafts);
        when(chunkEnricher.enrich(any(), eq(drafts))).thenReturn(drafts);
        when(knowledgeDocumentRepository.update(any())).thenReturn(true);

        ingestionService.ingestSync("kb-1", document);

        verify(knowledgeDocumentSignalService).saveOrUpdate("doc-1", enhancementResult);
        verify(knowledgeChunkRepository).saveAll(anyList());
        verify(knowledgeBaseMilvusIndexer).upsert(eq("kb-1"), eq(document), anyList());
    }

    @Test
    void shouldStripNullCharactersBeforePersistingKnowledgeChunks() throws Exception {
        Path storedFile = tempDir.resolve("doc-null.pdf");
        Files.writeString(storedFile, "pdf payload");

        KnowledgeDocumentDTO document = KnowledgeDocumentDTO.builder()
                .id("doc-null")
                .knowledgeBaseId("kb-1")
                .storagePath("knowledge/doc-null")
                .originalFilename("doc.pdf")
                .mimeType("application/pdf")
                .retryCount(0)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        ParseResult parseResult = ParseResult.builder()
                .segments(List.of(new ParseSegment("parsed\u0000content", 0, SegmentType.PAGE, Map.of("pageNumber", 1))))
                .qualityLevel(QualityLevel.HIGH)
                .extractionMode("NATIVE_TEXT")
                .build();
        List<KnowledgeChunkDraft> drafts = List.of(new KnowledgeChunkDraft(
                "chunk\u0000-body",
                "{\"raw\":\"meta\u0000data\"}"
        ));

        when(documentStorageService.fileExists("knowledge/doc-null")).thenReturn(true);
        when(documentStorageService.getFileSize("knowledge/doc-null")).thenReturn((long) Files.size(storedFile));
        when(documentStorageService.readPrefix(eq("knowledge/doc-null"), anyInt())).thenReturn("pdf".getBytes());
        when(documentStorageService.openInputStream("knowledge/doc-null")).thenAnswer(invocation -> Files.newInputStream(storedFile));
        when(documentParserSelector.selectParser(any(), eq("doc.pdf"), eq("application/pdf"), eq(PipelineSource.KNOWLEDGE)))
                .thenReturn(documentParser);
        when(documentParser.parse(org.mockito.ArgumentMatchers.<java.util.function.Supplier<InputStream>>any(),
                eq("application/pdf"),
                any())).thenReturn(parseResult);
        when(documentEnhancer.enhance(any())).thenReturn(DocumentEnhancementResult.empty());
        when(documentChunker.chunk(any())).thenReturn(drafts);
        when(chunkEnricher.enrich(any(), eq(drafts))).thenReturn(drafts);
        when(knowledgeDocumentRepository.update(any())).thenReturn(true);

        ingestionService.ingestSync("kb-1", document);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<KnowledgeChunkDTO>> chunkCaptor = ArgumentCaptor.forClass(List.class);
        verify(knowledgeChunkRepository).saveAll(chunkCaptor.capture());
        assertThat(chunkCaptor.getValue()).singleElement().satisfies(chunk -> {
            assertThat(chunk.getContent()).isEqualTo("chunk-body").doesNotContain("\u0000");
            assertThat(chunk.getMetadata()).isEqualTo("{\"raw\":\"metadata\"}").doesNotContain("\u0000");
        });
    }

    @Test
    void shouldPurgeSignalsWhenParserRejectsDocument() throws Exception {
        Path storedFile = tempDir.resolve("doc.pdf");
        Files.write(storedFile, "bad pdf".getBytes());

        KnowledgeDocumentDTO document = KnowledgeDocumentDTO.builder()
                .id("doc-1")
                .knowledgeBaseId("kb-1")
                .storagePath("knowledge/doc-1")
                .originalFilename("doc.pdf")
                .mimeType("application/pdf")
                .retryCount(0)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        ParseResult parseResult = ParseResult.builder()
                .segments(List.of(new ParseSegment("garbled", 0, SegmentType.PAGE, Map.of("pageNumber", 1))))
                .qualityLevel(QualityLevel.REJECTED)
                .warnings(List.of("Parser quality gate rejected this PDF"))
                .extractionMode("NATIVE_TEXT")
                .build();

        when(documentStorageService.fileExists("knowledge/doc-1")).thenReturn(true);
        when(documentStorageService.getFileSize("knowledge/doc-1")).thenReturn((long) Files.size(storedFile));
        when(documentStorageService.readPrefix(eq("knowledge/doc-1"), anyInt())).thenReturn("bad pdf".getBytes());
        when(documentStorageService.openInputStream("knowledge/doc-1")).thenAnswer(invocation -> Files.newInputStream(storedFile));
        when(documentParserSelector.selectParser(any(), eq("doc.pdf"), eq("application/pdf"), eq(PipelineSource.KNOWLEDGE)))
                .thenReturn(documentParser);
        when(documentParser.parse(org.mockito.ArgumentMatchers.<java.util.function.Supplier<java.io.InputStream>>any(),
                eq("application/pdf"),
                any())).thenReturn(parseResult);
        when(knowledgeDocumentRepository.update(any())).thenReturn(true);

        ingestionService.ingestSync("kb-1", document);

        verify(knowledgeDocumentSignalService).delete("doc-1");
        verify(knowledgeChunkRepository).deleteByKnowledgeDocumentId("doc-1");
        verify(knowledgeBaseMilvusIndexer).deleteByKnowledgeDocumentId("doc-1");
        verify(documentEnhancer, never()).enhance(any());
    }

    @Test
    void shouldUseStoredContentDigestWhenKnowledgeDocumentContentHashMissing() throws Exception {
        Path storedFile = tempDir.resolve("doc-no-hash.pdf");
        Files.writeString(storedFile, "pdf payload for digest fallback");
        String expectedDigest = sha256Hex(Files.readAllBytes(storedFile));

        KnowledgeDocumentDTO document = KnowledgeDocumentDTO.builder()
                .id("doc-no-hash")
                .knowledgeBaseId("kb-1")
                .storagePath("knowledge/doc-no-hash")
                .originalFilename("doc.pdf")
                .mimeType("application/pdf")
                .retryCount(0)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        ParseResult parseResult = ParseResult.builder()
                .segments(List.of(new ParseSegment("parsed content", 0, SegmentType.PAGE, Map.of("pageNumber", 1))))
                .qualityLevel(QualityLevel.HIGH)
                .extractionMode("NATIVE_TEXT")
                .build();
        DocumentEnhancementResult enhancementResult = new DocumentEnhancementResult(
                null,
                List.of(),
                List.of(),
                Map.of(),
                null
        );

        when(documentStorageService.fileExists("knowledge/doc-no-hash")).thenReturn(true);
        when(documentStorageService.getFileSize("knowledge/doc-no-hash")).thenReturn((long) Files.size(storedFile));
        when(documentStorageService.readPrefix(eq("knowledge/doc-no-hash"), anyInt())).thenReturn("pdf".getBytes());
        when(documentStorageService.openInputStream("knowledge/doc-no-hash")).thenAnswer(invocation -> Files.newInputStream(storedFile));
        when(documentParserSelector.selectParser(any(), eq("doc.pdf"), eq("application/pdf"), eq(PipelineSource.KNOWLEDGE)))
                .thenReturn(documentParser);
        when(documentParser.parse(org.mockito.ArgumentMatchers.<java.util.function.Supplier<InputStream>>any(),
                eq("application/pdf"),
                any())).thenReturn(parseResult);
        when(documentEnhancer.enhance(any())).thenReturn(enhancementResult);
        when(documentChunker.chunk(any())).thenReturn(List.of());
        when(chunkEnricher.enrich(any(), eq(List.of()))).thenReturn(List.of());
        when(knowledgeDocumentRepository.update(any())).thenReturn(true);

        ingestionService.ingestSync("kb-1", document);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> optionsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(documentParser).parse(
                org.mockito.ArgumentMatchers.<java.util.function.Supplier<InputStream>>any(),
                eq("application/pdf"),
                optionsCaptor.capture()
        );
        assertThat(optionsCaptor.getValue())
                .containsEntry("documentCacheKey", "knowledge-content:" + expectedDigest);
    }

    private String sha256Hex(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }
}
