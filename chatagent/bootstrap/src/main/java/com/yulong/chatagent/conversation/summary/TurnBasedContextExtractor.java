package com.yulong.chatagent.conversation.summary;

import com.yulong.chatagent.conversation.port.ChatMessageRepository;
import com.yulong.chatagent.support.dto.ChatMessageDTO;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于 turnId 的原子轮次提取器。
 * <p>
 * 摘要系统不直接以“消息列表”作为输入，而是先把消息整理成一个个完整 turn：
 * <ul>
 *     <li>同一个 turn 下的多条 USER / ASSISTANT 消息会被聚合；</li>
 *     <li>工具调用噪音会被过滤；</li>
 *     <li>最终得到更适合做会话摘要的 {@link AtomicConversationTurn}。</li>
 * </ul>
 */
@Component
public class TurnBasedContextExtractor {

    private final SummaryWatermarkService summaryWatermarkService;
    private final ChatMessageRepository chatMessageRepository;

    public TurnBasedContextExtractor(SummaryWatermarkService summaryWatermarkService,
                                     ChatMessageRepository chatMessageRepository) {
        this.summaryWatermarkService = summaryWatermarkService;
        this.chatMessageRepository = chatMessageRepository;
    }

    public long countTurns(String sessionId) {
        // 按 turn 粒度判断是否超过 L1 窗口，而不是按消息条数判断。
        return chatMessageRepository.countTurnsBySessionId(sessionId);
    }

    public List<AtomicConversationTurn> extractPendingTurns(String sessionId, long anchorSeqNo) {
        List<ChatMessageDTO> pendingMessages = summaryWatermarkService.loadPendingMessages(sessionId, anchorSeqNo);
        if (pendingMessages.isEmpty()) {
            return List.of();
        }

        Map<String, List<ChatMessageDTO>> grouped = new LinkedHashMap<>();
        for (ChatMessageDTO message : pendingMessages) {
            if (!StringUtils.hasText(message.getTurnId())) {
                // 没有 turnId 的消息无法稳定归属到某一轮，摘要链直接忽略。
                continue;
            }
            // 用 LinkedHashMap 保留数据库扫描顺序，让后续 turn 顺序与原会话顺序一致。
            grouped.computeIfAbsent(message.getTurnId(), ignored -> new ArrayList<>()).add(message);
        }

        List<AtomicConversationTurn> turns = new ArrayList<>();
        for (Map.Entry<String, List<ChatMessageDTO>> entry : grouped.entrySet()) {
            AtomicConversationTurn turn = toTurn(entry.getKey(), entry.getValue());
            if (turn != null && turn.hasSummarizableContent()) {
                turns.add(turn);
            }
        }
        return turns;
    }

    private AtomicConversationTurn toTurn(String turnId, List<ChatMessageDTO> messages) {
        if (messages == null || messages.isEmpty()) {
            return null;
        }

        long startSeqNo = messages.get(0).getSeqNo() == null ? 0L : messages.get(0).getSeqNo();
        long endSeqNo = startSeqNo;
        List<String> userMessages = new ArrayList<>();
        String assistantConclusion = null;

        for (ChatMessageDTO message : messages) {
            if (message.getSeqNo() != null) {
                endSeqNo = Math.max(endSeqNo, message.getSeqNo());
            }
            if (message.getRole() == ChatMessageDTO.RoleType.USER && StringUtils.hasText(message.getContent())) {
                // 用户消息全部保留，它们是 L2 摘要的重要原始语义来源。
                userMessages.add(message.getContent().trim());
                continue;
            }
            if (message.getRole() == ChatMessageDTO.RoleType.ASSISTANT
                    && StringUtils.hasText(message.getContent())
                    && !hasToolCalls(message)) {
                // assistant 只有在“不含 toolCalls”时才被视为最终结论。
                // 否则它更像工具决策中间态，不适合直接写进摘要。
                assistantConclusion = message.getContent().trim();
            }
        }

        return new AtomicConversationTurn(turnId, startSeqNo, endSeqNo, List.copyOf(userMessages), assistantConclusion);
    }

    private boolean hasToolCalls(ChatMessageDTO message) {
        // 只要 metadata 里还带 toolCalls，就说明这条 assistant 更偏向“工具决策消息”而非最终结论。
        ChatMessageDTO.MetaData metadata = message.getMetadata();
        return metadata != null && metadata.getToolCalls() != null && !metadata.getToolCalls().isEmpty();
    }
}
