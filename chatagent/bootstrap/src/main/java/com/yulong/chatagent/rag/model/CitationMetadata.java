package com.yulong.chatagent.rag.model;

/**
 * Structured citation metadata attached to assistant messages and SSE payloads.
 * <p>
 * 它和 RetrievalHit 的字段高度相似，但语义不同：
 * RetrievalHit 是检索内部结果，CitationMetadata 是最终 assistant 消息 metadata 里给前端展示的引用来源。
 *
 * @param sourceType 来源类型，前端可用来显示“会话文件/知识库”
 * @param sourceId 来源容器 ID
 * @param documentId 文档 ID
 * @param documentName 文档名称
 * @param sectionPath 章节路径或 chunk 位置
 * @param chunkIndex chunk 序号
 * @param snippet 给引用面板展示的短摘录
 * @param score 检索/rerank 分数
 * @param scoreType 分数类型或过滤状态
 * @param isFallback 是否为兜底结果
 */
public record CitationMetadata(
        RagSourceType sourceType,
        String sourceId,
        String documentId,
        String documentName,
        String sectionPath,
        Integer chunkIndex,
        String snippet,
        Double score,
        String scoreType,
        boolean isFallback
) {
}
