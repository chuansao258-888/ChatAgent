package com.yulong.chatagent.intent.model.response;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Returned after creating one draft intent node.
 */
@Data
@AllArgsConstructor
public class CreateIntentNodeResponse {
    private String nodeId;
}
