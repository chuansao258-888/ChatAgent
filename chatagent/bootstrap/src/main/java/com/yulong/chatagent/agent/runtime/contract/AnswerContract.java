package com.yulong.chatagent.agent.runtime.contract;

/**
 * Answer constraints for the turn.
 *
 * <p>These are the generic guards that Phase 7 will keep in final repair once
 * domain-specific regexes are removed. Phase 1 only records the conservative
 * defaults.</p>
 *
 * @param citationRequired   whether evidence-backed answers must cite sources
 * @param matchUserLanguage  whether the answer must match the user's language
 * @param noToolCallMarkup   whether visible tool-call markup must be stripped
 */
public record AnswerContract(
        boolean citationRequired,
        boolean matchUserLanguage,
        boolean noToolCallMarkup
) {
    /** Default answer contract: match user language, strip tool markup, citation off by default. */
    public static AnswerContract defaults() {
        return new AnswerContract(false, true, true);
    }
}
