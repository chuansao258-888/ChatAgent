package com.yulong.chatagent.intent.application;

import com.yulong.chatagent.agent.runtime.AgentExecutionMode;
import com.yulong.chatagent.support.dto.ChatMessageDTO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TurnPreparationContextAssemblerTest {

    @Test
    void shouldExcludeCurrentInternalSystemAndToolMessages() {
        IntentPolicyProperties properties = new IntentPolicyProperties();
        TurnPreparationContextAssembler assembler = new TurnPreparationContextAssembler(properties);
        List<ChatMessageDTO> history = List.of(
                message("u1", "t1", 1L, ChatMessageDTO.RoleType.USER, "visible question", false, true),
                message("a1", "t1", 2L, ChatMessageDTO.RoleType.ASSISTANT, "visible answer", false, true),
                message("i1", "t1", 3L, ChatMessageDTO.RoleType.ASSISTANT, "private trace", true, true),
                message("s1", "t0", 4L, ChatMessageDTO.RoleType.SYSTEM, "system prompt", false, true),
                message("tool", "t0", 5L, ChatMessageDTO.RoleType.TOOL, "raw tool result", false, true),
                message("current", "t2", 6L, ChatMessageDTO.RoleType.USER, "current input", false, false)
        );

        TurnPreparationContext context = assembler.assemble(
                "agent", "session", "current input", "current", history, "assets", AgentExecutionMode.REACT);

        assertThat(context.recentVisibleTurns()).extracting(IntentUnderstandingRequest.RecentTurn::text)
                .containsExactly("visible question", "visible answer")
                .doesNotContain("private trace", "system prompt", "raw tool result", "current input");
    }

    @Test
    void shouldKeepNewestConfiguredTurnsAndTruncateOldestCharacters() {
        IntentPolicyProperties properties = new IntentPolicyProperties();
        properties.setRecentContextTurns(2);
        properties.setRecentContextMaxChars(12);
        TurnPreparationContextAssembler assembler = new TurnPreparationContextAssembler(properties);
        List<ChatMessageDTO> history = List.of(
                message("u1", "t1", 1L, ChatMessageDTO.RoleType.USER, "old-old", false, true),
                message("a1", "t1", 2L, ChatMessageDTO.RoleType.ASSISTANT, "old-answer", false, true),
                message("u2", "t2", 3L, ChatMessageDTO.RoleType.USER, "middle", false, true),
                message("a2", "t2", 4L, ChatMessageDTO.RoleType.ASSISTANT, "answer", false, true),
                message("u3", "t3", 5L, ChatMessageDTO.RoleType.USER, "newest", false, true),
                message("a3", "t3", 6L, ChatMessageDTO.RoleType.ASSISTANT, "reply", false, true)
        );

        TurnPreparationContext context = assembler.assemble(
                "agent", "session", "next", "current", history, null, AgentExecutionMode.REACT);

        assertThat(context.contextTruncated()).isTrue();
        assertThat(context.recentVisibleTurns()).extracting(IntentUnderstandingRequest.RecentTurn::text)
                .doesNotContain("old-old", "old-answer");
        assertThat(context.recentVisibleTurns().stream().mapToInt(turn -> turn.text().length()).sum())
                .isLessThanOrEqualTo(12);
    }

    private ChatMessageDTO message(String id, String turnId, Long seq,
                                   ChatMessageDTO.RoleType role, String content,
                                   boolean internal, boolean completed) {
        return ChatMessageDTO.builder()
                .id(id).turnId(turnId).seqNo(seq).role(role).content(content)
                .turnCompleted(completed)
                .metadata(ChatMessageDTO.MetaData.builder().internal(internal).build())
                .build();
    }
}
