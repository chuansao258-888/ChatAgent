package com.yulong.chatagent.rag.application;

import com.yulong.chatagent.agent.prompt.PromptConstants;
import com.yulong.chatagent.agent.prompt.PromptLoader;
import com.yulong.chatagent.rag.model.CitationMetadata;
import com.yulong.chatagent.rag.model.RetrievalHit;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Renders structured retrieval hits into a stable prompt-friendly text block.
 * Keeps citation numbering aligned with the metadata returned alongside the prompt.
 * <p>
 * 这个类负责把“结构化检索结果”拆成两条输出：
 * <ul>
 *     <li>promptText：给 LLM 阅读的证据块，包含 [1]/[2] 编号和片段正文。</li>
 *     <li>citations：给前端/数据库使用的结构化引用元数据。</li>
 * </ul>
 * 两者的顺序必须一致，否则最终回答里的引用编号会和前端来源列表错位。
 */
@Component
public class RetrievalHitFormatter {

    private final PromptLoader promptLoader;

    public RetrievalHitFormatter(PromptLoader promptLoader) {
        this.promptLoader = promptLoader;
    }

    // 旧便捷入口已停用：当前调用方需要同时拿到 promptText 和 citations，
    // 因此统一走 formatWithCitations(...)。
    // public String formatForPrompt(List<RetrievalHit> hits) {
    //     return formatWithCitations(hits).promptText();
    // }

    /**
     * Formats retrieval hits for the model prompt and preserves citation metadata for downstream rendering.
     *
     * @param hits retrieval results ordered by relevance
     * @return prompt text plus the matching citation metadata list
     */
    public FormattedRetrievalPrompt formatWithCitations(List<RetrievalHit> hits) {
        return formatWithCitations(hits, 1);
    }

    /**
     * Formats a retrieval batch using a turn-wide citation number offset.
     *
     * @param hits retrieval results ordered by relevance
     * @param firstCitationNumber first number assigned to visible evidence in this batch
     * @return prompt text plus citation metadata in the same order
     */
    public FormattedRetrievalPrompt formatWithCitations(List<RetrievalHit> hits, int firstCitationNumber) {
        if (firstCitationNumber < 1) {
            throw new IllegalArgumentException("firstCitationNumber must be at least 1");
        }
        if (hits == null || hits.isEmpty()) {
            // 空结果也返回一段明确文本给模型，避免模型误以为工具调用失败或继续编造证据。
            return new FormattedRetrievalPrompt(
                    "No relevant attached session-file content found.",
                    List.of()
            );
        }

        List<String> sections = new ArrayList<>(hits.size());
        List<CitationMetadata> citations = new ArrayList<>(hits.size());
        for (RetrievalHit hit : hits) {
            if (!shouldIncludeInPrompt(hit)) {
                continue;
            }
            int citationNumber = firstCitationNumber + sections.size();
            // 只为实际暴露给模型的证据生成 citation，保证编号连续且与 metadata 顺序一致。
            citations.add(toCitationMetadata(hit));
            sections.add(formatSingleHit(hit, citationNumber));
        }
        if (sections.isEmpty()) {
            // 如果所有命中都被标记为 filtered，就不要把任何证据暴露给模型，也不要返回 citations。
            return new FormattedRetrievalPrompt(
                    "No relevant attached session-file content found.",
                    List.of()
            );
        }
        // 模板中包含引用使用规则，提醒模型回答时必须使用实际出现的 [n]。
        String prompt = promptLoader.render(PromptConstants.RAG_EVIDENCE_BLOCK, Map.of(
                "evidenceSections", String.join("\n\n---\n\n", sections)
        )).trim();
        return new FormattedRetrievalPrompt(prompt, List.copyOf(citations));
    }

    private boolean shouldIncludeInPrompt(RetrievalHit hit) {
        // filtered 命中可以保留在内部流程中，但不能出现在给模型的证据块里。
        return hit != null && !"filtered".equals(hit.scoreType());
    }

    /**
     * Renders a single retrieval chunk into the numbered block consumed by the answer prompt.
     */
    private String formatSingleHit(RetrievalHit hit, int citationNumber) {
        List<String> lines = new ArrayList<>();
        // 第一行必须稳定包含 [n] 和 Source，模型引用时就用这个编号。
        lines.add("[" + citationNumber + "] Source: " + buildSourceLabel(hit));
        if (StringUtils.hasText(hit.sectionPath())) {
            lines.add("Section: " + hit.sectionPath());
        }
        if (StringUtils.hasText(hit.contextText())) {
            // contextText 通常是父章节/相邻片段等上下文，帮助模型理解 chunk 的位置和语义。
            lines.add("Chunk Context:\n" + hit.contextText());
        }
        if (StringUtils.hasText(hit.content())) {
            lines.add("Chunk Content:\n" + hit.content());
        } else {
            // 即使正文为空也保留字段名，保证 prompt 结构稳定，便于模型和测试断言处理。
            lines.add("Chunk Content:\n");
        }
        return String.join("\n", lines);
    }

    private CitationMetadata toCitationMetadata(RetrievalHit hit) {
        // CitationMetadata 是最终 assistant message.metadata.citations 的元素，
        // 前端可以用它渲染来源名称、章节、snippet、分数和 fallback 状态。
        return new CitationMetadata(
                hit.sourceType(),
                hit.sourceId(),
                hit.documentId(),
                hit.documentName(),
                hit.sectionPath(),
                hit.chunkIndex(),
                buildSnippet(hit),
                hit.score(),
                hit.scoreType(),
                hit.isFallback()
        );
    }

    private String buildSnippet(RetrievalHit hit) {
        // snippet 优先取 chunk 正文；如果正文为空，再退回 contextText。
        String snippet = StringUtils.hasText(hit.content()) ? hit.content() : hit.contextText();
        if (!StringUtils.hasText(snippet)) {
            return "";
        }
        // 前端引用面板只需要短摘要，过长会影响消息 metadata 体积和 UI 可读性。
        String normalized = snippet.replaceAll("\\s+", " ").trim();
        int maxChars = 180;
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, maxChars - 3).trim() + "...";
    }

    private String buildSourceLabel(RetrievalHit hit) {
        StringBuilder builder = new StringBuilder();
        // documentName 面向用户更友好；没有名称时用 documentId 兜底，仍能定位来源。
        String documentName = StringUtils.hasText(hit.documentName()) ? hit.documentName() : hit.documentId();
        if (StringUtils.hasText(documentName)) {
            builder.append(documentName);
        } else {
            builder.append("Unknown");
        }
        // sourceType 明确区分“会话文件”和“知识库”，方便模型和前端理解来源类型。
        builder.append(" [").append(hit.sourceType()).append(']');
        if (hit.chunkIndex() != null) {
            builder.append(" chunk ").append(hit.chunkIndex());
        }
        return builder.toString();
    }
}
