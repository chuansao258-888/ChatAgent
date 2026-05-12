package com.yulong.chatagent.rag.application;

import com.yulong.chatagent.rag.model.CitationMetadata;

import java.util.List;

/**
 * Prompt text plus the aligned citation metadata rendered from retrieval hits.
 * <p>
 * 这是 RetrievalHitFormatter 的返回值，不直接对应数据库表：
 * promptText 进入 ToolResponseMessage 给模型读，citations 暂存在 CurrentTurnCitationHolder，
 * 等最终 assistant 消息落库时写入 metadata。
 *
 * @param promptText 带引用编号的证据文本，模型会在下一轮基于它回答
 * @param citations 与 promptText 中 [1]/[2] 顺序对齐的结构化引用元数据
 */
public record FormattedRetrievalPrompt(
        String promptText,
        List<CitationMetadata> citations
) {
}
