package com.yulong.chatagent.agent.prompt;

/**
 * Prompt 模板路径集中注册表。
 * <p>
 * 所有路径都相对于 {@code classpath:prompts/}，避免业务代码里散落硬编码模板路径。
 */
public final class PromptConstants {

    private PromptConstants() {
    }

    // ── Agent 核心提示词 ───────────────────────────────────────────────
    public static final String AGENT_DEFAULT_SYSTEM = "agent/default-system-prompt.md";
    public static final String AGENT_DECISION_MODULE = "agent/decision-module.md";
    public static final String AGENT_FINAL_ANSWER = "agent/final-answer-module.md";

    // ── DeepThink 提示词 ──────────────────────────────────────────────
    public static final String DEEPTHINK_PLANNER = "agent/deepthink/planner.md";
    public static final String DEEPTHINK_STEP_EXECUTOR = "agent/deepthink/step-executor.md";
    public static final String DEEPTHINK_REFLECTION = "agent/deepthink/reflection.md";
    public static final String DEEPTHINK_VERIFICATION = "agent/deepthink/verification.md";
    public static final String DEEPTHINK_FINAL_SYNTHESIS = "agent/deepthink/final-synthesis.md";

    // Agent 条件片段：由 DefaultAgentRuntimeContextLoader 按运行时上下文拼装
    public static final String AGENT_MCP_TOOL_SAFETY = "agent/sections/mcp-tool-safety.md";
    public static final String AGENT_WEB_SEARCH_SAFETY = "agent/sections/web-search-safety.md";
    public static final String AGENT_TOOL_STRATEGY = "agent/sections/tool-strategy.md";
    public static final String AGENT_LATEST_TURN_GUIDANCE = "agent/sections/latest-turn-guidance.md";
    public static final String AGENT_INTENT_BOUNDARY_NARROWED = "agent/sections/intent-boundary-narrowed.md";
    public static final String AGENT_INTENT_BOUNDARY_KB_ONLY = "agent/sections/intent-boundary-kb-only.md";

    // ── 意图路由 ───────────────────────────────────────────────────────
    public static final String INTENT_CLASSIFIER = "intent/classifier.md";
    public static final String INTENT_STRUCTURED_CLASSIFIER = "intent/structured-classifier-v1.md";
    public static final String INTENT_QUERY_REWRITE = "intent/query-rewrite.md";

    // ── RAG 入库/切分 ──────────────────────────────────────────────────
    public static final String RAG_DOC_CLEANUP = "rag/ingestion/document-cleanup.md";
    public static final String RAG_DOC_METADATA = "rag/ingestion/document-metadata.md";
    public static final String RAG_CHUNK_CTX_SYSTEM = "rag/ingestion/chunk-context-system.md";
    public static final String RAG_CHUNK_CTX_USER = "rag/ingestion/chunk-context-user.md";

    // ── RAG 检索 ───────────────────────────────────────────────────────
    public static final String RAG_RERANKER_SYSTEM = "rag/retrieval/reranker-system.md";
    public static final String RAG_RERANKER_USER = "rag/retrieval/reranker-user-template.md";
    public static final String RAG_EVIDENCE_BLOCK = "rag/retrieval/evidence-block-template.md";

    // ── VLM 视觉解析 ───────────────────────────────────────────────────
    public static final String VLM_PARSE = "vlm/visual-parse.md";

    // ── 会话摘要 ───────────────────────────────────────────────────────
    public static final String SUMMARIZER_MEMORY = "summarizer/rolling-memory.md";
    public static final String SUMMARIZER_SEGMENT_MEMORY = "summarizer/segment-memory.md";

    // ── L3 长期记忆提取 ─────────────────────────────────────────────────
    public static final String L3_MEMORY_EXTRACTOR = "memory/l3-extractor.md";

    // ── fallback 文案 ──────────────────────────────────────────────────
    public static final String FALLBACK_SESSION_FILES = "fallbacks/session-files.md";
    public static final String FALLBACK_SESSION_SUMMARY = "fallbacks/session-summary.md";
    public static final String FALLBACK_SYSTEM_INTENT = "fallbacks/system-intent.md";
    public static final String FALLBACK_VLM_FAILURE = "fallbacks/vlm-failure.md";
}
