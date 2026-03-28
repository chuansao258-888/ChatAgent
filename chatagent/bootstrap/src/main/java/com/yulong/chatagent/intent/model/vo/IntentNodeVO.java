package com.yulong.chatagent.intent.model.vo;

import com.yulong.chatagent.intent.model.IntentKind;
import com.yulong.chatagent.intent.model.IntentNodeLevel;
import com.yulong.chatagent.intent.model.IntentNodeStatus;
import com.yulong.chatagent.intent.model.ScopePolicy;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Admin-facing representation of one intent node with bound knowledge bases.
 */
@Data
@Builder
public class IntentNodeVO {
    private String id;
    private String parentId;
    private Integer version;
    private IntentNodeStatus status;
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
    private List<String> knowledgeBaseIds;
}
