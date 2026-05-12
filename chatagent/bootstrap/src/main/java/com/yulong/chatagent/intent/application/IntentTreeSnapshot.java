package com.yulong.chatagent.intent.application;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.yulong.chatagent.support.dto.IntentNodeDTO;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Active intent tree snapshot loaded for one assistant version.
 */
@Data
@NoArgsConstructor
public class IntentTreeSnapshot {
    private String agentId;
    private Integer version;
    private List<IntentNodeDTO> nodes;
    private Map<String, List<String>> knowledgeBaseIdsByNodeId;

    /**
     * 运行时派生索引，不参与 Redis/JSON 序列化。
     * <p>
     * snapshot 的持久化形态仍然是扁平 nodes 列表；这些 Map 只是为了避免路由时反复 stream 扫全表。
     */
    @JsonIgnore
    private transient volatile Map<String, IntentNodeDTO> nodeById;
    @JsonIgnore
    private transient volatile Map<String, List<IntentNodeDTO>> childrenByParentId;

    public IntentTreeSnapshot(String agentId,
                              Integer version,
                              List<IntentNodeDTO> nodes,
                              Map<String, List<String>> knowledgeBaseIdsByNodeId) {
        this.agentId = agentId;
        this.version = version;
        this.nodes = nodes;
        this.knowledgeBaseIdsByNodeId = knowledgeBaseIdsByNodeId;
    }

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
        ensureIndexes();
        return childrenByParentId.getOrDefault(emptyToNull(parentId), List.of());
    }

    public IntentNodeDTO findNode(String nodeId) {
        if (nodeId == null) {
            return null;
        }
        ensureIndexes();
        return nodeById.get(nodeId);
    }

    public List<IntentNodeDTO> pathTo(String nodeId) {
        IntentNodeDTO target = findNode(nodeId);
        if (target == null) {
            return List.of();
        }
        List<IntentNodeDTO> path = new ArrayList<>();
        IntentNodeDTO cursor = target;
        while (cursor != null) {
            path.add(0, cursor);
            cursor = nodeById.get(cursor.getParentId());
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

    public void setNodes(List<IntentNodeDTO> nodes) {
        this.nodes = nodes;
        clearIndexes();
    }

    private void clearIndexes() {
        this.nodeById = null;
        this.childrenByParentId = null;
    }

    private void ensureIndexes() {
        if (nodeById != null && childrenByParentId != null) {
            return;
        }
        synchronized (this) {
            if (nodeById != null && childrenByParentId != null) {
                return;
            }

            Map<String, IntentNodeDTO> byId = new LinkedHashMap<>();
            Map<String, List<IntentNodeDTO>> children = new LinkedHashMap<>();
            if (nodes != null) {
                for (IntentNodeDTO node : nodes) {
                    if (node == null) {
                        continue;
                    }
                    if (node.getId() != null) {
                        byId.put(node.getId(), node);
                    }
                    children.computeIfAbsent(emptyToNull(node.getParentId()), ignored -> new ArrayList<>())
                            .add(node);
                }
            }

            children.replaceAll((ignored, childNodes) -> childNodes.stream()
                    .sorted(Comparator
                            .comparing((IntentNodeDTO node) -> node.getSortOrder() == null ? 0 : node.getSortOrder())
                            .thenComparing(IntentNodeDTO::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                    .toList());
            this.nodeById = byId;
            this.childrenByParentId = children;
        }
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
