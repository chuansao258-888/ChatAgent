package com.yulong.chatagent.intent.model.response;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Returned after publishing one new intent snapshot version.
 */
@Data
@AllArgsConstructor
public class PublishIntentTreeResponse {
    private Integer version;
}
