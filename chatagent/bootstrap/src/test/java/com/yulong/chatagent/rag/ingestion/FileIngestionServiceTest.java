package com.yulong.chatagent.rag.ingestion;

import com.yulong.chatagent.file.port.ChatSessionFileRepository;
import com.yulong.chatagent.file.port.FileChunkRepository;
import com.yulong.chatagent.rag.ingestion.model.KnowledgeChunkDraft;
import com.yulong.chatagent.rag.parser.DocumentParser;
import com.yulong.chatagent.rag.parser.DocumentParserSelector;
import com.yulong.chatagent.rag.parser.ParseResult;
import com.yulong.chatagent.rag.parser.ParseSegment;
import com.yulong.chatagent.rag.parser.ParserType;
import com.yulong.chatagent.rag.parser.PipelineSource;
import com.yulong.chatagent.rag.parser.QualityLevel;
import com.yulong.chatagent.rag.parser.SegmentType;
import com.yulong.chatagent.rag.application.DocumentStorageService;
import com.yulong.chatagent.rag.vector.milvus.SessionFileMilvusIndexer;
import com.yulong.chatagent.support.dto.ChatSessionFileDTO;
import com.yulong.chatagent.support.dto.FileChunkDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FileIngestionServiceTest {

    @Mock
    private ChatSessionFileRepository chatSessionFileRepository;

    @Mock
    private FileChunkRepository fileChunkRepository;

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
    private SessionFileMilvusIndexer sessionFileMilvusIndexer;

    @Mock
    private DocumentParser documentParser;

    private FileIngestionService ingestionService;

    @BeforeEach
    void setUp() {
        ingestionService = new FileIngestionService(
                chatSessionFileRepository,
                fileChunkRepository,
                documentStorageService,
                documentParserSelector,
                documentChunker,
                documentEnhancer,
                chunkEnricher,
                sessionFileMilvusIndexer
        );
    }

    @Test
    void shouldCompleteImageIngestionWhenDegradedResultProducesNoChunks() throws Exception {
        ChatSessionFileDTO sessionFile = ChatSessionFileDTO.builder()
                .id("file-1")
                .sessionId("session-1")
                .originalFilename("scan.png")
                .mimeType("image/png")
                .storagePath("sessions/session-1/file-1/scan.png")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        ParseResult parseResult = ParseResult.builder()
                .segments(List.of(new ParseSegment(
                        "",
                        0,
                        SegmentType.FIGURE,
                        Map.of("contentOrigin", "VDP_TRANSCRIBED", "degraded", true, "interpretiveNote", "[图像解析失败]")
                )))
                .parserType(ParserType.IMAGE.getType())
                .qualityLevel(QualityLevel.MEDIUM)
                .extractionMode("VLM_IMAGE")
                .build();

        when(documentStorageService.getFileSize(sessionFile.getStoragePath())).thenReturn(128L);
        when(documentStorageService.readPrefix(eq(sessionFile.getStoragePath()), anyInt())).thenReturn(new byte[]{1, 2, 3});
        when(documentParserSelector.selectParser(any(), eq("scan.png"), eq("image/png"), eq(PipelineSource.SESSION)))
                .thenReturn(documentParser);
        when(documentParser.parse(org.mockito.ArgumentMatchers.<Supplier<InputStream>>any(), eq("image/png"), any()))
                .thenReturn(parseResult);
        when(documentEnhancer.enhance(any())).thenReturn(DocumentEnhancementResult.empty());
        when(documentChunker.chunk(any())).thenReturn(List.of());
        when(chunkEnricher.enrich(any(), eq(List.of()))).thenReturn(List.of());

        ingestionService.ingest("session-1", sessionFile);

        verify(fileChunkRepository).deleteBySessionFileId("file-1");
        verify(fileChunkRepository).saveAll(List.of());
        verify(sessionFileMilvusIndexer).upsert(eq("session-1"), eq(sessionFile), eq(List.of()));
        verify(chatSessionFileRepository).update(org.mockito.ArgumentMatchers.argThat(updated ->
                "COMPLETED".equals(updated.getParseStatus())
        ));
        verify(sessionFileMilvusIndexer, never()).deleteBySessionFileId("file-1");
    }

    @Test
    void shouldStripNullCharactersBeforePersistingSessionFileChunks() throws Exception {
        ChatSessionFileDTO sessionFile = ChatSessionFileDTO.builder()
                .id("file-null")
                .sessionId("session-1")
                .originalFilename("notes.md")
                .mimeType("text/markdown")
                .storagePath("sessions/session-1/file-null/notes.md")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        ParseResult parseResult = ParseResult.builder()
                .segments(List.of(new ParseSegment("note\u0000s", 0, SegmentType.FULL, Map.of())))
                .parserType(ParserType.MARKDOWN.getType())
                .qualityLevel(QualityLevel.HIGH)
                .extractionMode("NATIVE_TEXT")
                .build();
        List<KnowledgeChunkDraft> drafts = List.of(new KnowledgeChunkDraft(
                "hello\u0000world",
                "{\"trace\":\"ab\u0000cd\"}",
                "hello world"
        ));

        when(documentStorageService.getFileSize(sessionFile.getStoragePath())).thenReturn(64L);
        when(documentStorageService.readPrefix(eq(sessionFile.getStoragePath()), anyInt())).thenReturn("notes".getBytes());
        when(documentParserSelector.selectParser(any(), eq("notes.md"), eq("text/markdown"), eq(PipelineSource.SESSION)))
                .thenReturn(documentParser);
        when(documentParser.parse(org.mockito.ArgumentMatchers.<Supplier<InputStream>>any(), eq("text/markdown"), any()))
                .thenReturn(parseResult);
        when(documentEnhancer.enhance(any())).thenReturn(DocumentEnhancementResult.empty());
        when(documentChunker.chunk(any())).thenReturn(drafts);
        when(chunkEnricher.enrich(any(), eq(drafts))).thenReturn(drafts);

        ingestionService.ingest("session-1", sessionFile);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<FileChunkDTO>> chunkCaptor = ArgumentCaptor.forClass(List.class);
        verify(fileChunkRepository).saveAll(chunkCaptor.capture());
        assertThat(chunkCaptor.getValue()).singleElement().satisfies(chunk -> {
            assertThat(chunk.getContent()).isEqualTo("helloworld").doesNotContain("\u0000");
            assertThat(chunk.getMetadata()).isEqualTo("{\"trace\":\"abcd\"}").doesNotContain("\u0000");
        });
    }
}
