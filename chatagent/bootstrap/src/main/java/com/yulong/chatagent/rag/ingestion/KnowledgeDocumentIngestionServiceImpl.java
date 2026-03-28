package com.yulong.chatagent.rag.ingestion;

import com.yulong.chatagent.knowledge.port.KnowledgeChunkRepository;
import com.yulong.chatagent.knowledge.port.KnowledgeDocumentRepository;
import com.yulong.chatagent.rag.ingestion.model.FileIngestionContext;
import com.yulong.chatagent.rag.ingestion.model.KnowledgeChunkDraft;
import com.yulong.chatagent.rag.parser.DocumentParser;
import com.yulong.chatagent.rag.parser.DocumentParserSelector;
import com.yulong.chatagent.rag.parser.ParseResult;
import com.yulong.chatagent.rag.parser.ParserType;
import com.yulong.chatagent.rag.service.DocumentStorageService;
import com.yulong.chatagent.rag.vector.milvus.KnowledgeBaseMilvusIndexer;
import com.yulong.chatagent.support.dto.KnowledgeChunkDTO;
import com.yulong.chatagent.support.dto.KnowledgeDocumentDTO;
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
 * Knowledge-base document ingestion pipeline: fetch -> parse -> enhance -> chunk -> enrich -> persist -> index.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KnowledgeDocumentIngestionServiceImpl implements KnowledgeDocumentIngestionService {

    private final KnowledgeDocumentRepository knowledgeDocumentRepository;
    private final KnowledgeChunkRepository knowledgeChunkRepository;
    private final DocumentStorageService documentStorageService;
    private final DocumentParserSelector documentParserSelector;
    private final StructureAwareMarkdownChunker structureAwareMarkdownChunker;
    private final DocumentEnhancer documentEnhancer;
    private final ChunkEnricher chunkEnricher;
    private final PlainTextChunker plainTextChunker;
    private final KnowledgeBaseMilvusIndexer knowledgeBaseMilvusIndexer;

    @Override
    @Async
    public void ingest(String knowledgeBaseId, KnowledgeDocumentDTO knowledgeDocument) {
        long ingestionStart = System.nanoTime();
        String documentId = knowledgeDocument == null ? null : knowledgeDocument.getId();
        if (knowledgeDocument == null) {
            return;
        }
        if (!documentStorageService.fileExists(knowledgeDocument.getStoragePath())) {
            markFailure(knowledgeDocument, "Stored knowledge document is missing");
            return;
        }

        String fileExtension = getFileExtension(knowledgeDocument.getOriginalFilename());
        log.info("Knowledge document ingestion started: knowledgeBaseId={}, documentId={}, filename={}, extension={}, mimeType={}",
                knowledgeBaseId,
                documentId,
                knowledgeDocument.getOriginalFilename(),
                fileExtension,
                knowledgeDocument.getMimeType());

        if (!supportsIngestion(fileExtension)) {
            markSkipped(knowledgeDocument);
            return;
        }

        try {
            byte[] rawBytes = Files.readAllBytes(documentStorageService.getFilePath(knowledgeDocument.getStoragePath()));
            String rawText = parseDocument(rawBytes, knowledgeDocument, fileExtension);
            String enhancedText = documentEnhancer.enhance(buildEnrichmentContext(fileExtension, rawText, null), rawText);
            List<KnowledgeChunkDraft> drafts = chunkDocument(fileExtension, enhancedText, rawText);
            List<KnowledgeChunkDraft> enrichedDrafts = chunkEnricher.enrich(
                    buildEnrichmentContext(fileExtension, rawText, enhancedText),
                    drafts
            );
            List<KnowledgeChunkDTO> chunks = buildKnowledgeChunks(documentId, enrichedDrafts);

            knowledgeChunkRepository.deleteByKnowledgeDocumentId(documentId);
            knowledgeChunkRepository.saveAll(chunks);
            knowledgeBaseMilvusIndexer.deleteByKnowledgeDocumentId(documentId);
            knowledgeBaseMilvusIndexer.upsert(knowledgeBaseId, knowledgeDocument, chunks);

            markCompleted(knowledgeDocument);
            log.info("Knowledge document ingestion finished: knowledgeBaseId={}, documentId={}, chunkCount={}, totalDurationMs={}",
                    knowledgeBaseId,
                    documentId,
                    chunks.size(),
                    (System.nanoTime() - ingestionStart) / 1_000_000);
        } catch (Exception e) {
            log.warn("Knowledge document ingestion failed: knowledgeBaseId={}, documentId={}, error={}",
                    knowledgeBaseId, documentId, e.getMessage());
            knowledgeChunkRepository.deleteByKnowledgeDocumentId(documentId);
            knowledgeBaseMilvusIndexer.deleteByKnowledgeDocumentId(documentId);
            markFailure(knowledgeDocument, e.getMessage());
        }
    }

    private boolean supportsIngestion(String fileExtension) {
        return supportsMarkdownIngestion(fileExtension) || supportsGenericIngestion(fileExtension);
    }

    private boolean supportsMarkdownIngestion(String fileExtension) {
        return "md".equals(fileExtension) || "markdown".equals(fileExtension);
    }

    private boolean supportsGenericIngestion(String fileExtension) {
        return Set.of("txt", "pdf", "doc", "docx").contains(fileExtension);
    }

    private String parseDocument(byte[] rawBytes, KnowledgeDocumentDTO knowledgeDocument, String fileExtension) {
        if (supportsMarkdownIngestion(fileExtension)) {
            DocumentParser parser = documentParserSelector.select(ParserType.MARKDOWN.getType());
            if (parser == null) {
                throw new IllegalStateException("Markdown parser is not configured");
            }
            ParseResult result = parser.parse(rawBytes, "text/markdown", Map.of());
            return result.text();
        }

        DocumentParser parser = documentParserSelector.select(ParserType.TIKA.getType());
        if (parser == null) {
            throw new IllegalStateException("Tika parser is not configured");
        }

        String mimeType = knowledgeDocument.getMimeType();
        if (!StringUtils.hasText(mimeType)) {
            mimeType = "application/octet-stream";
        }
        ParseResult result = parser.parse(rawBytes, mimeType, Map.of());
        return result.text();
    }

    private List<KnowledgeChunkDraft> chunkDocument(String fileExtension, String enhancedText, String rawText) {
        String sourceText = StringUtils.hasText(enhancedText) ? enhancedText : rawText;
        if (!StringUtils.hasText(sourceText)) {
            throw new IllegalStateException("Parsed knowledge document text is empty");
        }
        if (supportsMarkdownIngestion(fileExtension)) {
            return structureAwareMarkdownChunker.chunk(sourceText);
        }
        return plainTextChunker.chunk(sourceText);
    }

    private FileIngestionContext buildEnrichmentContext(String fileExtension, String rawText, String enhancedText) {
        return FileIngestionContext.builder()
                .fileExtension(fileExtension)
                .rawText(rawText)
                .enhancedText(enhancedText)
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

    private void markSkipped(KnowledgeDocumentDTO knowledgeDocument) {
        knowledgeDocument.setParseStatus("SKIPPED");
        knowledgeDocument.setFailedReason(null);
        knowledgeDocument.setUpdatedAt(LocalDateTime.now());
        knowledgeDocumentRepository.update(knowledgeDocument);
    }

    private void markCompleted(KnowledgeDocumentDTO knowledgeDocument) {
        knowledgeDocument.setParseStatus("COMPLETED");
        knowledgeDocument.setFailedReason(null);
        knowledgeDocument.setIndexedAt(LocalDateTime.now());
        knowledgeDocument.setUpdatedAt(LocalDateTime.now());
        knowledgeDocumentRepository.update(knowledgeDocument);
    }

    private void markFailure(KnowledgeDocumentDTO knowledgeDocument, String errorMessage) {
        knowledgeDocument.setParseStatus("FAILED");
        knowledgeDocument.setFailedReason(errorMessage);
        knowledgeDocument.setRetryCount((knowledgeDocument.getRetryCount() == null ? 0 : knowledgeDocument.getRetryCount()) + 1);
        knowledgeDocument.setUpdatedAt(LocalDateTime.now());
        knowledgeDocumentRepository.update(knowledgeDocument);
    }

    private String getFileExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "";
        }
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
    }
}
