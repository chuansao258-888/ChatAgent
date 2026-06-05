package com.yulong.chatagent.agent.runtime;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolResultCompactorTest {

    private SimpleMeterRegistry meterRegistry;
    private ToolResultCompactor compactor;

    @SuppressWarnings("unchecked")
    private ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meterProvider() {
        ObjectProvider<io.micrometer.core.instrument.MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(meterRegistry);
        return provider;
    }

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        compactor = new ToolResultCompactor(200, 80, 80, meterProvider());
    }

    @Test
    void shouldNotCompactSmallContent() {
        String content = "Short tool result.";

        String result = compactor.compactIfNeeded(content);

        assertThat(result).isSameAs(content);
    }

    @Test
    void shouldCompactOversizedContent() {
        String content = "A".repeat(500);

        String result = compactor.compactIfNeeded(content);

        assertThat(result).startsWith("[Tool result compacted for context budget]");
        assertThat(result).contains("Original chars: 500");
        assertThat(result).contains("Head:\n" + "A".repeat(100));
        assertThat(result).contains("Tail:\n" + "A".repeat(100));
    }

    @Test
    void shouldReturnOriginalWhenExactlyAtThreshold() {
        String content = "A".repeat(200);

        String result = compactor.compactIfNeeded(content);

        assertThat(result).isSameAs(content);
    }

    @Test
    void shouldReturnNullForNull() {
        assertThat(compactor.compactIfNeeded(null)).isNull();
    }

    @Test
    void shouldReturnOriginalForEmptyString() {
        assertThat(compactor.compactIfNeeded("")).isEqualTo("");
    }

    @Test
    void shouldProduceHeadAndTailFromDifferentParts() {
        // Config: maxChars=200, headChars=max(80,100)=100, tailChars=max(80,100)=100
        // Content must be large enough that head (100) + tail (100) don't overlap
        String head = "H".repeat(100);
        String middle = "M".repeat(200);
        String tail = "T".repeat(100);
        String content = head + middle + tail; // total 400 chars, exceeds maxChars=200

        String result = compactor.compactIfNeeded(content);

        assertThat(result).contains("Head:\n" + head);
        assertThat(result).contains("Tail:\n" + tail);
        // Middle content should not appear as a block
        assertThat(result).doesNotContain("M".repeat(200));
    }

    @Test
    void shouldReportShouldCompactCorrectly() {
        assertThat(compactor.shouldCompact("A".repeat(201))).isTrue();
        assertThat(compactor.shouldCompact("A".repeat(200))).isFalse();
        assertThat(compactor.shouldCompact(null)).isFalse();
        assertThat(compactor.shouldCompact("")).isFalse();
    }

    @Test
    void shouldCompactWhenContentExceedsMaxButShorterThanHeadPlusTail() {
        // max=1000, head=max(800,100)=800, tail=max(800,100)=800
        // content=1200: exceeds max (1000) but shorter than head+tail (1600)
        ToolResultCompactor wideCompactor = new ToolResultCompactor(1000, 800, 800, meterProvider());
        String content = "A".repeat(1200);

        String result = wideCompactor.compactIfNeeded(content);

        assertThat(result).startsWith("[Tool result compacted for context budget]");
        assertThat(result).contains("Original chars: 1200");
        assertThat(result.length()).isLessThan(content.length());
    }

    @Test
    void shouldForceCompactForBudgetRegardlessOfMaxChars() {
        ToolResultCompactor wideCompactor = new ToolResultCompactor(1000, 800, 800, meterProvider());
        // 500 chars — below maxChars, so compactIfNeeded returns original
        String content = "X".repeat(500);
        assertThat(wideCompactor.compactIfNeeded(content)).isSameAs(content);

        // But compactForBudget should still compact it
        String result = wideCompactor.compactForBudget(content);
        assertThat(result).startsWith("[Tool result compacted for context budget]");
        assertThat(result).contains("Original chars: 500");
    }

    @Test
    void shouldKeepOriginalWhenBudgetCompactionWouldGrowContent() {
        String content = "tiny";

        String result = compactor.compactForBudget(content);

        assertThat(result).isSameAs(content);
    }

    @Test
    void shouldReturnNullWhenBudgetCompactingNull() {
        assertThat(compactor.compactForBudget(null)).isNull();
    }

    @Test
    void shouldIncrementMetricOnCompaction() {
        String content = "A".repeat(500);

        compactor.compactIfNeeded(content);

        assertThat(meterRegistry
                .counter("chatagent.memory.compaction.v2.tool_results_compacted")
                .count()).isEqualTo(1.0);
    }

    @Test
    void shouldNotIncrementMetricWhenNoCompactionNeeded() {
        String content = "Short";

        compactor.compactIfNeeded(content);

        assertThat(meterRegistry
                .counter("chatagent.memory.compaction.v2.tool_results_compacted")
                .count()).isEqualTo(0.0);
    }

    @Test
    void shouldPreserveErrorLinesFromDroppedMiddle() {
        // Build content with error lines in the middle that fall outside head/tail.
        // Config: maxChars=200, headChars=100, tailChars=100
        // Content must be large enough that error lines fit within the length margin.
        String head = "H".repeat(100);
        String middle = "X".repeat(300) + "\nNormal line\nError: connection refused\nMore noise\n"
                + "Final noise\n" + "Y".repeat(300);
        String tail = "T".repeat(100);
        String content = head + middle + tail;

        String result = compactor.compactIfNeeded(content);

        assertThat(result).contains("Error: connection refused");
        // Non-error middle content should not appear as full blocks
        assertThat(result).doesNotContain("X".repeat(300));
        assertThat(result).doesNotContain("Y".repeat(300));
    }
}
