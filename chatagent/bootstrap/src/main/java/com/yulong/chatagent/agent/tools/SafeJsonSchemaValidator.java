package com.yulong.chatagent.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 唯一的共享 JSON Schema 校验器（ARRB Phase 1）。
 * <p>
 * 两个用途复用同一个边界：Phase 1 的 callback input preflight（无副作用、不调用回调即可判定
 * 参数是否匹配回调声明的 input schema），Phase 3 的 MCP output 校验（Draft 2020-12）。
 * <p>
 * 安全边界（ARRB-DEC-016）：
 * <ul>
 *   <li>schema 最多 {@value #MAX_SCHEMA_BYTES} UTF-8 字节、深度 {@value #MAX_SCHEMA_DEPTH}；</li>
 *   <li>只允许 in-document JSON Pointer {@code $ref}，禁用外部 schema 检索与自定义 format；</li>
 *   <li>未受信、含 {@code pattern}/{@code patternProperties}（正则）的 schema 直接判为
 *       {@link Result#MCP_SCHEMA_REGEX_UNSUPPORTED}，避免 JDK regex 的 ReDoS 风险；</li>
 *   <li>fail-fast/flag 输出，编译结果按 origin-policy + SHA-256 缓存（有界 256）；</li>
 *   <li>任何外部 {@code $ref}、过度复杂、非法 schema、缓存/校验失败都返回类型化的 safe code，
 *       绝不抛出、绝不 fetch URI、绝不把 schema URI 写进 INFO/WARN。</li>
 * </ul>
 * 调用方据此把 missing/invalid schema 的 effect 工具 fail-closed，只读工具返回 typed 配置错误。
 */
public final class SafeJsonSchemaValidator {

    /** 单个 schema 的 UTF-8 字节上限。 */
    public static final int MAX_SCHEMA_BYTES = 32_768;

    /** schema 允许的最大 JSON 深度。 */
    public static final int MAX_SCHEMA_DEPTH = 64;

    /** 编译结果缓存上限。 */
    public static final int MAX_CACHE_SIZE = 256;

    private final ObjectMapper objectMapper;
    private final SchemaRegistry registry;
    private final Map<String, Schema> compiledSchemas = java.util.Collections.synchronizedMap(
            new LinkedHashMap<>(MAX_CACHE_SIZE + 1, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Schema> eldest) {
                    return size() > MAX_CACHE_SIZE;
                }
            });

    /**
     * @param objectMapper 项目共享的 Jackson ObjectMapper（与 Spring Boot 3.5 的 Jackson 2 行一致）
     */
    public SafeJsonSchemaValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        // Draft 2020-12：MCP 在缺失 $schema 时规定使用它。外部 $ref 在解析前已被本类拒绝，
        // 因此 registry 不会发起任何网络/文件检索。
        this.registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
    }

    /**
     * 校验一个 JSON 实例是否匹配给定 schema。
     *
     * @param schemaJson   schema 的 JSON 文本；可为 {@code null}（返回 {@link Result#NO_SCHEMA}）
     * @param instanceJson 被校验实例的 JSON 文本；可为 {@code null}（返回 {@link Result#NO_INSTANCE}）
     * @return 类型化的校验结果，永远非空、绝不抛异常
     */
    public Result validate(String schemaJson, String instanceJson) {
        return validate(schemaJson, instanceJson, SchemaTrust.UNTRUSTED_MCP);
    }

    public Result validate(String schemaJson, String instanceJson, SchemaTrust trust) {
        if (schemaJson == null || schemaJson.isBlank()) {
            return Result.NO_SCHEMA;
        }
        if (instanceJson == null) {
            return Result.NO_INSTANCE;
        }
        if (schemaJson.getBytes(StandardCharsets.UTF_8).length > MAX_SCHEMA_BYTES) {
            return Result.SCHEMA_TOO_LARGE;
        }

        JsonNode schemaNode;
        JsonNode instanceNode;
        try {
            schemaNode = objectMapper.readTree(schemaJson);
            instanceNode = objectMapper.readTree(instanceJson);
        } catch (Exception e) {
            // 非法 JSON 视为配置错误，绝不向上抛。
            return Result.INVALID_JSON;
        }

        if (depthOf(schemaNode) > MAX_SCHEMA_DEPTH) {
            return Result.SCHEMA_TOO_DEEP;
        }
        if (containsExternalRef(schemaNode)) {
            return Result.EXTERNAL_REF_FORBIDDEN;
        }
        if (trust == SchemaTrust.UNTRUSTED_MCP && containsRegex(schemaNode)) {
            // 未受信正则 schema 在本阶段不编译，避免 ReDoS（ARRB-DEFER-005）。
            return Result.MCP_SCHEMA_REGEX_UNSUPPORTED;
        }

        try {
            String cacheKey = trust + ":" + ToolArgumentCanonicalizer.sha256(schemaJson);
            Schema schema = compiledSchemas.computeIfAbsent(cacheKey,
                    ignored -> registry.getSchema(schemaNode));
            List<Error> errors = schema.validate(instanceNode);
            return errors.isEmpty() ? Result.VALID : Result.INVALID;
        } catch (Exception e) {
            // 编译/校验失败统一映射成 SCHEMA_INVALID，绝不暴露内部异常细节。
            return Result.SCHEMA_INVALID;
        }
    }

    /** 计算一个 JSON 节点的最大嵌套深度。 */
    private static int depthOf(JsonNode node) {
        if (node == null || node.isNull() || node.isValueNode()) {
            return 0;
        }
        if (node.isObject()) {
            int max = 0;
            for (JsonNode child : node) {
                max = Math.max(max, depthOf(child));
            }
            return 1 + max;
        }
        if (node.isArray()) {
            int max = 0;
            for (JsonNode child : node) {
                max = Math.max(max, depthOf(child));
            }
            return 1 + max;
        }
        return 0;
    }

    /** 检测 schema 是否引用了外部资源（任何不以 {@code #} 开头的 {@code $ref} 或 {@code $id} 带 URI）。 */
    private static boolean containsExternalRef(JsonNode node) {
        if (node == null) {
            return false;
        }
        if (node.isObject()) {
            JsonNode ref = node.get("$ref");
            if (ref != null && ref.isTextual()) {
                String text = ref.asText();
                if (!text.startsWith("#")) {
                    return true;
                }
            }
            JsonNode id = node.get("$id");
            if (id != null && id.isTextual()) {
                String value = id.asText();
                if (!value.isBlank() && !value.startsWith("#")) {
                    return true;
                }
            }
            for (JsonNode child : node) {
                if (containsExternalRef(child)) {
                    return true;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                if (containsExternalRef(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 检测 schema 是否包含正则约束（pattern / patternProperties），本阶段一律拒绝编译。 */
    private static boolean containsRegex(JsonNode node) {
        if (node == null) {
            return false;
        }
        if (node.isObject()) {
            if (node.has("pattern") || node.has("patternProperties")) {
                return true;
            }
            for (JsonNode child : node) {
                if (containsRegex(child)) {
                    return true;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                if (containsRegex(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 类型化的校验结果。{@link #VALID} 是唯一表示"通过"的值，其余都是 typed safe code。 */
    public enum Result {
        VALID,
        INVALID,
        NO_SCHEMA,
        NO_INSTANCE,
        INVALID_JSON,
        SCHEMA_TOO_LARGE,
        SCHEMA_TOO_DEEP,
        EXTERNAL_REF_FORBIDDEN,
        MCP_SCHEMA_REGEX_UNSUPPORTED,
        SCHEMA_INVALID;

        public boolean isValid() {
            return this == VALID;
        }
    }

    public enum SchemaTrust {
        TRUSTED_BUILTIN,
        UNTRUSTED_MCP
    }
}
