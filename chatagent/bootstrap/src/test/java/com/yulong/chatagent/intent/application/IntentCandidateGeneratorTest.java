package com.yulong.chatagent.intent.application;

import com.yulong.chatagent.intent.model.IntentKind;
import com.yulong.chatagent.support.dto.IntentNodeDTO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IntentCandidateGeneratorTest {

    private final IntentCandidateGenerator generator = new IntentCandidateGenerator();

    @Test
    void shouldRankNestedLeafByNameDescriptionAndPathEvidence() {
        IntentNodeDTO hr = node("hr", "Human Resources", null, null, 10);
        IntentNodeDTO leave = node("leave", "Annual Leave", "hr", IntentKind.KB, 20);
        leave.setDescription("Vacation and paid leave policy");
        leave.setExamples(List.of("annual leave allowance", "book vacation"));
        IntentNodeDTO expense = node("expense", "Travel Expense", null, IntentKind.KB, 1);

        List<IntentCandidate> ranked = generator.generate(snapshot(hr, leave, expense),
                "what is my annual leave allowance");

        assertThat(ranked.get(0).node().getId()).isEqualTo("leave");
        assertThat(ranked.get(0).path()).isEqualTo("Human Resources > Annual Leave");
        assertThat(ranked.get(0).reasonCodes()).contains("example_overlap");
        assertThat(ranked.get(0).lexicalGap()).isPositive();
    }

    @Test
    void shouldUseSortOrderAndNodeIdOnlyAfterEqualRelevance() {
        IntentNodeDTO later = node("b", "Operations Beta", null, IntentKind.KB, 20);
        IntentNodeDTO earlier = node("a", "Operations Alpha", null, IntentKind.KB, 10);

        List<IntentCandidate> ranked = generator.generate(snapshot(later, earlier), "operations");

        assertThat(ranked).extracting(candidate -> candidate.node().getId())
                .containsExactly("a", "b");
    }

    @Test
    void shouldNotLetProductOrderHideRelevantCandidateInBroadSiblingSet() {
        IntentNodeDTO irrelevant1 = node("a", "Facilities", null, IntentKind.KB, 1);
        IntentNodeDTO irrelevant2 = node("b", "Payroll", null, IntentKind.KB, 2);
        IntentNodeDTO relevant = node("z", "Account Recovery", null, IntentKind.TOOL, 99);
        relevant.setExamples(List.of("reset my password", "cannot sign in"));

        List<IntentCandidate> ranked = generator.generate(
                snapshot(irrelevant1, irrelevant2, relevant), "reset my password");

        assertThat(ranked.get(0).node().getId()).isEqualTo("z");
        assertThat(ranked.get(0).exactExampleMatch()).isTrue();
    }

    @Test
    void shouldExposeCompetingReviewedExactMatchesForPolicy() {
        IntentNodeDTO first = node("a", "Leave", null, IntentKind.KB, 1);
        IntentNodeDTO second = node("b", "Absence", null, IntentKind.KB, 2);
        first.setExamples(List.of("time off", "annual vacation"));
        second.setExamples(List.of("time off", "sick absence"));

        List<IntentCandidate> candidates = generator.generate(snapshot(first, second), "time off");

        assertThat(candidates.stream()
                .filter(candidate -> candidate.exactNameMatch() || candidate.exactExampleMatch()))
                .hasSize(2);
    }

    @Test
    void shouldKeepZeroScoreCandidatesForBoundedSemanticClassification() {
        List<IntentCandidate> candidates = generator.generate(
                snapshot(node("leave", "Annual Leave", null, IntentKind.KB, 1)),
                "completely unrelated wording");

        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).lexicalScore()).isZero();
        assertThat(generator.limit(candidates, 8)).hasSize(1);
    }

    private IntentNodeDTO node(String id, String name, String parentId, IntentKind kind, int sortOrder) {
        return IntentNodeDTO.builder().id(id).name(name).parentId(parentId)
                .intentKind(kind).enabled(true).sortOrder(sortOrder).build();
    }

    private IntentTreeSnapshot snapshot(IntentNodeDTO... nodes) {
        return new IntentTreeSnapshot("agent", 1, List.of(nodes), Map.of());
    }
}
