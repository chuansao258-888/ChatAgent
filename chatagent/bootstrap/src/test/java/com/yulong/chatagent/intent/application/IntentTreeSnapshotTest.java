package com.yulong.chatagent.intent.application;

import com.yulong.chatagent.support.dto.IntentNodeDTO;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IntentTreeSnapshotTest {

    @Test
    void shouldResolveRootsChildrenNodeAndPathFromRuntimeIndexes() {
        IntentTreeSnapshot snapshot = new IntentTreeSnapshot(
                "assistant-1",
                1,
                List.of(
                        node("finance", null, "财务", 2),
                        node("hr", null, "人事", 1),
                        node("invoice", "finance", "发票", 2),
                        node("reimburse", "finance", "报销", 1),
                        node("travel", "reimburse", "差旅报销", 1)
                ),
                Map.of("travel", List.of("kb-travel"))
        );

        assertThat(snapshot.rootNodes())
                .extracting(IntentNodeDTO::getId)
                .containsExactly("hr", "finance");
        assertThat(snapshot.childrenOf("finance"))
                .extracting(IntentNodeDTO::getId)
                .containsExactly("reimburse", "invoice");
        assertThat(snapshot.findNode("travel").getName()).isEqualTo("差旅报销");
        assertThat(snapshot.pathTo("travel"))
                .extracting(IntentNodeDTO::getId)
                .containsExactly("finance", "reimburse", "travel");
        assertThat(snapshot.knowledgeBaseIdsForNode("travel")).containsExactly("kb-travel");
    }

    @Test
    void shouldRebuildIndexesWhenNodesAreReplaced() {
        IntentTreeSnapshot snapshot = new IntentTreeSnapshot(
                "assistant-1",
                1,
                List.of(node("old-root", null, "旧根节点", 1)),
                Map.of()
        );

        assertThat(snapshot.findNode("old-root")).isNotNull();

        snapshot.setNodes(List.of(node("new-root", null, "新根节点", 1)));

        assertThat(snapshot.findNode("old-root")).isNull();
        assertThat(snapshot.rootNodes())
                .extracting(IntentNodeDTO::getId)
                .containsExactly("new-root");
    }

    private IntentNodeDTO node(String id, String parentId, String name, int sortOrder) {
        return IntentNodeDTO.builder()
                .id(id)
                .parentId(parentId)
                .name(name)
                .sortOrder(sortOrder)
                .createdAt(LocalDateTime.of(2026, 1, sortOrder, 0, 0))
                .build();
    }
}
