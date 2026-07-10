package com.yulong.chatagent.agent.runtime.contract;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2 tests for {@link QueryRewritePreservationValidator}.
 *
 * <p>Assert that source/object/time/comparison constraints are detected and that
 * a lossy rewrite fails while a faithful rewrite passes. Covers the P1 finding:
 * objects and comparison targets must be preserved, not just source keywords.</p>
 */
class QueryRewritePreservationValidatorTest {

    private final QueryRewritePreservationValidator validator = ContractTestSupport.validator();

    @Test
    void shouldPassWhenRewritePreservesUploadedFileReference() {
        String original = "Summarize the uploaded report.";
        String rewrite = "uploaded report summary";

        QueryRewritePreservationValidator.PreservationResult result = validator.validate(original, rewrite);

        assertThat(result.preserved()).isTrue();
        assertThat(result.detectedCount()).isGreaterThan(0);
    }

    @Test
    void shouldFailWhenRewriteDropsUploadedFileReference() {
        String original = "Compare the policy with my uploaded spreadsheet.";
        String rewrite = "compare policy approval limits";

        QueryRewritePreservationValidator.PreservationResult result = validator.validate(original, rewrite);

        assertThat(result.preserved()).isFalse();
        assertThat(result.reason()).contains("dropped");
    }

    @Test
    void shouldPreserveCurrentnessConstraint() {
        String original = "What is the latest version of the API?";
        String rewrite = "latest version API documentation";

        QueryRewritePreservationValidator.PreservationResult result = validator.validate(original, rewrite);

        assertThat(result.preserved()).isTrue();
    }

    @Test
    void shouldFailWhenCurrentnessDropped() {
        String original = "What is the latest price?";
        String rewrite = "price range overview";

        QueryRewritePreservationValidator.PreservationResult result = validator.validate(original, rewrite);

        assertThat(result.preserved()).isFalse();
    }

    @Test
    void shouldFailOnBlankRewrite() {
        QueryRewritePreservationValidator.PreservationResult result = validator.validate("uploaded file details", "");

        assertThat(result.preserved()).isFalse();
        assertThat(result.reason()).contains("blank");
    }

    @Test
    void shouldPassWhenNoConstraintsDetected() {
        QueryRewritePreservationValidator.PreservationResult result = validator.validate("hello there", "hi");

        assertThat(result.preserved()).isTrue();
        assertThat(result.detectedCount()).isZero();
    }

    @Test
    void shouldFailWhenSecondComparisonTargetDropped() {
        // P1 regression: "compare A with B" — dropping B must fail, not pass.
        String original = "Compare policy A with policy B.";
        String rewrite = "compare policy A";

        QueryRewritePreservationValidator.PreservationResult result = validator.validate(original, rewrite);

        assertThat(result.preserved()).isFalse();
        // The reason must NOT contain the raw constraint values (content-free logging).
        assertThat(result.reason()).doesNotContain("policy B");
    }

    @Test
    void shouldFailWhenFirstComparisonOperandDropped() {
        // P1 regression round 2: dropping the FIRST operand must also fail.
        String original = "Compare policy A with policy B.";
        String rewrite = "compare policy B";

        QueryRewritePreservationValidator.PreservationResult result = validator.validate(original, rewrite);

        assertThat(result.preserved()).isFalse();
    }

    @Test
    void shouldFailWhenChineseSecondTargetDropped() {
        // P1 regression round 2: Chinese dual-target comparison — dropping one side fails.
        String original = "比较政策A和政策B";
        String rewrite = "比较政策B";

        QueryRewritePreservationValidator.PreservationResult result = validator.validate(original, rewrite);

        assertThat(result.preserved()).isFalse();
    }

    @Test
    void shouldPassWhenBothChineseOperandsPreserved() {
        String original = "比较政策A和政策B";
        String rewrite = "政策A 政策B 对比";

        QueryRewritePreservationValidator.PreservationResult result = validator.validate(original, rewrite);

        assertThat(result.preserved()).isTrue();
    }

    @Test
    void shouldAcceptCompareToContrastRewriteWhenBothOperandsSurvive() {
        // P2 round-4: the SUCCESS path the contract claims. compare→contrast with both
        // operands (policy A, policy B) preserved verbatim must PASS preservation.
        String original = "Compare policy A with policy B.";
        String preservingRewrite = "contrast policy with policy A and policy B";

        QueryRewritePreservationValidator.PreservationResult result = validator.validate(original, preservingRewrite);

        assertThat(result.preserved()).isTrue();
    }

    @Test
    void shouldFailWhenComparisonOperandDroppedEvenIfVerbParaphrased() {
        // Original: "Compare the annual leave policy with the sick leave policy."
        // Rewrite drops the sick-leave operand → fails on object loss, not on the verb.
        String original = "Compare the annual leave policy with the sick leave policy.";
        String rewrite = "annual leave policy comparison";

        QueryRewritePreservationValidator.PreservationResult result = validator.validate(original, rewrite);

        assertThat(result.preserved()).isFalse();
    }

    @Test
    void shouldFailWhenShortObjectReplaced() {
        // P2 round-4: short 3-letter objects like tax/law/pay must be preserved.
        // "tax policy" rewritten to "leave policy" drops the "tax" object.
        String original = "What is the tax policy?";
        String rewrite = "What is the leave policy?";

        QueryRewritePreservationValidator.PreservationResult result = validator.validate(original, rewrite);

        assertThat(result.preserved()).isFalse();
    }

    @Test
    void shouldFailWhenQuarterCodeChanged() {
        // P2 round-4: 2024-Q3 → 2024-Q4 changes the object; the full quarter code must survive.
        String original = "What changed in the 2024-Q3 release?";
        String rewrite = "what changed in the 2024-Q4 release";

        QueryRewritePreservationValidator.PreservationResult result = validator.validate(original, rewrite);

        assertThat(result.preserved()).isFalse();
    }

    @Test
    void shouldFailWhenNormalQaObjectReplaced() {
        // P1 round-3: ordinary KB question object substitution must fail.
        // "annual leave policy" rewritten to "sick leave policy" drops the "annual" object.
        String original = "What is the annual leave policy?";
        String rewrite = "What is the sick leave policy?";

        QueryRewritePreservationValidator.PreservationResult result = validator.validate(original, rewrite);

        assertThat(result.preserved()).isFalse();
    }

    @Test
    void shouldFailWhenNumberOrVersionDropped() {
        String original = "What is covered in policy version 3.2?";
        String rewrite = "what is covered in the policy";

        QueryRewritePreservationValidator.PreservationResult result = validator.validate(original, rewrite);

        assertThat(result.preserved()).isFalse();
    }

    @Test
    void shouldFailWhenShortObjectSubstringMatchedInsideLongerWord() {
        // ATC-P02-F04: boundary-aware matching — "tax" must NOT be found inside "syntax".
        String original = "What is the tax policy?";
        String rewrite = "What is the syntax policy?";

        QueryRewritePreservationValidator.PreservationResult result = validator.validate(original, rewrite);

        assertThat(result.preserved()).isFalse();
    }

    @Test
    void shouldFailWhenTemporalModifierReplaced() {
        // ATC-P02-F04: temporal/quantifier modifiers (old/new) are meaning-changing.
        String original = "What is the old policy?";
        String rewrite = "What is the new policy?";

        QueryRewritePreservationValidator.PreservationResult result = validator.validate(original, rewrite);

        assertThat(result.preserved()).isFalse();
    }

    @Test
    void shouldFailWhenChineseObjectDropped() {
        // P1 round-3: Chinese object substitution. 年假 → 病假 changes the object.
        String original = "年假怎么申请？";
        String rewrite = "病假怎么申请？";

        QueryRewritePreservationValidator.PreservationResult result = validator.validate(original, rewrite);

        assertThat(result.preserved()).isFalse();
    }

    @Test
    void shouldPreserveMultiWordObject() {
        // A faithful rewrite that keeps all content tokens passes.
        String original = "What is the travel reimbursement policy approval process?";
        String rewrite = "travel reimbursement policy approval process steps";

        QueryRewritePreservationValidator.PreservationResult result = validator.validate(original, rewrite);

        assertThat(result.preserved()).isTrue();
    }

    @Test
    void shouldDetectChineseSourceConstraints() {
        // Source references (knowledge base, uploaded file) are preservation constraints.
        // The comparison verb (对比) is NOT — it is operation intent, derived into QueryOperation.
        assertThat(validator.detectConstraints("对比知识库和上传的文件"))
                .anyMatch(c -> c.contains("知识库"))
                .anyMatch(c -> c.contains("上传"));
    }

    @Test
    void shouldFailWhenUnderscoreIdentifierSubstringMatched() {
        // ATC-P02-F06: "item_3" must NOT be found inside "xitem_3" — boundary-aware.
        String original = "What is item_3?";
        String rewrite = "What is xitem_3?";

        QueryRewritePreservationValidator.PreservationResult result = validator.validate(original, rewrite);

        assertThat(result.preserved()).isFalse();
    }

    @Test
    void shouldPassWhenUnderscoreIdentifierPreservedExactly() {
        // ATC-P02-F06: exact identifier preservation passes.
        String original = "What is item_3?";
        String rewrite = "item_3 details";

        QueryRewritePreservationValidator.PreservationResult result = validator.validate(original, rewrite);

        assertThat(result.preserved()).isTrue();
    }
}
