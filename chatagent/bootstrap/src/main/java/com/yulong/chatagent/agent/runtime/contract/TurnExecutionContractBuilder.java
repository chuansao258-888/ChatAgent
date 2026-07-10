package com.yulong.chatagent.agent.runtime.contract;

import com.yulong.chatagent.agent.runtime.AgentExecutionMode;
import com.yulong.chatagent.intent.application.IntentResolution;
import com.yulong.chatagent.intent.model.IntentKind;
import com.yulong.chatagent.intent.model.ScopePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Builds a {@link TurnExecutionContract} from the existing turn-preparation
 * inputs.
 *
 * <p>Phase 1 derivation is conservative and deterministic: it maps the resolved
 * {@link IntentResolution} onto the contract enums without changing any
 * downstream behavior. Later phases will enrich {@link TurnAnalysis} and
 * {@link QueryPlan} and make the runtime enforce {@link RetrievalPlan}.</p>
 *
 * <p>Inputs that are {@code null} (passthrough turns, no intent tree) produce a
 * minimal general-chat contract so every dispatched turn still carries a
 * contract.</p>
 */
@Component
public class TurnExecutionContractBuilder {

    private static final Logger log = LoggerFactory.getLogger(TurnExecutionContractBuilder.class);

    private final QueryPlanBuilder queryPlanBuilder;
    private final SourceReferenceClassifier classifier;

    public TurnExecutionContractBuilder(QueryPlanBuilder queryPlanBuilder, SourceReferenceClassifier classifier) {
        this.queryPlanBuilder = queryPlanBuilder;
        this.classifier = classifier;
    }

    /**
     * Build a contract for one dispatched turn.
     *
     * @param resolution     resolved intent, or {@code null} for passthrough/general turns
     * @param userInput      the original user text
     * @param rewrittenInput the rewritten input (may be {@code null})
     * @param executionMode  requested runtime mode (may be {@code null}, defaults to REACT)
     * @return a non-null contract
     */
    public TurnExecutionContract build(IntentResolution resolution,
                                       String userInput,
                                       String rewrittenInput,
                                       AgentExecutionMode executionMode) {
        String originalText = StringUtils.hasText(userInput) ? userInput : "";
        IntentKind kind = resolution == null ? null : resolution.kind();
        IntentLabel primaryLabel = derivePrimaryIntentLabel(kind);
        SourceReferenceClassifier.SourceClassification classification = classifier.classify(originalText);
        SourceNeed sourceNeed = classifier.deriveSourceNeed(classification, kind);
        TimeSensitivity timeSensitivity = classifier.deriveTimeSensitivity(classification);
        TurnAnalysis analysis = new TurnAnalysis(
                originalText,
                primaryLabel,
                List.of(),
                sourceNeed,
                deriveToolNeed(kind, sourceNeed),
                timeSensitivity,
                ActionRisk.READ_ONLY,
                AmbiguityPlan.none(),
                deriveConfidence(resolution)
        );

        QueryPlan queryPlan = queryPlanBuilder.build(resolution, sourceNeed, originalText, rewrittenInput);
        IntentContract intent = new IntentContract(
                kind,
                primaryLabel,
                resolution,
                resolution == null ? "" : resolution.pathLabel()
        );
        RetrievalPlan retrieval = buildRetrievalPlan(resolution);
        ToolPolicy tools = new ToolPolicy(
                resolution == null ? List.of() : resolution.allowedTools(),
                isRetrievalVisible(kind, sourceNeed)
        );

        log.debug("Built turn contract: intentKind={}, label={}, sourceNeed={}, retrievalMode={}, retrievalSource={}",
                kind, primaryLabel, sourceNeed, retrieval.mode(), retrieval.source());

        return new TurnExecutionContract(
                TurnExecutionContract.VERSION,
                analysis,
                queryPlan,
                intent,
                ClarificationPlan.none(),
                retrieval,
                tools,
                MemoryPolicy.defaultRecall(),
                buildAnswerContract(retrieval),
                executionMode == null ? AgentExecutionMode.REACT : executionMode,
                OrderingPolicy.SESSION_SERIAL
        );
    }

    private IntentLabel derivePrimaryIntentLabel(IntentKind kind) {
        if (kind == null) {
            return IntentLabel.GENERAL_CHAT;
        }
        return switch (kind) {
            case KB -> IntentLabel.KB_QA;
            case TOOL -> IntentLabel.ACTION_REQUEST;
            case SYSTEM -> IntentLabel.GENERAL_CHAT;
            case CLARIFY -> IntentLabel.AMBIGUOUS_FOLLOW_UP;
        };
    }

    private ToolNeed deriveToolNeed(IntentKind kind, SourceNeed sourceNeed) {
        if (kind == IntentKind.TOOL) {
            return ToolNeed.REQUIRED;
        }
        // WEB turns require the web-search tool; they do not use RAG retrieval.
        if (sourceNeed == SourceNeed.WEB) {
            return ToolNeed.REQUIRED;
        }
        return ToolNeed.NONE;
    }

    private double deriveConfidence(IntentResolution resolution) {
        // Phase 1/2 have no calibrated confidence model; record a conservative neutral value.
        return resolution == null ? 0.3d : 0.6d;
    }

    private RetrievalPlan buildRetrievalPlan(IntentResolution resolution) {
        if (resolution == null || resolution.kind() != IntentKind.KB) {
            // Non-KB turns keep retrieval disabled in Phase 1; the current keyword gate still owns ALLOWED cases.
            return RetrievalPlan.disabled();
        }
        List<String> scopedKbIds = resolution.scopedKbIds();
        boolean hasScopedKbs = scopedKbIds != null && !scopedKbIds.isEmpty();
        RetrievalSource source = hasScopedKbs ? RetrievalSource.INTENT_KB : RetrievalSource.AGENT_DEFAULT_KB;
        RetrievalFallbackPolicy fallback = resolution.scopePolicy() == ScopePolicy.FALLBACK_ALLOWED
                ? RetrievalFallbackPolicy.AGENT_DEFAULT_KB
                : RetrievalFallbackPolicy.NONE;
        // Phase 1 records REQUIRED_BEFORE_ANSWER for KB intents but does not enforce it yet
        // (warn mode). Phase 3 enforces it and removes the keyword gate.
        return new RetrievalPlan(RetrievalMode.REQUIRED_BEFORE_ANSWER, source, scopedKbIds, fallback, true);
    }

    private boolean isRetrievalVisible(IntentKind kind, SourceNeed sourceNeed) {
        // RAG retrieval visibility: KB intent exposes the retrieval tool. WEB turns do NOT
        // use RAG (they use the web-search tool), so WEB must not make RAG visible.
        if (sourceNeed == SourceNeed.WEB) {
            return false;
        }
        return kind == IntentKind.KB || sourceNeed == SourceNeed.FILE || sourceNeed == SourceNeed.MIXED;
    }

    private AnswerContract buildAnswerContract(RetrievalPlan retrieval) {
        // Citation is required when the contract says KB evidence is used.
        return new AnswerContract(retrieval.citationRequired(), true, true);
    }
}
