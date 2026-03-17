package com.yulong.chatagent.rag.ingestion;

import com.yulong.chatagent.rag.ingestion.model.KnowledgeChunkDraft;
import com.yulong.chatagent.rag.service.MarkdownParserService;
import com.yulong.chatagent.trace.TraceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

@Component
@Slf4j
public class MarkdownIngestionPipeline {

    private final MarkdownSectionReader sectionReader;
    private final MarkdownSectionChunker sectionChunker;
    private final KnowledgeChunkIndexer chunkIndexer;

    public MarkdownIngestionPipeline(MarkdownSectionReader sectionReader,
                                     MarkdownSectionChunker sectionChunker,
                                     KnowledgeChunkIndexer chunkIndexer) {
        this.sectionReader = sectionReader;
        this.sectionChunker = sectionChunker;
        this.chunkIndexer = chunkIndexer;
    }

    public int ingest(String kbId, String documentId, String filePath) throws IOException {
        long startTime = System.nanoTime();
        List<MarkdownParserService.MarkdownSection> sections = sectionReader.read(filePath);
        List<KnowledgeChunkDraft> drafts = sectionChunker.chunk(sections);
        int chunkCount = chunkIndexer.index(kbId, documentId, drafts);
        long durationMs = (System.nanoTime() - startTime) / 1_000_000;
        log.info("Markdown ingestion pipeline completed: traceId={}, kbId={}, documentId={}, sections={}, chunks={}, durationMs={}",
                TraceContext.getTraceId(), kbId, documentId, sections.size(), chunkCount, durationMs);
        return chunkCount;
    }
}
