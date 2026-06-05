package com.yulong.chatagent.conversation.summary;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StructuredSummaryParserTest {

    @Test
    void shouldParseValidJson() {
        String json = """
                {
                  "summary": "User discussed project timeline.",
                  "facts": ["Deadline is June 15.", "Budget is $5000."],
                  "decisions": ["Use React for frontend."],
                  "open_tasks": ["Finalize API contract."],
                  "entities": {
                    "dates": ["2026-06-15"],
                    "amounts": ["$5000"],
                    "orderIds": []
                  }
                }
                """;

        StructuredSummary result = StructuredSummaryParser.parse(json);

        assertThat(result.summary()).isEqualTo("User discussed project timeline.");
        assertThat(result.facts()).containsExactly("Deadline is June 15.", "Budget is $5000.");
        assertThat(result.decisions()).containsExactly("Use React for frontend.");
        assertThat(result.openTasks()).containsExactly("Finalize API contract.");
        assertThat(result.entities().get("dates")).containsExactly("2026-06-15");
        assertThat(result.entities().get("amounts")).containsExactly("$5000");
        // Empty entity lists are filtered out by the parser
        assertThat(result.entities()).doesNotContainKey("orderIds");
    }

    @Test
    void shouldHandleMissingOptionalFields() {
        String json = """
                {"summary": "Brief summary."}
                """;

        StructuredSummary result = StructuredSummaryParser.parse(json);

        assertThat(result.summary()).isEqualTo("Brief summary.");
        assertThat(result.facts()).isEmpty();
        assertThat(result.decisions()).isEmpty();
        assertThat(result.openTasks()).isEmpty();
        assertThat(result.entities()).isEmpty();
    }

    @Test
    void shouldStripMarkdownCodeFences() {
        String json = """
                ```json
                {
                  "summary": "Fenced output.",
                  "facts": ["One fact."],
                  "decisions": [],
                  "open_tasks": [],
                  "entities": {}
                }
                ```
                """;

        StructuredSummary result = StructuredSummaryParser.parse(json);

        assertThat(result.summary()).isEqualTo("Fenced output.");
        assertThat(result.facts()).containsExactly("One fact.");
    }

    @Test
    void shouldReturnEmptyWhenJsonIsInvalid() {
        String invalid = "This is not JSON at all, just plain text.";

        StructuredSummary result = StructuredSummaryParser.parse(invalid);

        // Invalid JSON returns empty so caller falls through to deterministic fallback
        assertThat(result.summary()).isEmpty();
        assertThat(result.facts()).isEmpty();
        assertThat(result.entities()).isEmpty();
    }

    @Test
    void shouldReturnEmptyForBlankInput() {
        StructuredSummary result = StructuredSummaryParser.parse("   ");

        assertThat(result.summary()).isEmpty();
        assertThat(result.facts()).isEmpty();
    }

    @Test
    void shouldReturnEmptyForNullInput() {
        StructuredSummary result = StructuredSummaryParser.parse(null);

        assertThat(result.summary()).isEmpty();
    }

    @Test
    void shouldFilterNonStringItemsInLists() {
        String json = """
                {
                  "summary": "Mixed types.",
                  "facts": ["Valid fact", 42, null, "", "Another fact"],
                  "decisions": ["Keep this"],
                  "open_tasks": [],
                  "entities": {}
                }
                """;

        StructuredSummary result = StructuredSummaryParser.parse(json);

        assertThat(result.facts()).containsExactly("Valid fact", "Another fact");
        assertThat(result.decisions()).containsExactly("Keep this");
    }

    @Test
    void shouldHandleEmptyEntityLists() {
        String json = """
                {
                  "summary": "No entities.",
                  "facts": [],
                  "decisions": [],
                  "open_tasks": [],
                  "entities": {
                    "dates": [],
                    "amounts": [],
                    "orderIds": []
                  }
                }
                """;

        StructuredSummary result = StructuredSummaryParser.parse(json);

        assertThat(result.entities()).isEmpty();
    }

    @Test
    void shouldCreateFallbackFromTurns() {
        List<AtomicConversationTurn> turns = List.of(
                new AtomicConversationTurn("t-1", 1L, 3L, List.of("Hello world"), "Hi there"),
                new AtomicConversationTurn("t-2", 4L, 6L, List.of("Second question"), "Answer")
        );

        StructuredSummary result = StructuredSummaryParser.fallback(turns);

        assertThat(result.summary()).contains("User: Hello world")
                .contains("Assistant: Hi there")
                .contains("User: Second question")
                .contains("Assistant: Answer");
        assertThat(result.facts()).hasSize(2);
        assertThat(result.decisions()).isEmpty();
        assertThat(result.openTasks()).isEmpty();
    }

    @Test
    void shouldCreateEmptyFallbackForEmptyTurns() {
        StructuredSummary result = StructuredSummaryParser.fallback(List.of());

        assertThat(result.summary()).isEmpty();
        assertThat(result.facts()).isEmpty();
    }

    @Test
    void shouldCreateEmptyFallbackForNullTurns() {
        StructuredSummary result = StructuredSummaryParser.fallback(null);

        assertThat(result.summary()).isEmpty();
    }
}
