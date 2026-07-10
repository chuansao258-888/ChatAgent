package com.yulong.chatagent.agent.runtime.contract;

/**
 * Test-only factory that wires the Phase 2 contract components with their
 * real collaborators, so tests do not repeat the 4-layer constructor chain.
 */
public final class ContractTestSupport {

    private ContractTestSupport() {
    }

    public static SourceReferenceClassifier classifier() {
        return new SourceReferenceClassifier();
    }

    public static QueryContentTokenExtractor contentTokenExtractor() {
        return new QueryContentTokenExtractor();
    }

    public static QueryRewritePreservationValidator validator() {
        return new QueryRewritePreservationValidator(classifier(), contentTokenExtractor());
    }

    public static QueryPlanBuilder queryPlanBuilder() {
        return new QueryPlanBuilder(validator(), classifier());
    }

    public static TurnExecutionContractBuilder contractBuilder() {
        return new TurnExecutionContractBuilder(queryPlanBuilder(), classifier());
    }
}
