package com.yulong.chatagent.agent.runtime.contract;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Validates that a rewritten query preserves the user-stated constraints that
 * matter for source-backed retrieval.
 *
 * <p>This is the Phase 2 "preservation gate" described in the plan: if a rewrite
 * drops source, object, time/version, action, or comparison constraints, the
 * caller must fall back to the original query rather than send a lossy rewrite
 * to retrieval. (Execution-clarification escalation is owned by Phase 4.)</p>
 *
 * <p>Constraints come from two sources:</p>
 * <ul>
 *   <li>{@link SourceReferenceClassifier} — source references (session file, KB,
 *       currentness) and comparison operands (both sides of "A with B").</li>
 *   <li>{@link QueryContentTokenExtractor} — salient content tokens (stopword-
 *       filtered English words, CJK 2-grams, numbers, codes, filenames) that
 *       protect the <em>object</em> of ordinary questions. This catches object
 *       substitution like "annual leave policy" → "sick leave policy".</li>
 * </ul>
 *
 * <p>Comparison operation WORDS (compare/versus/对比) are NOT preservation
 * constraints: they express intent, are normalized into {@link QueryOperation},
 * and may be paraphrased. The comparison OPERANDS (the objects) are the real
 * constraints that must survive verbatim.</p>
 */
@Component
public class QueryRewritePreservationValidator {

    private final SourceReferenceClassifier classifier;
    private final QueryContentTokenExtractor contentTokenExtractor;

    public QueryRewritePreservationValidator(SourceReferenceClassifier classifier,
                                             QueryContentTokenExtractor contentTokenExtractor) {
        this.classifier = classifier;
        this.contentTokenExtractor = contentTokenExtractor;
    }

    /**
     * Validate that the rewritten query preserves the constraints detected in
     * the original text.
     *
     * @param originalText  the raw user input
     * @param rewrittenText the rewritten query (may be {@code null}/blank)
     * @return a preservation result; never {@code null}
     */
    public PreservationResult validate(String originalText, String rewrittenText) {
        if (!StringUtils.hasText(originalText)) {
            return PreservationResult.ok(0);
        }
        List<String> detected = detectConstraints(originalText);
        if (detected.isEmpty()) {
            return PreservationResult.ok(0);
        }
        if (!StringUtils.hasText(rewrittenText)) {
            return new PreservationResult(false, detected.size(), "rewritten query is blank");
        }

        String rewrittenLower = rewrittenText.toLowerCase(Locale.ROOT);
        int droppedCount = 0;
        for (String constraint : detected) {
            String token = constraint.toLowerCase(Locale.ROOT);
            if (!constraintPresent(rewrittenLower, token)) {
                droppedCount++;
            }
        }
        if (droppedCount == 0) {
            return PreservationResult.ok(detected.size());
        }
        // Log-safe reason: only the count and category, never the constraint values.
        return new PreservationResult(false, detected.size(),
                "rewrite dropped " + droppedCount + " of " + detected.size() + " detected constraints");
    }

    /**
     * Detect the constraint tokens present in the original text.
     *
     * <p>Returns lower-cased constraint tokens so the caller can record how many
     * constraints the query plan must keep. Includes source references, currentness
     * tokens, comparison operands, and salient content tokens (object protection).</p>
     */
    public List<String> detectConstraints(String text) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }
        List<String> constraints = new ArrayList<>();
        SourceReferenceClassifier.SourceClassification c = classifier.classify(text);
        addMatches(text, SourceReferenceClassifier.sessionFilePattern(), constraints);
        addMatches(text, SourceReferenceClassifier.knowledgeBasePattern(), constraints);
        addMatches(text, SourceReferenceClassifier.currentnessPattern(), constraints);
        // Comparison operands (the objects on both sides of "A with B") are real constraints.
        c.comparisonTargets().forEach(t -> addUnique(t.toLowerCase(Locale.ROOT), constraints));
        // Salient content tokens protect the object of ordinary (non-comparison) questions.
        contentTokenExtractor.extract(text).forEach(t -> addUnique(t, constraints));
        return constraints;
    }

    private void addMatches(String text, java.util.regex.Pattern pattern, List<String> into) {
        pattern.matcher(text).results().forEach(m -> {
            String match = m.group().trim().toLowerCase(Locale.ROOT);
            addUnique(match, into);
        });
    }

    private void addUnique(String token, List<String> into) {
        if (StringUtils.hasText(token) && !into.contains(token)) {
            into.add(token);
        }
    }

    /**
     * Check whether a constraint token is present in the rewritten text with
     * appropriate boundary semantics.
     *
     * <p>For ASCII identifier tokens (Latin letters, digits, underscores, hyphens,
     * dots — e.g. "tax", "policy", "v2", "item_3", "2024-q3"), word-boundary
     * matching is used so "tax" is NOT considered present inside "syntax" and
     * "item_3" is NOT considered present inside "xitem_3". For CJK 2-grams and
     * genuinely mixed tokens, substring matching is used because CJK has no word
     * delimiters and the tokens are already short.</p>
     */
    private boolean constraintPresent(String rewrittenLower, String token) {
        if (rewrittenLower.contains(token)) {
            // For non-ASCII tokens (CJK or mixed), substring match is sufficient.
            if (!token.matches("[a-z0-9_.\\-]+")) {
                return true;
            }
            // For ASCII identifier tokens, require word boundaries so "tax" ≠ inside
            // "syntax" and "item_3" ≠ inside "xitem_3".
            return java.util.regex.Pattern.compile(
                    "\\b" + java.util.regex.Pattern.quote(token) + "\\b").matcher(rewrittenLower).find();
        }
        return false;
    }

    /**
     * Result of a preservation check.
     *
     * @param preserved whether the rewrite preserved all detected constraints
     * @param detectedCount the number of constraints found in the original text
     * @param reason log-safe reason code when not preserved (no raw content)
     */
    public record PreservationResult(
            boolean preserved,
            int detectedCount,
            String reason
    ) {
        public static PreservationResult ok(int detectedCount) {
            return new PreservationResult(true, detectedCount, null);
        }
    }
}
