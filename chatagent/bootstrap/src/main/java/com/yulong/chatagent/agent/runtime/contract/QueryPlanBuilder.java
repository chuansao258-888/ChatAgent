package com.yulong.chatagent.agent.runtime.contract;

import com.yulong.chatagent.intent.application.IntentResolution;
import com.yulong.chatagent.intent.model.IntentKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds a conservative {@link QueryPlan} from the resolved intent, the user
 * text, the rewritten input, and the source need.
 *
 * <p>Phase 2 scope: produce a structured query plan for L3 memory, prompt hints,
 * and the upcoming retrieval-plan path. This builder does <strong>not</strong>
 * fix current KB retrieval triggering — that is Phase 3. It runs the
 * {@link QueryRewritePreservationValidator} and falls back to the original query
 * when a rewrite drops a detected constraint.</p>
 *
 * <p>The builder is deterministic and makes no LLM calls; the LLM rewrite already
 * happened in {@code QueryRewriter}. Mode selection:</p>
 * <ul>
 *   <li>NONE — no source need, no retrieval query.</li>
 *   <li>SINGLE_QUERY — one source-backed query (simple KB QA, single file, web).</li>
 *   <li>MULTI_QUERY — separate source-specific specs when the user references
 *       more than one source. Each spec carries a source-specific query text so
 *       the two queries are not identical.</li>
 * </ul>
 * <p>DECOMPOSED is not emitted in Phase 2: real ordered-subquestion decomposition
 * needs target-aware query generation that is out of Phase 2 scope. A comparison
 * over multiple sources is represented as MULTI_QUERY with distinct per-source
 * query texts until a later phase adds true decomposition.</p>
 */
@Component
public class QueryPlanBuilder {

    private static final Logger log = LoggerFactory.getLogger(QueryPlanBuilder.class);

    private final QueryRewritePreservationValidator preservationValidator;
    private final SourceReferenceClassifier classifier;

    public QueryPlanBuilder(QueryRewritePreservationValidator preservationValidator,
                            SourceReferenceClassifier classifier) {
        this.preservationValidator = preservationValidator;
        this.classifier = classifier;
    }

    /**
     * Build a query plan for one turn.
     *
     * @param resolution     resolved intent (may be {@code null})
     * @param sourceNeed     the turn's source need from {@link TurnAnalysis}
     * @param originalText   the raw user input
     * @param rewrittenInput the rewritten input (may be {@code null})
     * @return a non-null query plan
     */
    public QueryPlan build(IntentResolution resolution,
                           SourceNeed sourceNeed,
                           String originalText,
                           String rewrittenInput) {
        String mustPreserveText = StringUtils.hasText(originalText) ? originalText : "";
        List<String> mustPreserve = preservationValidator.detectConstraints(mustPreserveText);

        if (sourceNeed == SourceNeed.NONE) {
            return QueryPlan.none();
        }

        // Pick the query text: prefer the rewrite, but gate it through preservation.
        String queryText = pickQueryText(originalText, rewrittenInput);
        SourceReferenceClassifier.SourceClassification classification = classifier.classify(mustPreserveText);
        QueryOperation operation = deriveOperation(mustPreserveText, classification);

        return switch (sourceNeed) {
            case KB -> buildKbPlan(resolution, queryText, mustPreserve, operation);
            case FILE -> new QueryPlan(QueryPlanMode.SINGLE_QUERY, operation,
                    List.of(new QuerySpec(queryText, RetrievalSource.SESSION_FILES, mustPreserve)),
                    mustPreserve);
            case WEB -> new QueryPlan(QueryPlanMode.SINGLE_QUERY, operation,
                    List.of(new QuerySpec(queryText, RetrievalSource.WEB_SEARCH, mustPreserve)),
                    mustPreserve);
            case MIXED -> buildMixedPlan(resolution, queryText, mustPreserveText, mustPreserve, operation, classification);
            default -> QueryPlan.none();
        };
    }

    /**
     * Derive the KB retrieval source consistent with {@code TurnExecutionContractBuilder.buildRetrievalPlan}:
     * non-empty scoped KB IDs yield INTENT_KB; empty yields AGENT_DEFAULT_KB.
     * This is shared by single-KB and mixed plans so the QueryPlan and RetrievalPlan
     * never disagree on source.
     */
    private RetrievalSource deriveKbRetrievalSource(IntentResolution resolution) {
        if (resolution == null || resolution.kind() != IntentKind.KB) {
            return RetrievalSource.AGENT_DEFAULT_KB;
        }
        List<String> scopedKbIds = resolution.scopedKbIds();
        return scopedKbIds != null && !scopedKbIds.isEmpty()
                ? RetrievalSource.INTENT_KB : RetrievalSource.AGENT_DEFAULT_KB;
    }

    private QueryPlan buildKbPlan(IntentResolution resolution, String queryText,
                                  List<String> mustPreserve, QueryOperation operation) {
        RetrievalSource source = deriveKbRetrievalSource(resolution);
        QuerySpec spec = new QuerySpec(queryText, source, mustPreserve);
        return new QueryPlan(QueryPlanMode.SINGLE_QUERY, operation, List.of(spec), mustPreserve);
    }

    private QueryPlan buildMixedPlan(IntentResolution resolution, String queryText, String originalText,
                                     List<String> mustPreserve,
                                     QueryOperation operation,
                                     SourceReferenceClassifier.SourceClassification classification) {
        // Produce genuinely distinct per-source query texts so the two specs are not
        // identical copies. The KB query keeps the (preservation-gated) query text;
        // the session-file query binds the real file reference (filename/upload noun)
        // plus the object/target so it is usable for session-file retrieval.
        String fileQuery = buildSessionFileQuery(originalText, queryText, classification);
        QuerySpec kbSpec = new QuerySpec(queryText, deriveKbRetrievalSource(resolution), mustPreserve);
        QuerySpec fileSpec = new QuerySpec(fileQuery, RetrievalSource.SESSION_FILES, mustPreserve);
        return new QueryPlan(QueryPlanMode.MULTI_QUERY, operation, List.of(kbSpec, fileSpec), mustPreserve);
    }

    /**
     * Build a session-file-specific query text that binds a real file reference.
     *
     * <p>The query is composed of the detected file reference (filename or upload
     * noun) plus any comparison operands/object entities, so it carries both the
     * retrievable file identity and the object the user is asking about. If no
     * file reference can be extracted, fall back to the original text (which by
     * construction contains a file reference in the MIXED branch).</p>
     */
    private String buildSessionFileQuery(String originalText, String kbQueryText,
                                         SourceReferenceClassifier.SourceClassification classification) {
        String fileRef = classifier.extractFileReference(originalText);
        List<String> operands = classification.comparisonTargets();
        StringBuilder sb = new StringBuilder();
        if (StringUtils.hasText(fileRef)) {
            sb.append(fileRef);
        }
        for (String operand : operands) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(operand);
        }
        if (sb.length() > 0) {
            return sb.toString();
        }
        return StringUtils.hasText(originalText) ? originalText : kbQueryText;
    }

    /**
     * Pick the query text to use, applying the preservation gate.
     *
     * <p>If the rewrite preserves all detected constraints, use it. If it drops a
     * constraint, fall back to the original text rather than sending a lossy
     * rewrite downstream. Phase 2 does not escalate to a clarification here
     * (clarification kinds arrive in Phase 4); fallback to original is the safe
     * default.</p>
     */
    private String pickQueryText(String originalText, String rewrittenInput) {
        if (!StringUtils.hasText(rewrittenInput)) {
            return StringUtils.hasText(originalText) ? originalText : "";
        }
        QueryRewritePreservationValidator.PreservationResult result =
                preservationValidator.validate(originalText, rewrittenInput);
        if (!result.preserved()) {
            // Content-free log: only the reason code, never the constraint values.
            log.info("Query rewrite fell back to original text: {}", result.reason());
            return originalText;
        }
        return rewrittenInput;
    }

    /**
     * Derive the query operation from the text + classification. Applied
     * consistently across all source branches so KB/FILE no longer hardcode QA.
     */
    private QueryOperation deriveOperation(String text,
                                           SourceReferenceClassifier.SourceClassification classification) {
        if (classification.comparison()) {
            return QueryOperation.COMPARE;
        }
        if (!StringUtils.hasText(text)) {
            return QueryOperation.QA;
        }
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        if (lower.matches(".*(summari[sz]e|summary|总结|汇总).*")) {
            return QueryOperation.SUMMARIZE;
        }
        if (lower.matches(".*(extract|提取).*")) {
            return QueryOperation.EXTRACT;
        }
        if (lower.matches(".*(verify|check|核[实对查]|确认).*")) {
            return QueryOperation.VERIFY;
        }
        return QueryOperation.QA;
    }
}
