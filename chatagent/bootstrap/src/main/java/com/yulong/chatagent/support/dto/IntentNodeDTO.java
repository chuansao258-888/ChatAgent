package com.yulong.chatagent.support.dto;

import com.yulong.chatagent.intent.model.IntentKind;
import com.yulong.chatagent.intent.model.IntentNodeLevel;
import com.yulong.chatagent.intent.model.IntentNodeStatus;
import com.yulong.chatagent.intent.model.ScopePolicy;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * DTO representing one persisted intent tree node.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IntentNodeDTO {
    private String id;
    private String agentId;
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
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
