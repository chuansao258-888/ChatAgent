package com.yulong.chatagent.conversation.summary;

/**
 * Decision from {@link MemoryCompactionPolicy} on whether to run compaction.
 *
 * @param shouldCompact whether compaction should proceed
 * @param trigger       the reason for the decision
 */
public record CompactionDecision(
        boolean shouldCompact,
        CompactionTrigger trigger
) {
}
