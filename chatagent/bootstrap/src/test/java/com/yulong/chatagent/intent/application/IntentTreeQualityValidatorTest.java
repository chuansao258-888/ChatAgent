package com.yulong.chatagent.intent.application;

import com.yulong.chatagent.intent.model.IntentKind;
import com.yulong.chatagent.support.dto.IntentNodeDTO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IntentTreeQualityValidatorTest {

    private final IntentTreeQualityValidator validator = new IntentTreeQualityValidator();

    @Test
    void shouldMarkReviewedDistinctLeavesEligible() {
        var report = validator.validate(snapshot(
                leaf("leave", "Leave", "Paid time off", List.of("annual leave", "vacation"), null),
                leaf("expense", "Expense", "Expense claims", List.of("travel claim", "receipt claim"), null)
        ), 2);

        assertThat(report.findings()).isEmpty();
        assertThat(report.autoRouteEligibleNodeIds()).containsExactlyInAnyOrder("leave", "expense");
    }

    @Test
    void shouldExcludeDuplicateSiblingNamesWithoutLeakingNames() {
        var report = validator.validate(snapshot(
                leaf("a", "Leave", "d1", List.of("e1", "e2"), null),
                leaf("b", " leave ", "d2", List.of("e3", "e4"), null)
        ), 2);

        assertThat(report.findings()).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("duplicate_sibling_name");
            assertThat(finding.nodeIds()).containsExactlyInAnyOrder("a", "b");
            assertThat(finding.toString()).doesNotContain("Leave", "leave ");
        });
        assertThat(report.autoRouteEligibleNodeIds()).doesNotContain("a", "b");
    }

    @Test
    void shouldRequireDescriptionAndProfileOwnedExampleMinimum() {
        var report = validator.validate(snapshot(
                leaf("a", "Leave", null, List.of("only one"), null)
        ), 2);

        assertThat(report.findings()).extracting(IntentTreeQualityValidator.QualityFinding::code)
                .contains("blank_leaf_description", "insufficient_reviewed_examples");
        assertThat(report.isAutoRouteEligible("a")).isFalse();
    }

    @Test
    void shouldExcludeSiblingExampleCollisionsWithoutLeakingExampleText() {
        var report = validator.validate(snapshot(
                leaf("a", "Alpha", "d1", List.of("shared phrase", "alpha only"), null),
                leaf("b", "Beta", "d2", List.of("shared phrase", "beta only"), null)
        ), 2);

        assertThat(report.findings()).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("duplicate_sibling_example");
            assertThat(finding.toString()).doesNotContain("shared phrase");
        });
        assertThat(report.hasHighSeverity()).isTrue();
    }

    @Test
    void shouldIgnoreDisabledSiblingWhenComputingEligibility() {
        IntentNodeDTO enabled = leaf("enabled", "Annual Leave", "description",
                List.of("annual leave balance", "annual leave process"), null);
        IntentNodeDTO disabled = leaf("disabled", "Annual Leave", "description",
                List.of("annual leave balance", "annual leave process"), null);
        disabled.setEnabled(false);

        var report = validator.validate(snapshot(enabled, disabled), 2);

        assertThat(report.isAutoRouteEligible("enabled")).isTrue();
        assertThat(report.findings()).isEmpty();
    }

    private IntentNodeDTO leaf(String id, String name, String description,
                               List<String> examples, String parentId) {
        return IntentNodeDTO.builder().id(id).name(name).description(description)
                .examples(examples).parentId(parentId).intentKind(IntentKind.KB).enabled(true).build();
    }

    private IntentTreeSnapshot snapshot(IntentNodeDTO... nodes) {
        return new IntentTreeSnapshot("agent", 1, List.of(nodes), Map.of());
    }
}
