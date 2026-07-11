package com.yulong.chatagent.intent.application;

import com.yulong.chatagent.agent.runtime.contract.SourceReferenceClassifier;
import com.yulong.chatagent.support.dto.IntentNodeDTO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClarificationResolverTest {

    private final ClarificationResolver resolver = new ClarificationResolver(
            new IntentSignalAnalyzer(new SourceReferenceClassifier()));

    @Test
    void shouldResolveOrdinalExactUniquePartialAndReviewedExample() {
        List<IntentNodeDTO> candidates = candidates();

        assertSelected("second", "expense", candidates);
        assertSelected("Travel Expense", "expense", candidates);
        assertSelected("Annual", "leave", candidates);
        assertSelected("claim my hotel receipt", "expense", candidates);
    }

    @Test
    void shouldResolveSelectManyFromBothOrMultipleOrdinals() {
        assertThat(resolver.resolveTyped("both", candidates()))
                .satisfies(reply -> {
                    assertThat(reply.outcome()).isEqualTo(ClarificationResolver.ReplyOutcome.SELECT_MANY);
                    assertThat(reply.selected()).hasSize(2);
                });
        assertThat(resolver.resolveTyped("1 and 2", candidates()).selected())
                .extracting(IntentNodeDTO::getId).containsExactly("leave", "expense");
    }

    @Test
    void shouldDistinguishNoneCancelNewTopicAndUnresolved() {
        assertThat(resolver.resolveTyped("none of these", candidates()).outcome())
                .isEqualTo(ClarificationResolver.ReplyOutcome.NONE_OF_THESE);
        assertThat(resolver.resolveTyped("cancel", candidates()).outcome())
                .isEqualTo(ClarificationResolver.ReplyOutcome.CANCEL);
        assertThat(resolver.resolveTyped("Actually, what is the weather?", candidates()))
                .satisfies(reply -> {
                    assertThat(reply.outcome()).isEqualTo(ClarificationResolver.ReplyOutcome.NEW_TOPIC);
                    assertThat(reply.newTopicText()).isEqualTo("what is the weather?");
                });
        assertThat(resolver.resolveTyped("not sure", candidates()).outcome())
                .isEqualTo(ClarificationResolver.ReplyOutcome.UNRESOLVED);
    }

    @Test
    void shouldPreserveLegacySingleSelectionFacade() {
        assertThat(resolver.resolve("第二项比较像", candidates()))
                .extracting(IntentNodeDTO::getId).isEqualTo("expense");
        assertThat(resolver.resolve("both", candidates())).isNull();
    }

    @Test
    void shouldNotTreatQuarterAsOrdinal() {
        assertThat(resolver.resolve("第一季度的预算还要确认", candidates())).isNull();
    }

    private void assertSelected(String reply, String expectedId, List<IntentNodeDTO> candidates) {
        assertThat(resolver.resolveTyped(reply, candidates))
                .satisfies(result -> {
                    assertThat(result.outcome()).isEqualTo(ClarificationResolver.ReplyOutcome.SELECT_ONE);
                    assertThat(result.selected()).extracting(IntentNodeDTO::getId).containsExactly(expectedId);
                });
    }

    private List<IntentNodeDTO> candidates() {
        return List.of(
                IntentNodeDTO.builder().id("leave").name("Annual Leave")
                        .examples(List.of("request paid vacation", "annual allowance")).build(),
                IntentNodeDTO.builder().id("expense").name("Travel Expense")
                        .examples(List.of("claim my hotel receipt", "travel reimbursement")).build()
        );
    }
}
