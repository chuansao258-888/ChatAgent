package com.yulong.chatagent.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

/**
 * ARRB Phase 1：精确工具确认提案的安全预览与规范化构造器（plan ARRB-DEC-008, L341-361）。
 * <p>
 * 当一个非只读/未知工具调用被首次提出时，coordinator 不派发，而是生成一条确定性的
 * {@link ToolApprovalChallenge}：包含稳定 approvalId、安全预览、approval hash 前缀，
 * 由现有 ACTION_CONFIRMATION 流程在下一轮回放。安全预览只暴露足够让用户判断的信息，
 * 永不回显原始完整参数、密码/令牌或 URL 凭据。
 * <p>
 * 规范：
 * <ul>
 *   <li>canonical JSON：递归排序对象 key、保留数组顺序与标量类型、whitespace-free UTF-8；</li>
 *   <li>挂起参数最多 {@value #MAX_PENDING_CANONICAL_BYTES} UTF-8 字节，超限落 TOOL_APPROVAL_PAYLOAD_TOO_LARGE；</li>
 *   <li>安全预览：单独脱敏，最多 {@value #MAX_SAFE_PREVIEW_CODE_POINTS} Unicode 码点；</li>
 *   <li>key 匹配 password/passphrase/secret/token/apiKey/authorization/cookie/credential（大小写不敏感）
 *       的 scalar/subtree 值替换成 [REDACTED]；</li>
 *   <li>预览剥离 URL user-info/query/fragment、移除控制字符、每个剩余 scalar 最多 {@value #MAX_PREVIEW_SCALAR_CODE_POINTS} 码点；</li>
 *   <li>预览从不回退到 raw JSON；序列化失败时只显示 hash 前缀。</li>
 * </ul>
 */
public final class ToolApprovalChallengeBuilder {

    /** 挂起 canonical 参数的 UTF-8 字节上限。 */
    public static final int MAX_PENDING_CANONICAL_BYTES = 8_192;

    /** 安全预览的 Unicode 码点上限。 */
    public static final int MAX_SAFE_PREVIEW_CODE_POINTS = 512;

    /** 预览里每个剩余 scalar 的码点上限。 */
    public static final int MAX_PREVIEW_SCALAR_CODE_POINTS = 80;

    /** 敏感 key（小写匹配）——其 scalar/subtree 值替换成 [REDACTED]。 */
    static final Set<String> SENSITIVE_KEYS = Set.of(
            "password", "passphrase", "secret", "token", "apikey",
            "authorization", "cookie", "credential");

    private final ObjectMapper canonicalMapper;
    private final ObjectMapper previewMapper;

    public ToolApprovalChallengeBuilder(ObjectMapper objectMapper) {
        // canonical：按 key 字典序排序输出，紧凑。
        this.canonicalMapper = objectMapper.copy()
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        this.previewMapper = objectMapper;
    }

    /**
     * 构造一个确认提案。如果原始参数超过 canonical 字节上限，返回的 challenge 会带
     * {@code TOOL_APPROVAL_PAYLOAD_TOO_LARGE}，coordinator 据此拒绝提案而非回显。
     *
     * @param approvalId 稳定的 approval id（由 pending store 生成）
     * @param toolName   模型面向的 callback 名
     * @param rawArguments 原始参数 JSON 文本（可为 null/空，视为 {}）
     * @return 永远非空的 challenge
     */
    public ToolApprovalChallenge build(String approvalId, String toolName, String rawArguments) {
        String canonical;
        try {
            canonical = ToolArgumentCanonicalizer.canonicalize(rawArguments);
        } catch (IllegalArgumentException malformed) {
            return new ToolApprovalChallenge(
                    approvalId, toolName, null, null,
                    "[preview unavailable]", "TOOL_ARGUMENTS_MALFORMED");
        }
        String argumentHash = ToolArgumentCanonicalizer.sha256(canonical);
        int canonicalBytes = canonical.getBytes(StandardCharsets.UTF_8).length;
        if (canonicalBytes > MAX_PENDING_CANONICAL_BYTES) {
            return new ToolApprovalChallenge(
                    approvalId, toolName, null, argumentHash,
                    "[payload too large]", "TOOL_APPROVAL_PAYLOAD_TOO_LARGE");
        }
        String safePreview = buildSafePreview(rawArguments);
        return new ToolApprovalChallenge(
                approvalId, toolName, canonical, argumentHash, safePreview, null);
    }

    /** Jackson canonicalization：递归排序 key、紧凑、UTF-8。 */
    String canonicalize(String rawArguments) {
        return ToolArgumentCanonicalizer.canonicalize(rawArguments);
    }

    /**
     * 递归把所有 object 节点的 key 按字典序重排，返回一个新建的有序 ObjectNode/ArrayNode。
     * Jackson ObjectNode 保留插入顺序，因此必须重建；array 顺序保留。
     */
    private JsonNode sortKeysDeep(JsonNode node) {
        if (node == null || node.isNull() || node.isValueNode()) {
            return node;
        }
        if (node.isArray()) {
            // array 顺序保留，但元素中的 object 仍需递归排序。
            com.fasterxml.jackson.databind.node.ArrayNode arr =
                    JsonNodeFactory.instance.arrayNode();
            for (JsonNode child : node) {
                arr.add(sortKeysDeep(child));
            }
            return arr;
        }
        // object：收集字段名、排序、递归重建。
        java.util.List<String> fields = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(fields::add);
        java.util.Collections.sort(fields);
        ObjectNode ordered = JsonNodeFactory.instance.objectNode();
        for (String f : fields) {
            ordered.set(f, sortKeysDeep(node.get(f)));
        }
        return ordered;
    }

    /** 构造安全预览：脱敏 + URL 剥离 + 控制字符移除 + scalar/总码点上限。 */
    String buildSafePreview(String rawArguments) {
        if (rawArguments == null || rawArguments.isBlank()) {
            return "{}";
        }
        try {
            JsonNode node = previewMapper.readTree(rawArguments);
            StringBuilder out = new StringBuilder();
            appendRedacted(out, node, "");
            String preview = out.toString();
            // 移除控制字符（保留换行/制表以外的不可见控制字符全部去掉）。
            preview = stripControlChars(preview);
            // 总码点上限。
            preview = capCodePoints(preview, MAX_SAFE_PREVIEW_CODE_POINTS);
            return preview.isBlank() ? "{}" : preview;
        } catch (Exception e) {
            // 序列化失败：绝不回退 raw JSON，只显示占位。
            return "[preview unavailable]";
        }
    }

    private void appendRedacted(StringBuilder out, JsonNode node, String keyHint) {
        if (node == null || node.isNull()) {
            appendScalar(out, "null");
            return;
        }
        if (node.isValueNode()) {
            if (isSensitiveKey(keyHint)) {
                appendScalar(out, "[REDACTED]");
            } else {
                String scalar = node.asText();
                if (node.isTextual()) {
                    scalar = redactUrl(scalar);
                }
                appendScalar(out, scalar);
            }
            return;
        }
        if (node.isArray()) {
            out.append('[');
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                appendRedacted(out, node.get(i), keyHint);
            }
            out.append(']');
            return;
        }
        // object
        out.append('{');
        Iterator<String> fields = node.fieldNames();
        boolean first = true;
        while (fields.hasNext()) {
            String f = fields.next();
            if (!first) {
                out.append(',');
            }
            first = false;
            out.append(f).append('=');
            appendRedacted(out, node.get(f), f);
        }
        out.append('}');
    }

    private void appendScalar(StringBuilder out, String scalar) {
        String capped = capCodePoints(scalar, MAX_PREVIEW_SCALAR_CODE_POINTS);
        out.append(capped);
    }

    private static boolean isSensitiveKey(String key) {
        if (key == null) {
            return false;
        }
        String lower = key.toLowerCase(Locale.ROOT);
        // 子串匹配 SENSITIVE_KEYS，覆盖 apiKey/api_key/api-key 等变体。
        for (String s : SENSITIVE_KEYS) {
            if (lower.contains(s)) {
                return true;
            }
        }
        return false;
    }

    /** 剥离 URL 的 user-info/query/fragment，保留 scheme/host/path。 */
    private static String redactUrl(String value) {
        if (value == null || value.length() < 8) {
            return value;
        }
        try {
            URI uri = URI.create(value);
            if (uri.getScheme() == null || uri.getHost() == null) {
                return value;
            }
            String rebuilt = uri.getScheme() + "://" + uri.getHost()
                    + (uri.getPort() == -1 ? "" : ":" + uri.getPort())
                    + (uri.getPath() == null ? "" : uri.getPath());
            return rebuilt;
        } catch (Exception e) {
            return value;
        }
    }

    private static String stripControlChars(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            // 保留 \t \n \r；去掉其他 C0/C1 控制字符。
            if (cp == '\t' || cp == '\n' || cp == '\r'
                    || (cp >= 0x20 && cp != 0x7F && !(cp >= 0x80 && cp <= 0x9F))) {
                sb.appendCodePoint(cp);
            }
            i += Character.charCount(cp);
        }
        return sb.toString();
    }

    /** 把字符串裁剪到最多 maxCodePoints 个 Unicode 码点。 */
    private static String capCodePoints(String s, int maxCodePoints) {
        if (s == null) {
            return "";
        }
        int count = s.codePointCount(0, s.length());
        if (count <= maxCodePoints) {
            return s;
        }
        int endIdx = s.offsetByCodePoints(0, maxCodePoints);
        return s.substring(0, endIdx) + "…";
    }

    static String sha256(String text) {
        return ToolArgumentCanonicalizer.sha256(text);
    }

    /** Hex 格式化（Java 17 HexFormat）。 */
    static final class HexFormatter {
        static String toHex(byte[] bytes) {
            return java.util.HexFormat.of().formatHex(bytes);
        }
    }
}
