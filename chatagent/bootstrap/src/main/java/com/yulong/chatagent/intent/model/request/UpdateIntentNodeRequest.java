package com.yulong.chatagent.intent.model.request;

import com.yulong.chatagent.intent.model.IntentKind;
import com.yulong.chatagent.intent.model.IntentNodeLevel;
import com.yulong.chatagent.intent.model.ScopePolicy;
import lombok.Data;

import java.util.List;

/**
 * Payload for updating one draft intent node.
 */
@Data
public class UpdateIntentNodeRequest {
    private String parentId;
    private IntentNodeLevel nodeLevel;
    private String name;
    private String description;
    private List<String> examples;
    private IntentKind intentKind;
    private ScopePolicy scopePolicy;
    private List<String> allowedTools;
    private String systemPromptOverride;
    private Boolean enabled;
    private Integer sortOrder;
}
