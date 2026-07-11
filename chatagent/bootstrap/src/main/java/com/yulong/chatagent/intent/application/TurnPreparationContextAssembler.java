package com.yulong.chatagent.intent.application;

import com.yulong.chatagent.agent.runtime.AgentExecutionMode;
import com.yulong.chatagent.support.dto.ChatMessageDTO;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Filters and bounds the already-loaded conversation history without another repository query. */
@Component
public class TurnPreparationContextAssembler {

    private final IntentPolicyProperties properties;

    public TurnPreparationContextAssembler(IntentPolicyProperties properties) {
        this.properties = properties;
    }

    public TurnPreparationContext assemble(String agentId,
                                           String sessionId,
                                           String userInput,
                                           String currentUserMessageId,
                                           List<ChatMessageDTO> recentHistory,
                                           String sessionAssetSummary,
                                           AgentExecutionMode executionMode) {
        boolean available = recentHistory != null;
        List<ChatMessageDTO> visible = visibleCompletedHistory(recentHistory, currentUserMessageId);
        int maximumTurns = properties.boundedRecentContextTurns();
        Set<String> retainedTurnKeys = newestTurnKeys(visible, maximumTurns);
        List<IntentUnderstandingRequest.RecentTurn> retained = new ArrayList<>();
        for (ChatMessageDTO message : visible) {
            if (retainedTurnKeys.contains(turnKey(message))) {
                retained.add(new IntentUnderstandingRequest.RecentTurn(
                        message.getRole().getRole(), message.getContent().trim()));
            }
        }
        BoundedTurns bounded = boundCharacters(retained, properties.boundedRecentContextMaxChars());
        boolean truncated = bounded.truncated() || retainedTurnKeys.size() < distinctTurnCount(visible);
        return new TurnPreparationContext(
                agentId, sessionId, userInput, bounded.turns(), available, truncated,
                sessionAssetSummary, executionMode);
    }

    private List<ChatMessageDTO> visibleCompletedHistory(List<ChatMessageDTO> history,
                                                        String currentUserMessageId) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        List<ChatMessageDTO> ordered = history.stream()
                .filter(message -> message != null && message.getRole() != null)
                .filter(message -> message.getRole() == ChatMessageDTO.RoleType.USER
                        || message.getRole() == ChatMessageDTO.RoleType.ASSISTANT)
                .filter(message -> !isInternal(message))
                .filter(message -> !Objects.equals(message.getId(), currentUserMessageId))
                .filter(message -> StringUtils.hasText(message.getContent()))
                .sorted(Comparator
                        .comparing(ChatMessageDTO::getSeqNo, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(ChatMessageDTO::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        Map<String, List<ChatMessageDTO>> byTurn = new LinkedHashMap<>();
        ordered.forEach(message -> byTurn.computeIfAbsent(turnKey(message), ignored -> new ArrayList<>()).add(message));
        Set<String> completed = new LinkedHashSet<>();
        byTurn.forEach((key, messages) -> {
            boolean done = messages.stream().anyMatch(message -> Boolean.TRUE.equals(message.getTurnCompleted()))
                    || messages.stream().anyMatch(message -> message.getRole() == ChatMessageDTO.RoleType.ASSISTANT);
            if (done) {
                completed.add(key);
            }
        });
        return ordered.stream().filter(message -> completed.contains(turnKey(message))).toList();
    }

    private Set<String> newestTurnKeys(List<ChatMessageDTO> messages, int maximumTurns) {
        if (maximumTurns <= 0) {
            return Set.of();
        }
        List<String> orderedKeys = new ArrayList<>();
        for (ChatMessageDTO message : messages) {
            String key = turnKey(message);
            if (!orderedKeys.contains(key)) {
                orderedKeys.add(key);
            }
        }
        int start = Math.max(orderedKeys.size() - maximumTurns, 0);
        return new LinkedHashSet<>(orderedKeys.subList(start, orderedKeys.size()));
    }

    private BoundedTurns boundCharacters(List<IntentUnderstandingRequest.RecentTurn> turns, int maximumChars) {
        if (turns.isEmpty() || maximumChars <= 0) {
            return new BoundedTurns(List.of(), !turns.isEmpty());
        }
        int total = turns.stream().mapToInt(turn -> turn.text().length()).sum();
        if (total <= maximumChars) {
            return new BoundedTurns(List.copyOf(turns), false);
        }
        List<IntentUnderstandingRequest.RecentTurn> newest = new ArrayList<>();
        int remaining = maximumChars;
        for (int i = turns.size() - 1; i >= 0 && remaining > 0; i--) {
            IntentUnderstandingRequest.RecentTurn turn = turns.get(i);
            String text = turn.text();
            if (text.length() > remaining) {
                text = text.substring(text.length() - remaining);
            }
            newest.add(0, new IntentUnderstandingRequest.RecentTurn(turn.role(), text));
            remaining -= text.length();
        }
        return new BoundedTurns(List.copyOf(newest), true);
    }

    private int distinctTurnCount(List<ChatMessageDTO> messages) {
        return (int) messages.stream().map(this::turnKey).distinct().count();
    }

    private String turnKey(ChatMessageDTO message) {
        if (StringUtils.hasText(message.getTurnId())) {
            return message.getTurnId();
        }
        if (message.getTurnSeq() != null) {
            return "seq:" + message.getTurnSeq();
        }
        return "message:" + message.getId();
    }

    private boolean isInternal(ChatMessageDTO message) {
        return message.getMetadata() != null && Boolean.TRUE.equals(message.getMetadata().getInternal());
    }

    private record BoundedTurns(List<IntentUnderstandingRequest.RecentTurn> turns, boolean truncated) {
    }
}
