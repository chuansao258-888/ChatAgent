package com.yulong.chatagent.agent.prompt;

/**
 * Central registry of all prompt template paths.
 * <p>
 * Every path is relative to {@code classpath:prompts/}.
 */
public final class PromptConstants {

    private PromptConstants() {
    }

    // ── Agent ──────────────────────────────────────────────────────────
    public static final String AGENT_DEFAULT_SYSTEM = "agent/default-system-prompt.md";
    public static final String AGENT_DECISION_MODULE = "agent/decision-module.md";
    public static final String AGENT_FINAL_ANSWER = "agent/final-answer-module.md";

    // Agent sections (conditionally assembled)
    public static final String AGENT_MCP_TOOL_SAFETY = "agent/sections/mcp-tool-safety.md";
    public static final String AGENT_TOOL_STRATEGY = "agent/sections/tool-strategy.md";
    public static final String AGENT_LATEST_TURN_GUIDANCE = "agent/sections/latest-turn-guidance.md";
    public static final String AGENT_INTENT_BOUNDARY_NARROWED = "agent/sections/intent-boundary-narrowed.md";
    public static final String AGENT_INTENT_BOUNDARY_KB_ONLY = "agent/sections/intent-boundary-kb-only.md";

    // ── Intent ─────────────────────────────────────────────────────────
    public static final String INTENT_CLASSIFIER = "intent/classifier.md";
    public static final String INTENT_QUERY_REWRITE = "intent/query-rewrite.md";

    // ── RAG — Ingestion ───────────────────────────────────────────────
    public static final String RAG_DOC_CLEANUP = "rag/ingestion/document-cleanup.md";
    public static final String RAG_DOC_METADATA = "rag/ingestion/document-metadata.md";
    public static final String RAG_CHUNK_CTX_SYSTEM = "rag/ingestion/chunk-context-system.md";
    public static final String RAG_CHUNK_CTX_USER = "rag/ingestion/chunk-context-user.md";

    // ── RAG — Retrieval ───────────────────────────────────────────────
    public static final String RAG_RERANKER_SYSTEM = "rag/retrieval/reranker-system.md";
    public static final String RAG_RERANKER_USER = "rag/retrieval/reranker-user-template.md";
    public static final String RAG_EVIDENCE_BLOCK = "rag/retrieval/evidence-block-template.md";

    // ── VLM ────────────────────────────────────────────────────────────
    public static final String VLM_PARSE = "vlm/visual-parse.md";

    // ── Summarizer ─────────────────────────────────────────────────────
    public static final String SUMMARIZER_MEMORY = "summarizer/rolling-memory.md";

    // ── Fallbacks ──────────────────────────────────────────────────────
    public static final String FALLBACK_SESSION_FILES = "fallbacks/session-files.md";
    public static final String FALLBACK_SESSION_SUMMARY = "fallbacks/session-summary.md";
    public static final String FALLBACK_USER_PROFILE = "fallbacks/user-profile.md";
    public static final String FALLBACK_SYSTEM_INTENT = "fallbacks/system-intent.md";
    public static final String FALLBACK_VLM_FAILURE = "fallbacks/vlm-failure.md";
}
