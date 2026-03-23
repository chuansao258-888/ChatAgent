package com.yulong.chatagent.rag.ingestion.model;

import com.yulong.chatagent.support.dto.ChatSessionFileDTO;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
/**
 * Mutable state carried across the file ingestion pipeline.
 */
public class FileIngestionContext {

    private String sessionId;
    private ChatSessionFileDTO sessionFile;
    private String fileExtension;
    private byte[] rawBytes;
    private String rawText;
    private String enhancedText;
    private List<KnowledgeChunkDraft> chunkDrafts;
}
