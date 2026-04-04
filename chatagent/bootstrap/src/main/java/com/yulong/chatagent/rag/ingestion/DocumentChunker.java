package com.yulong.chatagent.rag.ingestion;

import com.yulong.chatagent.rag.ingestion.model.KnowledgeChunkDraft;
import com.yulong.chatagent.rag.parser.ParseSegment;

import java.util.List;

/**
 * Segment-aware chunking contract introduced in Phase 2.
 */
public interface DocumentChunker {

    List<KnowledgeChunkDraft> chunk(List<ParseSegment> segments);
}
