package com.yulong.chatagent.rag.ingestion;

import com.yulong.chatagent.knowledge.port.KnowledgeChunkRepository;
import com.yulong.chatagent.knowledge.port.KnowledgeDocumentRepository;
import com.yulong.chatagent.knowledge.application.KnowledgeDocumentStatusSseService;
import com.yulong.chatagent.rag.ingestion.model.KnowledgeChunkDraft;
import com.yulong.chatagent.rag.ingestion.model.KnowledgeIngestionContext;
import com.yulong.chatagent.rag.parser.DocumentParser;
import com.yulong.chatagent.rag.parser.DocumentParserSelector;
import com.yulong.chatagent.rag.parser.FileRejectedException;
import com.yulong.chatagent.rag.parser.ParseResult;
import com.yulong.chatagent.rag.parser.PipelineSource;
import com.yulong.chatagent.rag.parser.QualityLevel;
import com.yulong.chatagent.rag.parser.TextCleanupUtil;
import com.yulong.chatagent.rag.retrieve.KnowledgeDocumentSignalService;
import com.yulong.chatagent.rag.application.DocumentStorageService;
import com.yulong.chatagent.rag.vector.milvus.KnowledgeBaseMilvusIndexer;
import com.yulong.chatagent.support.dto.KnowledgeChunkDTO;
import com.yulong.chatagent.support.dto.KnowledgeDocumentDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.io.IOException;
import java.security.MessageDigest;

/**
 * Knowledge-base document ingestion pipeline: fetch -> parse -> enhance -> chunk -> enrich -> persist -> index.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KnowledgeDocumentIngestionServiceImpl implements KnowledgeDocumentIngestionService {

    private static final int DETECTION_PREFIX_BYTES = 8192;

    private final KnowledgeDocumentRepository knowledgeDocumentRepository;
    private final KnowledgeChunkRepository knowledgeChunkRepository;
    private final DocumentStorageService documentStorageService;
    private final DocumentParserSelector documentParserSelector;
    private final DocumentChunker documentChunker;
    private final DocumentEnhancer documentEnhancer;
    private final ChunkEnricher chunkEnricher;
    private final KnowledgeBaseMilvusIndexer knowledgeBaseMilvusIndexer;
    private final KnowledgeDocumentSignalService knowledgeDocumentSignalService;
    private final KnowledgeDocumentStatusSseService knowledgeDocumentStatusSseService;

    @Override
    @Async
    public void ingest(String knowledgeBaseId, KnowledgeDocumentDTO knowledgeDocument) {
        ingestSync(knowledgeBaseId, knowledgeDocument);
    }

    @Override
    public void ingestSync(String knowledgeBaseId, KnowledgeDocumentDTO knowledgeDocument) {
        long ingestionStart = System.nanoTime();
        String documentId = knowledgeDocument == null ? null : knowledgeDocument.getId();
        if (knowledgeDocument == null) {
            return;
        }
        if (!documentStorageService.fileExists(knowledgeDocument.getStoragePath())) {
            markFailure(knowledgeDocument, "Stored knowledge document is missing");
            throw new IllegalStateException("Stored knowledge document is missing");
        }

        String fileExtension = getFileExtension(knowledgeDocument.getOriginalFilename());
        log.info("Knowledge document ingestion started: knowledgeBaseId={}, documentId={}, filename={}, extension={}, mimeType={}",
                knowledgeBaseId,
                documentId,
                knowledgeDocument.getOriginalFilename(),
                fileExtension,
                knowledgeDocument.getMimeType());

        try {
            LoadedDocumentSource loadedSource = loadSource(knowledgeDocument);
            ParseResult parseResult = parseDocument(loadedSource, knowledgeDocument);
            log.info("Knowledge document parse result: knowledgeBaseId={}, documentId={}, parserType={}, extractionMode={}, qualityLevel={}, diagnostics={}, warnings={}",
                    knowledgeBaseId,
                    documentId,
                    parseResult.getParserType(),
                    parseResult.getExtractionMode(),
                    parseResult.getQualityLevel(),
                    parseResult.getDiagnostics(),
                    parseResult.getWarnings());
            if (parseResult.getQualityLevel() == QualityLevel.REJECTED) {
                clearIndexedContent(documentId);
                purgeDocumentSignalsQuietly(documentId, "rejected");
                markRejected(knowledgeDocument, firstWarningOrDefault(parseResult, "Knowledge document was rejected by the parser quality gate"));
                return;
            }

            KnowledgeIngestionContext context = buildIngestionContext(knowledgeBaseId, knowledgeDocument, fileExtension, parseResult);
            DocumentEnhancementResult enhancement = documentEnhancer.enhance(context);
            context.setEnhancedSegments(enhancement.enhancedSegments());
            context.setKeywords(enhancement.keywords());
            context.setQuestions(enhancement.questions());
            context.setEnhancerMetadata(enhancement.metadata());
            context.setEnhancerCacheKey(enhancement.cacheKey());
            context.setChunkDrafts(documentChunker.chunk(context.resolveChunkSegments()));
            List<KnowledgeChunkDraft> enrichedDrafts = chunkEnricher.enrich(context, context.getChunkDrafts());
            List<KnowledgeChunkDTO> chunks = buildKnowledgeChunks(documentId, enrichedDrafts);

            log.info("Knowledge document Milvus refresh started: knowledgeBaseId={}, documentId={}, chunkCount={}, action=delete-then-upsert",
                    knowledgeBaseId,
                    documentId,
                    chunks.size());
            knowledgeChunkRepository.deleteByKnowledgeDocumentId(documentId);
            knowledgeChunkRepository.saveAll(chunks);
            knowledgeBaseMilvusIndexer.deleteByKnowledgeDocumentId(documentId);
            knowledgeBaseMilvusIndexer.upsert(knowledgeBaseId, knowledgeDocument, chunks);
            knowledgeDocumentSignalService.saveOrUpdate(documentId, enhancement);
            log.info("Knowledge document Milvus refresh completed: knowledgeBaseId={}, documentId={}, chunkCount={}",
                    knowledgeBaseId,
                    documentId,
                    chunks.size());

            markCompleted(knowledgeDocument);
            log.info("Knowledge document ingestion finished: knowledgeBaseId={}, documentId={}, chunkCount={}, totalDurationMs={}",
                    knowledgeBaseId,
                    documentId,
                    chunks.size(),
                    (System.nanoTime() - ingestionStart) / 1_000_000);
        } catch (FileRejectedException e) {
            log.info("Knowledge document ingestion rejected before parse: knowledgeBaseId={}, documentId={}, reason={}",
                    knowledgeBaseId, documentId, e.getMessage());
            clearIndexedContent(documentId);
            purgeDocumentSignalsQuietly(documentId, "rejected_before_parse");
            markRejected(knowledgeDocument, e.getMessage());
        } catch (Exception e) {
            log.warn("Knowledge document ingestion failed: knowledgeBaseId={}, documentId={}, error={}",
                    knowledgeBaseId, documentId, e.getMessage());
            clearIndexedContent(documentId);
            purgeDocumentSignalsQuietly(documentId, "failed");
            markFailure(knowledgeDocument, e.getMessage());
            throw new RetryableKnowledgeDocumentIngestionException(
                    "Knowledge document ingestion failed for documentId=" + documentId,
                    e
            );
        }
    }

    private LoadedDocumentSource loadSource(KnowledgeDocumentDTO knowledgeDocument) throws Exception {
        String storagePath = knowledgeDocument.getStoragePath();
        long fileSize = documentStorageService.getFileSize(storagePath);
        FileSizeGuard.guardBeforeRead(
                fileSize,
                knowledgeDocument.getOriginalFilename()
        );
        byte[] prefix = documentStorageService.readPrefix(storagePath, DETECTION_PREFIX_BYTES);
        DocumentParser parser = documentParserSelector.selectParser(
                prefix,
                knowledgeDocument.getOriginalFilename(),
                knowledgeDocument.getMimeType(),
                PipelineSource.KNOWLEDGE
        );
        return new LoadedDocumentSource(parser, storagePath, fileSize);
    }

    private ParseResult parseDocument(LoadedDocumentSource loadedSource, KnowledgeDocumentDTO knowledgeDocument) {
        String mimeType = knowledgeDocument.getMimeType();
        if (!StringUtils.hasText(mimeType)) {
            mimeType = "application/octet-stream";
        }
        return loadedSource.parser().parse(
                () -> openStoredStream(loadedSource.storagePath()),
                mimeType,
                Map.of(
                        "fileSizeBytes", loadedSource.fileSizeBytes(),
                        "pipelineSource", PipelineSource.KNOWLEDGE,
                        "documentCacheKey", buildDocumentCacheKey(knowledgeDocument, loadedSource.storagePath())
                )
        );
    }

    private KnowledgeIngestionContext buildIngestionContext(String knowledgeBaseId,
                                                            KnowledgeDocumentDTO knowledgeDocument,
                                                            String fileExtension,
                                                            ParseResult parseResult) {
        return KnowledgeIngestionContext.builder()
                .knowledgeBaseId(knowledgeBaseId)
                .documentId(knowledgeDocument.getId())
                .fileExtension(fileExtension)
                .segments(parseResult.getSegments())
                .parseResult(parseResult)
                .build();
    }

    private List<KnowledgeChunkDTO> buildKnowledgeChunks(String documentId, List<KnowledgeChunkDraft> drafts) {
        List<KnowledgeChunkDTO> chunks = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < drafts.size(); i++) {
            KnowledgeChunkDraft draft = drafts.get(i);
            chunks.add(KnowledgeChunkDTO.builder()
                    .id(UUID.randomUUID().toString())
                    .knowledgeDocumentId(documentId)
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

    private void markCompleted(KnowledgeDocumentDTO knowledgeDocument) {
        knowledgeDocument.setParseStatus("COMPLETED");
        knowledgeDocument.setFailedReason(null);
        knowledgeDocument.setIndexedAt(LocalDateTime.now());
        knowledgeDocument.setUpdatedAt(LocalDateTime.now());
        knowledgeDocumentRepository.update(knowledgeDocument);
        knowledgeDocumentStatusSseService.publishStatusUpdated(knowledgeDocument);
    }

    private void markRejected(KnowledgeDocumentDTO knowledgeDocument, String reason) {
        knowledgeDocument.setParseStatus("REJECTED");
        knowledgeDocument.setFailedReason(reason);
        knowledgeDocument.setIndexedAt(null);
        knowledgeDocument.setUpdatedAt(LocalDateTime.now());
        knowledgeDocumentRepository.update(knowledgeDocument);
        knowledgeDocumentStatusSseService.publishStatusUpdated(knowledgeDocument);
    }

    private void markFailure(KnowledgeDocumentDTO knowledgeDocument, String errorMessage) {
        knowledgeDocument.setParseStatus("FAILED");
        knowledgeDocument.setFailedReason(errorMessage);
        knowledgeDocument.setRetryCount((knowledgeDocument.getRetryCount() == null ? 0 : knowledgeDocument.getRetryCount()) + 1);
        knowledgeDocument.setUpdatedAt(LocalDateTime.now());
        knowledgeDocumentRepository.update(knowledgeDocument);
        knowledgeDocumentStatusSseService.publishStatusUpdated(knowledgeDocument);
    }

    private void clearIndexedContent(String documentId) {
        knowledgeChunkRepository.deleteByKnowledgeDocumentId(documentId);
        knowledgeBaseMilvusIndexer.deleteByKnowledgeDocumentId(documentId);
    }

    private void purgeDocumentSignalsQuietly(String documentId, String reason) {
        if (!StringUtils.hasText(documentId)) {
            return;
        }
        try {
            knowledgeDocumentSignalService.delete(documentId);
        } catch (Exception e) {
            log.warn("Failed to purge knowledge document signals after {}: documentId={}, error={}",
                    reason, documentId, e.getMessage());
        }
    }

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

    private InputStream openStoredStream(String storagePath) {
        try {
            return documentStorageService.openInputStream(storagePath);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to open stored knowledge document stream: " + storagePath, e);
        }
    }

    private String buildDocumentCacheKey(KnowledgeDocumentDTO knowledgeDocument, String storagePath) {
        if (knowledgeDocument == null) {
            return null;
        }
        if (StringUtils.hasText(knowledgeDocument.getContentHash())) {
            return "knowledge-content:" + knowledgeDocument.getContentHash().trim();
        }
        String contentDigest = computeStoredContentDigest(storagePath);
        return StringUtils.hasText(contentDigest) ? "knowledge-content:" + contentDigest : null;
    }

    private String computeStoredContentDigest(String storagePath) {
        if (!StringUtils.hasText(storagePath)) {
            return null;
        }
        try (InputStream stream = openStoredStream(storagePath)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to compute knowledge document digest: " + storagePath, e);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute knowledge document digest: " + storagePath, e);
        }
    }

    private record LoadedDocumentSource(DocumentParser parser, String storagePath, long fileSizeBytes) {
    }
}
