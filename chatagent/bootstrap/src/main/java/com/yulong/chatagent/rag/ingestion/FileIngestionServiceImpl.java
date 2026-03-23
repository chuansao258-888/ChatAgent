package com.yulong.chatagent.rag.ingestion;

import com.yulong.chatagent.file.port.ChatSessionFileRepository;
import com.yulong.chatagent.file.port.FileChunkRepository;
import com.yulong.chatagent.rag.ingestion.model.FileIngestionContext;
import com.yulong.chatagent.rag.ingestion.model.KnowledgeChunkDraft;
import com.yulong.chatagent.rag.parser.DocumentParser;
import com.yulong.chatagent.rag.parser.DocumentParserSelector;
import com.yulong.chatagent.rag.parser.ParseResult;
import com.yulong.chatagent.rag.parser.ParserType;
import com.yulong.chatagent.rag.service.DocumentStorageService;
import com.yulong.chatagent.rag.vector.milvus.SessionFileMilvusIndexer;
import com.yulong.chatagent.support.dto.ChatSessionFileDTO;
import com.yulong.chatagent.support.dto.FileChunkDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Default session-file ingestion pipeline:
 * fetch -> parse -> enhance -> chunk -> enrich -> persist -> index.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FileIngestionServiceImpl implements FileIngestionService {

    private final ChatSessionFileRepository chatSessionFileRepository;
    private final FileChunkRepository fileChunkRepository;
    private final DocumentStorageService documentStorageService;
    private final DocumentParserSelector documentParserSelector;
    private final StructureAwareMarkdownChunker structureAwareMarkdownChunker;
    private final DocumentEnhancer documentEnhancer;
    private final ChunkEnricher chunkEnricher;
    private final SessionFileMilvusIndexer sessionFileMilvusIndexer;
    private final PlainTextChunker plainTextChunker;

    @Override
    @Async
    public void ingest(String sessionId, ChatSessionFileDTO sessionFile) {
        long ingestionStart = System.nanoTime();
        FileIngestionContext context = initializeContext(sessionId, sessionFile);
        log.info("File ingestion started: sessionId={}, sessionFileId={}, filename={}, extension={}, mimeType={}",
                sessionId,
                sessionFile.getId(),
                sessionFile.getOriginalFilename(),
                context.getFileExtension(),
                sessionFile.getMimeType());
        if (!supportsIngestion(context)) {
            log.info("File ingestion skipped: sessionId={}, sessionFileId={}, extension={}",
                    sessionId, sessionFile.getId(), context.getFileExtension());
            markSkipped(context);
            return;
        }

        try {
            long stageStart = System.nanoTime();
            fetchSource(context);
            log.info("File ingestion stage completed: sessionFileId={}, stage=fetch, bytes={}, durationMs={}",
                    sessionFile.getId(),
                    context.getRawBytes() == null ? 0 : context.getRawBytes().length,
                    elapsedMs(stageStart));

            stageStart = System.nanoTime();
            parseDocument(context);
            log.info("File ingestion stage completed: sessionFileId={}, stage=parse, rawTextLength={}, durationMs={}",
                    sessionFile.getId(),
                    context.getRawText() == null ? 0 : context.getRawText().length(),
                    elapsedMs(stageStart));

            stageStart = System.nanoTime();
            enhanceDocument(context);
            log.info("File ingestion stage completed: sessionFileId={}, stage=enhance, enhancedTextLength={}, durationMs={}",
                    sessionFile.getId(),
                    context.getEnhancedText() == null ? 0 : context.getEnhancedText().length(),
                    elapsedMs(stageStart));

            stageStart = System.nanoTime();
            chunkDocument(context);
            log.info("File ingestion stage completed: sessionFileId={}, stage=chunk, chunkCount={}, durationMs={}",
                    sessionFile.getId(),
                    context.getChunkDrafts() == null ? 0 : context.getChunkDrafts().size(),
                    elapsedMs(stageStart));

            stageStart = System.nanoTime();
            enrichChunks(context);
            log.info("File ingestion stage completed: sessionFileId={}, stage=enrich, chunkCount={}, durationMs={}",
                    sessionFile.getId(),
                    context.getChunkDrafts() == null ? 0 : context.getChunkDrafts().size(),
                    elapsedMs(stageStart));

            stageStart = System.nanoTime();
            List<FileChunkDTO> chunks = buildFileChunks(context);
            log.info("File ingestion stage completed: sessionFileId={}, stage=build_chunks, chunkCount={}, durationMs={}",
                    sessionFile.getId(),
                    chunks.size(),
                    elapsedMs(stageStart));

            stageStart = System.nanoTime();
            persistChunks(context, chunks);
            log.info("File ingestion stage completed: sessionFileId={}, stage=persist, chunkCount={}, durationMs={}",
                    sessionFile.getId(),
                    chunks.size(),
                    elapsedMs(stageStart));
            markCompleted(context);
            log.info("File ingestion finished: sessionId={}, sessionFileId={}, parseStatus={}, totalDurationMs={}",
                    sessionId,
                    sessionFile.getId(),
                    context.getSessionFile().getParseStatus(),
                    elapsedMs(ingestionStart));
        } catch (Exception e) {
            handleIngestionFailure(context, e);
        }
    }

    /**
     * Builds the mutable ingestion context once so every downstream stage works on the same
     * session-file state.
     */
    private FileIngestionContext initializeContext(String sessionId, ChatSessionFileDTO sessionFile) {
        return FileIngestionContext.builder()
                .sessionId(sessionId)
                .sessionFile(sessionFile)
                .fileExtension(getFileExtension(sessionFile.getOriginalFilename()))
                .build();
    }

    private boolean supportsIngestion(FileIngestionContext context) {
        return supportsMarkdownIngestion(context) || supportsGenericIngestion(context);
    }

    private boolean supportsMarkdownIngestion(FileIngestionContext context) {
        return "md".equals(context.getFileExtension()) || "markdown".equals(context.getFileExtension());
    }

    private boolean supportsGenericIngestion(FileIngestionContext context) {
        return Set.of("txt", "pdf", "doc", "docx").contains(context.getFileExtension());
    }


    private void fetchSource(FileIngestionContext context) throws Exception {
        context.setRawBytes(Files.readAllBytes(documentStorageService.getFilePath(context.getSessionFile().getStoragePath())));
    }

    /**
     * Dispatches parsing to the markdown-specific parser or the generic Tika-based parser.
     */
    private void parseDocument(FileIngestionContext context) throws Exception {
        if (supportsMarkdownIngestion(context)) {
            parseMarkdownDocument(context);
            return;
        }

        parseGenericDocument(context);
    }

    private void parseMarkdownDocument(FileIngestionContext context) throws Exception {
        DocumentParser parser = documentParserSelector.select(ParserType.MARKDOWN.getType());
        if (parser == null) {
            throw new IllegalStateException("Markdown parser is not configured");
        }

        ParseResult result = parser.parse(context.getRawBytes(), "text/markdown", Map.of());
        context.setRawText(result.text());
    }

    private void parseGenericDocument(FileIngestionContext context) {
        DocumentParser parser = documentParserSelector.select(ParserType.TIKA.getType());
        if (parser == null) {
            throw new IllegalStateException("Tika parser is not configured");
        }

        String mimeType = context.getSessionFile().getMimeType();
        if (mimeType == null || mimeType.isBlank()) {
            mimeType = "application/octet-stream";
        }

        ParseResult result = parser.parse(context.getRawBytes(), mimeType, Map.of());
        context.setRawText(result.text());
    }


    private void enhanceDocument(FileIngestionContext context) {
        context.setEnhancedText(documentEnhancer.enhance(context, context.getRawText()));
    }

    /**
     * Chooses a chunking strategy based on the session-file type.
     */
    private void chunkDocument(FileIngestionContext context) {
        if (supportsMarkdownIngestion(context)) {
            context.setChunkDrafts(chunkMarkdownDocument(context));
            return;
        }

        context.setChunkDrafts(chunkGenericDocument(context));
    }

    private List<KnowledgeChunkDraft> chunkMarkdownDocument(FileIngestionContext context) {
        return structureAwareMarkdownChunker.chunk(resolveChunkSourceText(context));
    }

    private List<KnowledgeChunkDraft> chunkGenericDocument(FileIngestionContext context) {
        String text = resolveChunkSourceText(context);
        if (text == null || text.isBlank()) {
            throw new IllegalStateException("Parsed generic document text is empty");
        }
        return plainTextChunker.chunk(text);
    }


    private void enrichChunks(FileIngestionContext context) {
        context.setChunkDrafts(chunkEnricher.enrich(context, context.getChunkDrafts()));
    }

    /**
     * Materializes transient chunk drafts into persistence DTOs with stable ids and indices.
     */
    private List<FileChunkDTO> buildFileChunks(FileIngestionContext context) {
        List<FileChunkDTO> chunks = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < context.getChunkDrafts().size(); i++) {
            KnowledgeChunkDraft draft = context.getChunkDrafts().get(i);
            chunks.add(FileChunkDTO.builder()
                    .id(UUID.randomUUID().toString())
                    .sessionFileId(context.getSessionFile().getId())
                    .chunkIndex(i)
                    .content(draft.content())
                    .tokenCount(null)
                    .metadata(draft.metadata())
                    .enabled(true)
                    .createdAt(now)
                    .updatedAt(now)
                    .build());
        }
        return chunks;
    }

    private void persistChunks(FileIngestionContext context, List<FileChunkDTO> chunks) {
        fileChunkRepository.deleteBySessionFileId(context.getSessionFile().getId());
        fileChunkRepository.saveAll(chunks);
        sessionFileMilvusIndexer.upsert(context.getSessionId(), context.getSessionFile(), chunks);
    }

    private void handleIngestionFailure(FileIngestionContext context, Exception e) {
        log.warn("Failed to parse uploaded file into chunks: sessionFileId={}, error={}",
                context.getSessionFile().getId(), e.getMessage());
        sessionFileMilvusIndexer.deleteBySessionFileId(context.getSessionFile().getId());
        markParseStatus(context.getSessionFile(), "FAILED");
    }

    private void markParseStatus(ChatSessionFileDTO sessionFile, String parseStatus) {
        sessionFile.setParseStatus(parseStatus);
        sessionFile.setUpdatedAt(LocalDateTime.now());
        chatSessionFileRepository.update(sessionFile);
    }

    private void markSkipped(FileIngestionContext context) {
        markParseStatus(context.getSessionFile(), "SKIPPED");
    }

    private void markCompleted(FileIngestionContext context) {
        markParseStatus(context.getSessionFile(), "COMPLETED");
    }

    private long elapsedMs(long startTime) {
        return (System.nanoTime() - startTime) / 1_000_000;
    }

    private String resolveChunkSourceText(FileIngestionContext context) {
        if (StringUtils.hasText(context.getEnhancedText())) {
            return context.getEnhancedText();
        }
        return context.getRawText();
    }

    /**
     * Uses the original filename as the primary type signal because upload MIME types are often
     * too generic for markdown and office documents.
     */
    private String getFileExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "";
        }
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
    }
}
