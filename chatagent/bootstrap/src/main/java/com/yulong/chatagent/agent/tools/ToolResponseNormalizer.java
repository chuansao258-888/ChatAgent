package com.yulong.chatagent.agent.tools;

import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * ARRB Phase 1：把每个模型可见的 tool callback 结果规范化到统一的有界信封。
 * <p>
 * 计划要求（ARRB-AC-005）：每个模型可见的 callback 结果被规范化到最多
 * {@value #MAX_MODEL_VISIBLE_CODE_UNITS} UTF-16 code units，做 code-point-safe 截断，
 * 附带稳定截断标记；sanitized journal 只保留 full-response hash，metrics 只记低基数
 * truncated 计数，永不记 hash 或原始正文。
 * <p>
 * 这个组件是 coordinator 把原始 callback 文本变成"配对 observation"前的唯一边界，
 * 保证超大工具结果不会撑爆下一轮 prompt，也保证截断是可观测、可审计的。
 */
public final class ToolResponseNormalizer {

    /** 每个模型可见 callback 结果的 UTF-16 code unit 上限。 */
    public static final int MAX_MODEL_VISIBLE_CODE_UNITS = 12_000;

    /** 截断时附加的稳定标记，让模型/审计能识别"这条 observation 被截断了"。 */
    public static final String TRUNCATION_MARKER = "\n…[tool response truncated]";

    private ToolResponseNormalizer() {
    }

    /**
     * 把一个原始 callback 文本规范化成有界的模型可见 observation。
     *
     * @param rawText 原始回调返回文本；可为 null（视为空串）
     * @return 规范化结果，永远非空
     */
    public static NormalizedResponse normalize(String rawText) {
        String text = rawText == null ? "" : rawText;
        if (text.length() <= MAX_MODEL_VISIBLE_CODE_UNITS) {
            return new NormalizedResponse(text, false, sha256(text));
        }
        // Code-point-safe 截断：不要在一个 surrogate pair 中间断开。
        // UTF-16 String.length() 按 char(code unit) 计数； surrogate pair 占 2 个 char。
        // 从预算上限往回退到一个完整 code point 边界。
        int budget = MAX_MODEL_VISIBLE_CODE_UNITS - TRUNCATION_MARKER.length();
        if (budget < 0) {
            budget = 0;
        }
        int cut = budget;
        if (cut > 0 && cut < text.length()) {
            // 如果 cut 正好落在 high surrogate 上，回退一位避免拆开 surrogate pair。
            if (Character.isHighSurrogate(text.charAt(cut - 1))) {
                cut--;
            }
        }
        String truncated = text.substring(0, Math.max(0, cut)) + TRUNCATION_MARKER;
        return new NormalizedResponse(truncated, true, sha256(text));
    }

    /**
     * 把一个 Spring AI {@link ToolResponseMessage.ToolResponse} 的 responseData 规范化，
     * 保留 name/id，仅替换 responseData 为有界文本。
     */
    public static ToolResponseMessage.ToolResponse normalizeResponse(
            ToolResponseMessage.ToolResponse response) {
        NormalizedResponse normalized = normalize(response.responseData());
        return new ToolResponseMessage.ToolResponse(
                response.id(),
                response.name(),
                normalized.text());
    }

    /**
     * 把整条 {@link ToolResponseMessage} 的每个 response 规范化为有界 observation，
     * 返回一条新的 ToolResponseMessage（id 顺序与原始一致）。
     */
    public static ToolResponseMessage normalizeMessage(ToolResponseMessage message) {
        if (message == null || message.getResponses() == null || message.getResponses().isEmpty()) {
            return message;
        }
        java.util.List<ToolResponseMessage.ToolResponse> normalized = message.getResponses().stream()
                .map(ToolResponseNormalizer::normalizeResponse)
                .toList();
        return ToolResponseMessage.builder().responses(normalized).build();
    }

    /** 计算文本的 SHA-256（十六进制），用于 sanitized journal；永不输出原始正文。 */
    static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            // SHA-256 是 JDK 标准算法，不会缺失；任何异常都退化成定长占位，绝不抛出。
            return "sha256-unavailable";
        }
    }

    /**
     * 规范化后的模型可见 observation。
     *
     * @param text          有界文本（含截断标记，若被截断）
     * @param truncated     是否被截断（低基数 metric 用）
     * @param fullHash      原始正文的 SHA-256（仅进 sanitized journal，不进日志/metrics）
     */
    public record NormalizedResponse(String text, boolean truncated, String fullHash) {
    }
}
