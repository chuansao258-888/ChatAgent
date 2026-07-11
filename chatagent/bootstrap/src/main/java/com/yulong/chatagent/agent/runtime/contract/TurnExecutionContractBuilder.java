package com.yulong.chatagent.agent.runtime.contract;

import com.yulong.chatagent.agent.runtime.AgentExecutionMode;
import com.yulong.chatagent.intent.application.IntentResolution;
import com.yulong.chatagent.intent.application.ConfidenceStatus;
import com.yulong.chatagent.intent.application.IntentRouteOutcome;
import com.yulong.chatagent.intent.application.IntentUnderstandingResult;
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
 * <p>Derivation is deterministic: it combines the resolved intent and the
 * structured understanding result into the query, retrieval, tool, and answer
 * policies consumed by the runtime.</p>
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
        return build(resolution, userInput, rewrittenInput, executionMode, null);
    }

    public TurnExecutionContract build(IntentResolution resolution,
                                       String userInput,
                                       String rewrittenInput,
                                       AgentExecutionMode executionMode,
                                       IntentUnderstandingResult understandingResult) {
        String originalText = StringUtils.hasText(userInput) ? userInput : "";
        IntentKind kind = resolution == null ? null : resolution.kind();
        IntentLabel primaryLabel = derivePrimaryIntentLabel(kind, understandingResult);
        SourceReferenceClassifier.SourceClassification classification = classifier.classify(originalText);
        SourceNeed sourceNeed = understandingResult == null
                ? classifier.deriveSourceNeed(classification, kind) : understandingResult.sourceNeed();
        TimeSensitivity timeSensitivity = understandingResult == null
                ? classifier.deriveTimeSensitivity(classification) : understandingResult.timeSensitivity();
        ActionRisk actionRisk = understandingResult == null
                ? ActionRisk.READ_ONLY : understandingResult.actionRisk();
        AmbiguityPlan ambiguity = deriveAmbiguityPlan(understandingResult);
        double confidence = understandingResult == null
                ? deriveConfidence(resolution) : calibratedConfidence(understandingResult);
        TurnAnalysis analysis = new TurnAnalysis(
                originalText,
                understandingResult == null ? null : understandingResult.decision(),
                primaryLabel,
                understandingResult == null ? List.of() : understandingResult.secondaryIntents(),
                sourceNeed,
                deriveToolNeed(kind, sourceNeed),
                timeSensitivity,
                actionRisk,
                ambiguity,
                confidence
        );

        QueryPlan queryPlan = queryPlanBuilder.build(resolution, sourceNeed, originalText, rewrittenInput);
        IntentContract intent = new IntentContract(
                kind,
                primaryLabel,
                resolution,
                resolution == null ? "" : resolution.pathLabel()
        );
        RetrievalPlan retrieval = buildRetrievalPlan(resolution, sourceNeed);
        ToolPolicy tools = new ToolPolicy(
                resolution == null ? List.of() : resolution.allowedTools(),
                isRetrievalVisible(kind, sourceNeed)
        );

        log.debug("Built turn contract: intentKind={}, label={}, routeOutcome={}, sourceNeed={}, retrievalMode={}, retrievalSource={}",
                kind, primaryLabel,
                understandingResult == null ? "LEGACY" : understandingResult.decision().outcome(),
                sourceNeed, retrieval.mode(), retrieval.source());

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

    private IntentLabel derivePrimaryIntentLabel(IntentKind kind, IntentUnderstandingResult result) {
        if (result != null) {
            IntentRouteOutcome outcome = result.decision().outcome();
            if (outcome == IntentRouteOutcome.MULTI_INTENT) {
                return IntentLabel.MULTI_INTENT;
            }
            if (outcome == IntentRouteOutcome.AMBIGUOUS_ROUTE) {
                return IntentLabel.AMBIGUOUS_FOLLOW_UP;
            }
            if (outcome == IntentRouteOutcome.GENERAL_CHAT || outcome == IntentRouteOutcome.OUT_OF_DOMAIN) {
                return IntentLabel.GENERAL_CHAT;
            }
            if (outcome == IntentRouteOutcome.EXECUTION_INFO_MISSING
                    && result.actionRisk() != ActionRisk.READ_ONLY) {
                return IntentLabel.ACTION_REQUEST;
            }
        }
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

    private AmbiguityPlan deriveAmbiguityPlan(IntentUnderstandingResult result) {
        if (result == null || result.decision().outcome() != IntentRouteOutcome.AMBIGUOUS_ROUTE) {
            return AmbiguityPlan.none();
        }
        List<String> candidatePaths = result.decision().rankedCandidates().stream()
                .map(candidate -> candidate.path() == null || candidate.path().isBlank()
                        ? candidate.nodeId() : candidate.path())
                .toList();
        return new AmbiguityPlan(true, ClarificationKind.ROUTE_CHOICE, null, candidatePaths);
    }

    private double calibratedConfidence(IntentUnderstandingResult result) {
        return result.decision().confidenceStatus() == ConfidenceStatus.CALIBRATED
                ? result.decision().calibratedConfidence() : 0.0d;
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
        // Legacy callers have no calibrated confidence model; record a conservative neutral value.
        return resolution == null ? 0.3d : 0.6d;
    }

    private RetrievalPlan buildRetrievalPlan(IntentResolution resolution, SourceNeed sourceNeed) {
        if (sourceNeed == SourceNeed.NONE || sourceNeed == SourceNeed.WEB) {
            return RetrievalPlan.disabled();
        }
        List<String> scopedKbIds = resolution == null ? List.of() : resolution.scopedKbIds();
        boolean hasScopedKbs = scopedKbIds != null && !scopedKbIds.isEmpty();
        RetrievalSource source = switch (sourceNeed) {
            case FILE -> RetrievalSource.SESSION_FILES;
            case MIXED -> RetrievalSource.MIXED_SESSION_AND_KB;
            case KB -> hasScopedKbs ? RetrievalSource.INTENT_KB : RetrievalSource.AGENT_DEFAULT_KB;
            case NONE, WEB -> RetrievalSource.NONE;
        };
        RetrievalFallbackPolicy fallback = resolution != null
                && resolution.scopePolicy() == ScopePolicy.FALLBACK_ALLOWED
                ? RetrievalFallbackPolicy.AGENT_DEFAULT_KB
                : RetrievalFallbackPolicy.NONE;
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
