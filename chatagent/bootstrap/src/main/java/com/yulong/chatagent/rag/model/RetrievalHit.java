package com.yulong.chatagent.rag.model;

/**
 * Structured retrieval result shared across different retrieval sources.
 *
 * <p>Phase 1A introduces this contract before knowledge-base retrieval lands,
 * so session-file search can stop leaking prompt-shaped strings into the rest
 * of the application.</p>
 *
 * <p>在当前 Agent runtime 中，RetrievalHit 是 SearchScopeResolver 输出给
 * RetrievalHitFormatter 的稳定结构。它还不是最终给模型看的文本，而是保留了来源、
 * 文档、chunk、分数等字段，方便后续同时生成 promptText 和 citations。</p>
 *
 * @param sourceType 来源类型：会话文件或知识库
 * @param sourceId 来源容器 ID，例如 session file id 或 knowledge base id
 * @param documentId 文档 ID，用于定位原始文档
 * @param documentName 文档展示名，优先给模型/前端显示
 * @param chunkIndex 文档切片序号，可能为空
 * @param sectionPath 文档章节路径或切片位置描述
 * @param content 命中 chunk 的正文
 * @param contextText 命中 chunk 的周边/父级上下文
 * @param score rerank 后的最终分数
 * @param scoreType 分数类型，例如 retrieval、fallback、filtered
 * @param isFallback 是否来自兜底排序或兜底召回
 */
public record RetrievalHit(
        RagSourceType sourceType,
        String sourceId,
        String documentId,
        String documentName,
        Integer chunkIndex,
        String sectionPath,
        String content,
        String contextText,
        Double score,
        String scoreType,
        boolean isFallback
) {
}
