package com.yulong.chatagent.intent.application;

import com.yulong.chatagent.support.dto.IntentNodeDTO;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Active intent tree snapshot loaded for one assistant version.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class IntentTreeSnapshot {
    private String agentId;
    private Integer version;
    private List<IntentNodeDTO> nodes;
    private Map<String, List<String>> knowledgeBaseIdsByNodeId;

    public static IntentTreeSnapshot empty(String agentId) {
        return new IntentTreeSnapshot(agentId, null, List.of(), Map.of());
    }

    public boolean isEmpty() {
        return nodes == null || nodes.isEmpty();
    }

    public List<IntentNodeDTO> rootNodes() {
        return childrenOf(null);
    }

    public List<IntentNodeDTO> childrenOf(String parentId) {
        if (nodes == null || nodes.isEmpty()) {
            return List.of();
        }
        return nodes.stream()
                .filter(node -> Objects.equals(emptyToNull(node.getParentId()), emptyToNull(parentId)))
                .sorted(Comparator
                        .comparing((IntentNodeDTO node) -> node.getSortOrder() == null ? 0 : node.getSortOrder())
                        .thenComparing(IntentNodeDTO::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    public IntentNodeDTO findNode(String nodeId) {
        if (nodeId == null || nodes == null) {
            return null;
        }
        for (IntentNodeDTO node : nodes) {
            if (nodeId.equals(node.getId())) {
                return node;
            }
        }
        return null;
    }

    public List<IntentNodeDTO> pathTo(String nodeId) {
        IntentNodeDTO target = findNode(nodeId);
        if (target == null) {
            return List.of();
        }
        Map<String, IntentNodeDTO> byId = new LinkedHashMap<>();
        for (IntentNodeDTO node : nodes) {
            byId.put(node.getId(), node);
        }
        List<IntentNodeDTO> path = new ArrayList<>();
        IntentNodeDTO cursor = target;
        while (cursor != null) {
            path.add(0, cursor);
            cursor = byId.get(cursor.getParentId());
        }
        return path;
    }

    public List<String> knowledgeBaseIdsForNode(String nodeId) {
        if (knowledgeBaseIdsByNodeId == null || nodeId == null) {
            return List.of();
        }
        List<String> ids = knowledgeBaseIdsByNodeId.get(nodeId);
        return ids == null ? List.of() : List.copyOf(ids);
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}

