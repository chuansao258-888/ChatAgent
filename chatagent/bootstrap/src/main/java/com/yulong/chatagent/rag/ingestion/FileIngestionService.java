package com.yulong.chatagent.rag.ingestion;

import com.yulong.chatagent.file.port.ChatSessionFileRepository;
import com.yulong.chatagent.file.port.FileChunkRepository;
import com.yulong.chatagent.rag.application.DocumentStorageService;
import com.yulong.chatagent.rag.ingestion.model.KnowledgeChunkDraft;
import com.yulong.chatagent.rag.ingestion.model.SessionIngestionContext;
import com.yulong.chatagent.rag.parser.DocumentParser;
import com.yulong.chatagent.rag.parser.DocumentParserSelector;
import com.yulong.chatagent.rag.parser.FileRejectedException;
import com.yulong.chatagent.rag.parser.ParseResult;
import com.yulong.chatagent.rag.parser.ParserType;
import com.yulong.chatagent.rag.parser.PipelineSource;
import com.yulong.chatagent.rag.parser.QualityLevel;
import com.yulong.chatagent.rag.parser.TextCleanupUtil;
import com.yulong.chatagent.rag.vector.milvus.SessionFileMilvusIndexer;
import com.yulong.chatagent.support.dto.ChatSessionFileDTO;
import com.yulong.chatagent.support.dto.FileChunkDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Default session-file ingestion pipeline:
 * fetch -> parse -> enhance -> chunk -> enrich -> persist -> index.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FileIngestionService {

    private static final int DETECTION_PREFIX_BYTES = 8192;

    private final ChatSessionFileRepository chatSessionFileRepository;
    private final FileChunkRepository fileChunkRepository;
    private final DocumentStorageService documentStorageService;
    private final DocumentParserSelector documentParserSelector;
    private final DocumentChunker documentChunker;
    private final DocumentEnhancer documentEnhancer;
    private final ChunkEnricher chunkEnricher;
    private final SessionFileMilvusIndexer sessionFileMilvusIndexer;

    @Async
    public void ingest(String sessionId, ChatSessionFileDTO sessionFile) {
        long ingestionStart = System.nanoTime();
        SessionIngestionContext context = initializeContext(sessionId, sessionFile);
        log.info("File ingestion started: sessionId={}, sessionFileId={}, filename={}, extension={}, mimeType={}",
                sessionId,
                sessionFile.getId(),
                sessionFile.getOriginalFilename(),
                context.getFileExtension(),
                sessionFile.getMimeType());

        try {
            long stageStart = System.nanoTime();
            LoadedDocumentSource loadedSource = fetchSource(context);
            log.info("File ingestion stage completed: sessionFileId={}, stage=fetch, bytes={}, durationMs={}",
                    sessionFile.getId(),
                    loadedSource.fileSizeBytes(),
                    elapsedMs(stageStart));

            stageStart = System.nanoTime();
            ParseResult parseResult = parseDocument(context, loadedSource);
            log.info("File ingestion stage completed: sessionFileId={}, stage=parse, parsedChars={}, durationMs={}",
                    sessionFile.getId(),
                    parseResult.totalChars(),
                    elapsedMs(stageStart));
            if (shouldRejectParseResult(parseResult)) {
                handleRejectedParseResult(context, parseResult);
                return;
            }

            stageStart = System.nanoTime();
            DocumentEnhancementResult enhancement = enhanceDocument(context);
            log.info("File ingestion stage completed: sessionFileId={}, stage=enhance, enhancedSegmentChars={}, durationMs={}",
                    sessionFile.getId(),
                    totalChars(context.getEnhancedSegments()),
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
        } catch (FileRejectedException e) {
            handleRejected(context, e.getMessage());
        } catch (Exception e) {
            handleIngestionFailure(context, e);
        }
    }

    /**
     * Builds the mutable ingestion context once so every downstream stage works on the same
     * session-file state.
     */
    private SessionIngestionContext initializeContext(String sessionId, ChatSessionFileDTO sessionFile) {
        return SessionIngestionContext.builder()
                .sessionId(sessionId)
                .sessionFile(sessionFile)
                .fileExtension(getFileExtension(sessionFile.getOriginalFilename()))
                .build();
    }

    private boolean supportsMarkdownIngestion(SessionIngestionContext context) {
        return "md".equals(context.getFileExtension()) || "markdown".equals(context.getFileExtension());
    }

    private LoadedDocumentSource fetchSource(SessionIngestionContext context) throws Exception {
        String storagePath = context.getSessionFile().getStoragePath();
        long fileSize = documentStorageService.getFileSize(storagePath);
        FileSizeGuard.guardBeforeRead(
                fileSize,
                context.getSessionFile().getOriginalFilename()
        );
        byte[] prefix = documentStorageService.readPrefix(storagePath, DETECTION_PREFIX_BYTES);
        DocumentParser parser = documentParserSelector.selectParser(
                prefix,
                context.getSessionFile().getOriginalFilename(),
                context.getSessionFile().getMimeType(),
                PipelineSource.SESSION
        );
        return new LoadedDocumentSource(parser, storagePath, fileSize);
    }

    private ParseResult parseDocument(SessionIngestionContext context, LoadedDocumentSource loadedSource) {
        String mimeType = context.getSessionFile().getMimeType();
        if (!StringUtils.hasText(mimeType)) {
            mimeType = "application/octet-stream";
        }
        ParseResult result = loadedSource.parser().parse(
                () -> openStoredStream(loadedSource.storagePath()),
                mimeType,
                Map.of(
                        "fileSizeBytes", loadedSource.fileSizeBytes(),
                        "pipelineSource", PipelineSource.SESSION,
                        "sessionId", context.getSessionId(),
                        "documentCacheKey", buildDocumentCacheKey(context)
                )
        );
        context.setParseResult(result);
        context.setSegments(result.getSegments());
        return result;
    }

    private DocumentEnhancementResult enhanceDocument(SessionIngestionContext context) {
        DocumentEnhancementResult enhancement = documentEnhancer.enhance(context);
        context.setEnhancedSegments(enhancement.enhancedSegments());
        return enhancement;
    }

    private void chunkDocument(SessionIngestionContext context) {
        List<KnowledgeChunkDraft> drafts = documentChunker.chunk(context.resolveChunkSegments());
        if (drafts == null || drafts.isEmpty()) {
            if (allowsEmptyChunkOutcome(context)) {
                context.setChunkDrafts(List.of());
                return;
            }
            throw new IllegalStateException("Parsed generic document text is empty");
        }
        context.setChunkDrafts(drafts);
    }

    private void enrichChunks(SessionIngestionContext context) {
        context.setChunkDrafts(chunkEnricher.enrich(context, context.getChunkDrafts()));
    }

    /**
     * Materializes transient chunk drafts into persistence DTOs with stable ids and indices.
     */
    private List<FileChunkDTO> buildFileChunks(SessionIngestionContext context) {
        List<FileChunkDTO> chunks = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < context.getChunkDrafts().size(); i++) {
            KnowledgeChunkDraft draft = context.getChunkDrafts().get(i);
            chunks.add(FileChunkDTO.builder()
                    .id(UUID.randomUUID().toString())
                    .sessionFileId(context.getSessionFile().getId())
                    .chunkIndex(i)
                    .content(TextCleanupUtil.stripNullCharacters(draft.content()))
                    .tokenCount(null)
                    .metadata(TextCleanupUtil.stripNullCharacters(draft.metadata()))
                    .enabled(true)
                    .createdAt(now)
                    .updatedAt(now)
                    .build());
        }
        return chunks;
    }

    private void persistChunks(SessionIngestionContext context, List<FileChunkDTO> chunks) {
        fileChunkRepository.deleteBySessionFileId(context.getSessionFile().getId());
        fileChunkRepository.saveAll(chunks);
        sessionFileMilvusIndexer.upsert(context.getSessionId(), context.getSessionFile(), chunks);
    }

    private boolean shouldRejectParseResult(ParseResult parseResult) {
        return "OCR_REQUIRED".equalsIgnoreCase(parseResult.getExtractionMode())
                || parseResult.getQualityLevel() == QualityLevel.REJECTED;
    }

    private void handleRejectedParseResult(SessionIngestionContext context, ParseResult parseResult) {
        if ("OCR_REQUIRED".equalsIgnoreCase(parseResult.getExtractionMode())) {
            handleRejected(context, "Session file requires OCR and session uploads do not support asynchronous OCR fallback");
            return;
        }
        handleRejected(context, firstWarningOrDefault(parseResult, "Session file was rejected by the parser quality gate"));
    }

    private void handleRejected(SessionIngestionContext context, String reason) {
        log.info("Session file ingestion rejected: sessionFileId={}, reason={}",
                context.getSessionFile().getId(), reason);
        fileChunkRepository.deleteBySessionFileId(context.getSessionFile().getId());
        sessionFileMilvusIndexer.deleteBySessionFileId(context.getSessionFile().getId());
        markRejected(context);
    }

    private void handleIngestionFailure(SessionIngestionContext context, Exception e) {
        log.warn("Failed to parse uploaded file into chunks: sessionFileId={}, error={}",
                context.getSessionFile().getId(), e.getMessage());
        fileChunkRepository.deleteBySessionFileId(context.getSessionFile().getId());
        sessionFileMilvusIndexer.deleteBySessionFileId(context.getSessionFile().getId());
        markParseStatus(context.getSessionFile(), "FAILED");
    }

    private void markParseStatus(ChatSessionFileDTO sessionFile, String parseStatus) {
        sessionFile.setParseStatus(parseStatus);
        sessionFile.setUpdatedAt(LocalDateTime.now());
        chatSessionFileRepository.update(sessionFile);
    }

    private void markCompleted(SessionIngestionContext context) {
        markParseStatus(context.getSessionFile(), "COMPLETED");
    }

    private void markRejected(SessionIngestionContext context) {
        markParseStatus(context.getSessionFile(), "REJECTED");
    }

    private int totalChars(List<com.yulong.chatagent.rag.parser.ParseSegment> segments) {
        if (segments == null || segments.isEmpty()) {
            return 0;
        }
        return segments.stream().mapToInt(com.yulong.chatagent.rag.parser.ParseSegment::charCount).sum();
    }

    private long elapsedMs(long startTime) {
        return (System.nanoTime() - startTime) / 1_000_000;
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

    private String firstWarningOrDefault(ParseResult parseResult, String defaultMessage) {
        if (parseResult.getWarnings() == null || parseResult.getWarnings().isEmpty()) {
            return defaultMessage;
        }
        String firstWarning = parseResult.getWarnings().get(0);
        return StringUtils.hasText(firstWarning) ? firstWarning : defaultMessage;
    }

    private boolean allowsEmptyChunkOutcome(SessionIngestionContext context) {
        ParseResult parseResult = context == null ? null : context.getParseResult();
        return parseResult != null
                && ParserType.IMAGE.getType().equals(parseResult.getParserType());
    }

    private InputStream openStoredStream(String storagePath) {
        try {
            return documentStorageService.openInputStream(storagePath);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to open stored session file stream: " + storagePath, e);
        }
    }

    private String buildDocumentCacheKey(SessionIngestionContext context) {
        if (context == null || context.getSessionFile() == null) {
            return null;
        }
        if (StringUtils.hasText(context.getSessionFile().getId())) {
            return "session-file:" + context.getSessionFile().getId().trim();
        }
        return StringUtils.hasText(context.getSessionFile().getStoragePath())
                ? "session-storage:" + context.getSessionFile().getStoragePath().trim()
                : null;
    }

    private record LoadedDocumentSource(DocumentParser parser, String storagePath, long fileSizeBytes) {
    }
}
