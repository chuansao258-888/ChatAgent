package com.yulong.chatagent.agent.runtime;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Runtime-only deterministic compactor for oversized tool responses.
 *
 * <p>Applied during L1 memory loading to reduce context size without
 * mutating persisted {@code chat_message} rows. Only affects the derived
 * Spring AI message list returned to the model.
 *
 * <p>Two trigger paths:
 * <ul>
 *     <li>Char threshold: content exceeds {@code tool-result-max-chars}</li>
 *     <li>Budget pressure: the L1 turn would exceed the effective token budget</li>
 * </ul>
 *
 * <p>Compact format:
 * <pre>
 * [Tool result compacted for context budget]
 * Original chars: 15000
 * Head:
 * &lt;first N chars&gt;
 * Tail:
 * &lt;last N chars&gt;
 * </pre>
 */
@Component
@Slf4j
public class ToolResultCompactor {

    private static final String COMPACTED_HEADER = "[Tool result compacted for context budget]";
    private static final String METRIC_TOOL_RESULTS_COMPACTED = "chatagent.memory.compaction.v2.tool_results_compacted";
    private static final int BUDGET_PRESSURE_EXCERPT_CHARS = 10;

    private final int maxChars;
    private final int headChars;
    private final int tailChars;
    private final MeterRegistry meterRegistry;

    public ToolResultCompactor(
            @Value("${chatagent.memory.compaction.v2.tool-result-max-chars:2000}") int maxChars,
            @Value("${chatagent.memory.compaction.v2.tool-result-head-chars:800}") int headChars,
            @Value("${chatagent.memory.compaction.v2.tool-result-tail-chars:800}") int tailChars,
            ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.maxChars = Math.max(maxChars, 200);
        this.headChars = Math.max(headChars, 100);
        this.tailChars = Math.max(tailChars, 100);
        this.meterRegistry = meterRegistryProvider.getIfAvailable();
    }

    /**
     * Returns compacted content if it exceeds maxChars, otherwise returns original.
     *
     * @param content the tool response text
     * @return compacted format or original content
     */
    public String compactIfNeeded(String content) {
        if (content == null || content.length() <= maxChars) {
            return content;
        }
        return doCompact(content, headChars, tailChars);
    }

    /**
     * Force-compacts content for L1 token budget pressure, regardless of maxChars threshold.
     * Used when a turn would exceed the effective token budget even though individual
     * tool responses are below the char threshold.
     *
     * @param content the tool response text
     * @return compacted format or original if content is null/empty
     */
    public String compactForBudget(String content) {
        if (!StringUtils.hasText(content)) {
            return content;
        }
        // Budget-pressure compaction must be materially smaller than threshold compaction.
        int budgetHead = Math.min(headChars, BUDGET_PRESSURE_EXCERPT_CHARS);
        int budgetTail = Math.min(tailChars, BUDGET_PRESSURE_EXCERPT_CHARS);
        String compacted = doCompact(content, budgetHead, budgetTail);
        return compacted.length() < content.length() ? compacted : content;
    }

    /**
     * @return true if the content exceeds the char-based compaction threshold
     */
    public boolean shouldCompact(String content) {
        return StringUtils.hasText(content) && content.length() > maxChars;
    }

    private String doCompact(String content, int preferredHead, int preferredTail) {
        String prefix = COMPACTED_HEADER + "\n"
                + "Original chars: " + content.length() + "\n"
                + "Head:\n";
        String separator = "\nTail:\n";

        int requestedExcerptChars = Math.max(preferredHead, 0) + Math.max(preferredTail, 0);
        int maxExcerptChars = Math.max(content.length() - prefix.length() - separator.length() - 1, 0);
        int totalExcerptChars = Math.min(requestedExcerptChars, maxExcerptChars);

        int head = 0;
        int tail = 0;
        if (totalExcerptChars > 0) {
            if (requestedExcerptChars == 0) {
                head = totalExcerptChars / 2;
            } else {
                head = (int) Math.floor((double) totalExcerptChars * Math.max(preferredHead, 0)
                        / requestedExcerptChars);
            }
            tail = totalExcerptChars - head;
        }

        String headText = content.substring(0, head);
        String tailText = content.substring(content.length() - tail);

        // Try to preserve error/significant lines that fell outside head/tail excerpts.
        // Scans the dropped middle region for lines starting with common error indicators
        // and appends up to 3 such lines between head and tail when budget allows.
        int droppedStart = head;
        int droppedEnd = content.length() - tail;
        String middleLines = extractErrorLines(content, droppedStart, droppedEnd, totalExcerptChars);

        String compacted = prefix + headText + middleLines + separator + tailText;

        // If error-line extraction made the result longer than the original, drop them.
        if (compacted.length() >= content.length()) {
            compacted = prefix + headText + separator + tailText;
        }

        // Record metric
        if (meterRegistry != null) {
            meterRegistry.counter(METRIC_TOOL_RESULTS_COMPACTED).increment();
        }

        log.debug("Compacted tool result: originalChars={}, compactedChars={}",
                content.length(), compacted.length());

        return compacted;
    }

    /**
     * Extracts up to 3 lines from the dropped middle region that appear to contain
     * error output, stack traces, file paths, URLs, or result counts.
     * Returns a formatted block or empty string when no significant lines are found.
     */
    private String extractErrorLines(String content, int droppedStart, int droppedEnd, int budgetChars) {
        if (droppedEnd <= droppedStart) {
            return "";
        }
        int remaining = budgetChars / 4; // use up to 25% of excerpt budget for error lines
        if (remaining < 20) {
            return ""; // not enough budget to be useful
        }

        String dropped = content.substring(droppedStart, droppedEnd);
        StringBuilder errorLines = new StringBuilder();
        int count = 0;
        for (String line : dropped.split("\n")) {
            if (count >= 3) {
                break;
            }
            if (isErrorLine(line)) {
                if (errorLines.length() + line.length() + 1 > remaining) {
                    break;
                }
                if (errorLines.isEmpty()) {
                    errorLines.append("\n...\n");
                }
                errorLines.append(line).append('\n');
                count++;
            }
        }
        return errorLines.toString();
    }

    private static boolean isErrorLine(String line) {
        if (line.isBlank()) {
            return false;
        }
        String trimmed = line.trim();
        return trimmed.startsWith("Error") || trimmed.startsWith("error")
                || trimmed.startsWith("Exception") || trimmed.startsWith("Caused by")
                || trimmed.startsWith("at ")               // stack trace frames
                || trimmed.startsWith("FAIL") || trimmed.startsWith("Timeout")
                || trimmed.startsWith("http://") || trimmed.startsWith("https://")
                || trimmed.startsWith("/") && (trimmed.contains("/") || trimmed.endsWith(".log"))
                    && trimmed.length() < 200               // likely a file path
                || trimmed.startsWith("Result") || trimmed.startsWith("result")
                || trimmed.startsWith("rows affected")
                || trimmed.startsWith("status:") || trimmed.startsWith("Status:");
    }
}
