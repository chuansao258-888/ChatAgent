package com.yulong.chatagent.rag.ingestion.model;

import com.yulong.chatagent.support.dto.ChatSessionFileDTO;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.SuperBuilder;

/**
 * Future segment-native context for session-file ingestion.
 */
@Data
@SuperBuilder
@EqualsAndHashCode(callSuper = true)
public class SessionIngestionContext extends BaseIngestionContext {

    private String sessionId;
    private ChatSessionFileDTO sessionFile;
}
