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
        SourceNeed sourceNeed = deriveSourceNeed(kind, resolution);
        TurnAnalysis analysis = new TurnAnalysis(
                originalText,
                primaryLabel,
                List.of(),
                sourceNeed,
                deriveToolNeed(kind),
                TimeSensitivity.UNKNOWN,
                ActionRisk.READ_ONLY,
                AmbiguityPlan.none(),
                deriveConfidence(resolution)
        );

        QueryPlan queryPlan = buildQueryPlan(sourceNeed, rewrittenInput);
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

    private SourceNeed deriveSourceNeed(IntentKind kind, IntentResolution resolution) {
        if (kind == IntentKind.KB) {
            return SourceNeed.KB;
        }
        if (kind == IntentKind.TOOL) {
            // A TOOL intent may still reference uploaded assets; Phase 1 keeps it conservative.
            return SourceNeed.NONE;
        }
        return SourceNeed.NONE;
    }

    private ToolNeed deriveToolNeed(IntentKind kind) {
        if (kind == IntentKind.TOOL) {
            return ToolNeed.REQUIRED;
        }
        return ToolNeed.NONE;
    }

    private double deriveConfidence(IntentResolution resolution) {
        // Phase 1 has no calibrated confidence model; record a conservative neutral value.
        return resolution == null ? 0.3d : 0.6d;
    }

    private QueryPlan buildQueryPlan(SourceNeed sourceNeed, String rewrittenInput) {
        // Phase 1: only NONE or a single conservative query carrying the rewritten input.
        // Phase 2 will add preservation validation and MULTI_QUERY/DECOMPOSED.
        if (sourceNeed == SourceNeed.NONE || !StringUtils.hasText(rewrittenInput)) {
            return QueryPlan.none();
        }
        RetrievalSource source = sourceNeed == SourceNeed.KB ? RetrievalSource.INTENT_KB : RetrievalSource.SESSION_FILES;
        QuerySpec spec = new QuerySpec(rewrittenInput, source, List.of());
        return new QueryPlan(QueryPlanMode.SINGLE_QUERY, QueryOperation.QA, List.of(spec), List.of());
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
        // Mirrors current AgentToolCallbackFactory behavior: KB intent exposes the retrieval tool.
        // This field is recorded only in Phase 1; AgentToolCallbackFactory still reads IntentResolution.
        return kind == IntentKind.KB || sourceNeed != SourceNeed.NONE;
    }

    private AnswerContract buildAnswerContract(RetrievalPlan retrieval) {
        // Citation is required when the contract says KB evidence is used.
        return new AnswerContract(retrieval.citationRequired(), true, true);
    }
}
