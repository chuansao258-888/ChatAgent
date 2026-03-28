package com.yulong.chatagent.intent.model.request;

import lombok.Data;

import java.util.List;

/**
 * Payload for replacing one draft node's knowledge-base bindings.
 */
@Data
public class SetIntentNodeKnowledgeBasesRequest {
    private List<String> knowledgeBaseIds;
}
