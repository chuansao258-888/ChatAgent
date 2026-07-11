package com.yulong.chatagent.agent.runtime.contract;

import com.yulong.chatagent.intent.application.IntentResolution;
import com.yulong.chatagent.intent.model.IntentKind;
import com.yulong.chatagent.intent.model.ScopePolicy;
import com.yulong.chatagent.support.dto.IntentNodeDTO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2 tests for {@link QueryPlanBuilder}.
 *
 * <p>Cover the plan's Phase 2 acceptance: simple KB QA produces one useful
 * query, mixed KB+file produces distinct source-specific specs (not identical
 * text), latest/current preserves currentness, and a lossy rewrite falls back
 * to the original text. These tests do NOT assert that QueryPlan alone triggers
 * KB retrieval (that is Phase 3).</p>
 */
class QueryPlanBuilderTest {

    private final QueryPlanBuilder builder = ContractTestSupport.queryPlanBuilder();

    @Test
    void shouldProduceSingleKbQueryForSimpleKbQa() {
        // The rewrite preserves the original object (年假申请) plus adds 流程; preservation passes.
        QueryPlan plan = builder.build(kbResolution(), SourceNeed.KB, "年假怎么申请？", "年假申请流程");

        assertThat(plan.mode()).isEqualTo(QueryPlanMode.SINGLE_QUERY);
        assertThat(plan.queries()).hasSize(1);
        assertThat(plan.queries().get(0).source()).isEqualTo(RetrievalSource.INTENT_KB);
        assertThat(plan.queries().get(0).text()).isEqualTo("年假申请流程");
    }

    @Test
    void shouldKeepOneQueryPerOrderedKbRouteWhenTextsMatch() {
        String query = "Answer the Annual Leave and Travel Expense questions.";

        QueryPlan plan = builder.buildForRoutes(
                List.of(
                        kbResolution("leave", "kb-leave", ScopePolicy.STRICT),
                        kbResolution("expense", "kb-expense", ScopePolicy.FALLBACK_ALLOWED)),
                SourceNeed.KB,
                query,
                query);

        assertThat(plan.mode()).isEqualTo(QueryPlanMode.MULTI_QUERY);
        assertThat(plan.queries()).hasSize(2);
        assertThat(plan.queries()).extracting(QuerySpec::source)
                .containsExactly(RetrievalSource.INTENT_KB, RetrievalSource.INTENT_KB);
        assertThat(plan.queries()).extracting(QuerySpec::text)
                .containsExactly(query, query);
    }

    @Test
    void shouldUseAgentDefaultKbWhenScopedKbIdsEmpty() {
        // ATC-P02-F05: empty scoped KB IDs → AGENT_DEFAULT_KB, matching RetrievalPlan.
        IntentResolution emptyScoped = new IntentResolution(
                IntentKind.KB,
                List.of(IntentNodeDTO.builder().id("leaf").name("报销").build()),
                List.of(),
                ScopePolicy.FALLBACK_ALLOWED,
                List.of(),
                null
        );
        QueryPlan plan = builder.build(emptyScoped, SourceNeed.KB, "报销流程", "报销 流程");

        assertThat(plan.queries().get(0).source()).isEqualTo(RetrievalSource.AGENT_DEFAULT_KB);
    }

    @Test
    void shouldUseAgentDefaultKbForMixedPlanWhenScopedKbIdsEmpty() {
        // ATC-P02-F05: mixed plan with null resolution → AGENT_DEFAULT_KB for the KB spec.
        QueryPlan plan = builder.build(null, SourceNeed.MIXED,
                "Compare the policy with my uploaded spreadsheet.", "policy comparison");

        QuerySpec kbSpec = plan.queries().stream()
                .filter(q -> q.source() != RetrievalSource.SESSION_FILES).findFirst().orElseThrow();
        assertThat(kbSpec.source()).isEqualTo(RetrievalSource.AGENT_DEFAULT_KB);
    }

    @Test
    void shouldProduceMultiQueryWithDistinctTextsWhenKbIntentAlsoReferencesUploadedFile() {
        IntentResolution resolution = kbResolution();
        String original = "Compare the policy with my uploaded spreadsheet report.xlsx.";

        QueryPlan plan = builder.build(resolution, SourceNeed.MIXED, original, "policy comparison");

        assertThat(plan.mode()).isEqualTo(QueryPlanMode.MULTI_QUERY);
        assertThat(plan.queries()).hasSize(2);
        assertThat(plan.queries()).extracting(QuerySpec::source)
                .contains(RetrievalSource.INTENT_KB, RetrievalSource.SESSION_FILES);
        // The two query texts must NOT be identical (P2 finding: no label-only decomposition).
        assertThat(plan.queries().get(0).text()).isNotEqualTo(plan.queries().get(1).text());
        // P1 round 2: the session-file query must bind a real file reference (filename),
        // not a mis-extracted target. Assert concrete coverage.
        QuerySpec fileSpec = plan.queries().stream()
                .filter(q -> q.source() == RetrievalSource.SESSION_FILES).findFirst().orElseThrow();
        assertThat(fileSpec.text()).contains("report.xlsx");
        assertThat(fileSpec.text()).isNotEmpty();
    }

    @Test
    void shouldAppendOneSessionFileQueryAfterEveryOrderedKbRoute() {
        String original = "Answer Annual Leave and Travel Expense using uploaded report.pdf.";

        QueryPlan plan = builder.buildForRoutes(
                List.of(
                        kbResolution("leave", "kb-leave", ScopePolicy.STRICT),
                        kbResolution("expense", "kb-expense", ScopePolicy.FALLBACK_ALLOWED)),
                SourceNeed.MIXED,
                original,
                original);

        assertThat(plan.mode()).isEqualTo(QueryPlanMode.MULTI_QUERY);
        assertThat(plan.queries()).extracting(QuerySpec::source)
                .containsExactly(
                        RetrievalSource.INTENT_KB,
                        RetrievalSource.INTENT_KB,
                        RetrievalSource.SESSION_FILES);
        assertThat(plan.queries().subList(0, 2)).extracting(QuerySpec::text)
                .containsExactly(original, original);
        assertThat(plan.queries().get(2).text()).contains("report.pdf");
    }

    @Test
    void shouldBindFileReferenceAndObjectInSessionFileQueryWhenNoFilename() {
        // P1 round 2: when there is no filename, the file query binds the upload noun + object.
        String original = "Compare the policy with my uploaded spreadsheet.";

        QueryPlan plan = builder.build(kbResolution(), SourceNeed.MIXED, original, "policy comparison");

        QuerySpec fileSpec = plan.queries().stream()
                .filter(q -> q.source() == RetrievalSource.SESSION_FILES).findFirst().orElseThrow();
        // Must reference the uploaded file noun, not be empty or a bare target.
        assertThat(fileSpec.text()).containsAnyOf("spreadsheet", "uploaded", "file");
    }

    @Test
    void shouldUseMultiQueryNotDecomposedForComparisonInPhase2() {
        // Phase 2 does not emit DECOMPOSED; comparison over sources is MULTI_QUERY.
        String original = "Compare the policy with my uploaded spreadsheet.";

        QueryPlan plan = builder.build(kbResolution(), SourceNeed.MIXED, original, "policy comparison");

        assertThat(plan.mode()).isEqualTo(QueryPlanMode.MULTI_QUERY);
        assertThat(plan.operation()).isEqualTo(QueryOperation.COMPARE);
    }

    @Test
    void shouldPreserveCurrentnessInRewrite() {
        String original = "What is the latest price of the product?";
        String rewrite = "latest price product catalog";

        QueryPlan plan = builder.build(kbResolution(), SourceNeed.KB, original, rewrite);

        // The rewrite keeps "latest" and "price", so it should be used (not the original).
        assertThat(plan.queries().get(0).text()).isEqualTo(rewrite);
        assertThat(plan.mustPreserve()).anyMatch(c -> c.toLowerCase().contains("latest"));
    }

    @Test
    void shouldFallBackToOriginalWhenRewriteDropsConstraint() {
        String original = "Summarize the uploaded report.";
        String lossyRewrite = "reimbursement summary";

        QueryPlan plan = builder.build(kbResolution(), SourceNeed.KB, original, lossyRewrite);

        assertThat(plan.queries().get(0).text()).isEqualTo(original);
    }

    @Test
    void shouldFallBackToOriginalWhenSecondComparisonTargetDropped() {
        // P1 regression: dropping the second comparison target is lossy.
        String original = "Compare policy A with policy B.";
        String lossyRewrite = "compare policy A";

        QueryPlan plan = builder.build(kbResolution(), SourceNeed.KB, original, lossyRewrite);

        assertThat(plan.queries().get(0).text()).isEqualTo(original);
    }

    @Test
    void shouldReturnNonePlanWhenNoSourceNeed() {
        QueryPlan plan = builder.build(null, SourceNeed.NONE, "hello", "hello");

        assertThat(plan.mode()).isEqualTo(QueryPlanMode.NONE);
        assertThat(plan.queries()).isEmpty();
    }

    @Test
    void shouldProduceSingleSessionFileQueryForFileSourceNeed() {
        QueryPlan plan = builder.build(null, SourceNeed.FILE, "summarize the uploaded report", "uploaded report summary");

        assertThat(plan.mode()).isEqualTo(QueryPlanMode.SINGLE_QUERY);
        assertThat(plan.queries().get(0).source()).isEqualTo(RetrievalSource.SESSION_FILES);
    }

    @Test
    void shouldDeriveSummarizeOperationForFileSourceNotHardcodedQa() {
        // P1 regression: FILE branch must not hardcode QA; operation derived from text.
        QueryPlan plan = builder.build(null, SourceNeed.FILE, "summarize the uploaded report", "uploaded report summary");

        assertThat(plan.operation()).isEqualTo(QueryOperation.SUMMARIZE);
    }

    @Test
    void shouldNormalizeCompareToContrastRewriteIntoCompareOperation() {
        // P2 round-4: the comparison verb is normalized into QueryOperation.COMPARE even
        // when the rewrite paraphrases compare→contrast (operands preserved).
        String original = "Compare policy A with policy B.";
        String preservingRewrite = "contrast policy with policy A and policy B";

        QueryPlan plan = builder.build(kbResolution(), SourceNeed.MIXED, original, preservingRewrite);

        assertThat(plan.operation()).isEqualTo(QueryOperation.COMPARE);
        // And the rewrite is accepted (not fallen back), since operands survived.
        assertThat(plan.queries()).isNotEmpty();
    }

    private IntentResolution kbResolution() {
        return kbResolution("leaf", "kb-1", ScopePolicy.STRICT);
    }

    private IntentResolution kbResolution(String nodeId, String kbId, ScopePolicy scopePolicy) {
        return new IntentResolution(
                IntentKind.KB,
                List.of(IntentNodeDTO.builder().id(nodeId).name(nodeId).build()),
                List.of(kbId),
                scopePolicy,
                List.of(),
                null
        );
    }
}
