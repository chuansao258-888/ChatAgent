package com.yulong.chatagent.agent.runtime.contract;

/**
 * The operation a {@link QueryPlan} is built to support.
 */
public enum QueryOperation {
    /** Plain question answering. */
    QA,
    /** Compare across sources or objects. */
    COMPARE,
    /** Summarize retrieved content. */
    SUMMARIZE,
    /** Extract specific facts from retrieved content. */
    EXTRACT,
    /** Verify a claim against retrieved content. */
    VERIFY
}
