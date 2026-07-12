package com.yulong.chatagent.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 覆盖 {@link SafeJsonSchemaValidator} 的安全边界。这些边界保证恶意/畸形 schema 无法触发
 * 网络 IO、ReDoS 或把异常泄露给调用方（ARRB-DEC-016, ARRB-AC-005/017）。
 */
class SafeJsonSchemaValidatorTest {

    private final SafeJsonSchemaValidator validator = new SafeJsonSchemaValidator(new ObjectMapper());

    @Test
    void validInstanceAgainstObjectSchemaPasses() {
        String schema = "{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}},\"required\":[\"q\"]}";
        String instance = "{\"q\":\"cats\"}";

        assertThat(validator.validate(schema, instance)).isEqualTo(SafeJsonSchemaValidator.Result.VALID);
    }

    @Test
    void missingRequiredFieldIsInvalid() {
        String schema = "{\"type\":\"object\",\"required\":[\"q\"]}";
        String instance = "{\"other\":1}";

        assertThat(validator.validate(schema, instance)).isEqualTo(SafeJsonSchemaValidator.Result.INVALID);
    }

    @Test
    void wrongTypeIsInvalid() {
        String schema = "{\"type\":\"object\"}";
        String instance = "[1,2,3]";

        assertThat(validator.validate(schema, instance)).isEqualTo(SafeJsonSchemaValidator.Result.INVALID);
    }

    @Test
    void nullSchemaReturnsNoSchemaAndNullInstanceReturnsNoInstance() {
        assertThat(validator.validate(null, "{}")).isEqualTo(SafeJsonSchemaValidator.Result.NO_SCHEMA);
        assertThat(validator.validate("{}", null)).isEqualTo(SafeJsonSchemaValidator.Result.NO_INSTANCE);
        assertThat(validator.validate("   ", "{}")).isEqualTo(SafeJsonSchemaValidator.Result.NO_SCHEMA);
    }

    @Test
    void invalidJsonNeverThrowsAndReturnsTypedCode() {
        assertThat(validator.validate("{not json", "{not json"))
                .isEqualTo(SafeJsonSchemaValidator.Result.INVALID_JSON);
    }

    @Test
    void oversizeSchemaIsRejectedBeforeParsing() {
        // 构造一个超过 MAX_SCHEMA_BYTES 的合法 schema 字符串。
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"object\",\"properties\":{");
        for (int i = 0; i < 4000; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("\"f").append(i).append("\":{\"type\":\"string\"}");
        }
        sb.append("}}");
        assertThat(sb.toString().getBytes().length)
                .isGreaterThan(SafeJsonSchemaValidator.MAX_SCHEMA_BYTES);

        assertThat(validator.validate(sb.toString(), "{}"))
                .isEqualTo(SafeJsonSchemaValidator.Result.SCHEMA_TOO_LARGE);
    }

    @Test
    void externalRefIsForbidden() {
        String schema = "{\"$ref\":\"https://evil.example/schema.json\"}";

        assertThat(validator.validate(schema, "{}"))
                .isEqualTo(SafeJsonSchemaValidator.Result.EXTERNAL_REF_FORBIDDEN);
    }

    @Test
    void inDocumentJsonPointerRefIsAllowed() {
        // 只允许 in-document JSON Pointer：这里通过 properties 内部引用 definitions。
        String schema = "{"
                + "\"type\":\"object\","
                + "\"properties\":{\"q\":{\"$ref\":\"#/definitions/s\"}},"
                + "\"definitions\":{\"s\":{\"type\":\"string\"}}"
                + "}";
        assertThat(validator.validate(schema, "{\"q\":\"ok\"}"))
                .isEqualTo(SafeJsonSchemaValidator.Result.VALID);
        assertThat(validator.validate(schema, "{\"q\":5}"))
                .isEqualTo(SafeJsonSchemaValidator.Result.INVALID);
    }

    @Test
    void regexPatternIsDisabledAsUnsupported() {
        // 未受信正则 schema 本阶段拒绝编译，避免 ReDoS（ARRB-DEFER-005）。
        String schema = "{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\",\"pattern\":\"^[a-z]+$\"}}}";

        assertThat(validator.validate(schema, "{\"q\":\"ok\"}"))
                .isEqualTo(SafeJsonSchemaValidator.Result.MCP_SCHEMA_REGEX_UNSUPPORTED);
    }

    @Test
    void patternPropertiesIsDisabledAsUnsupported() {
        String schema = "{\"type\":\"object\",\"patternProperties\":{\"^[a-z]+$\":{\"type\":\"string\"}}}";

        assertThat(validator.validate(schema, "{\"q\":\"ok\"}"))
                .isEqualTo(SafeJsonSchemaValidator.Result.MCP_SCHEMA_REGEX_UNSUPPORTED);
    }

    @Test
    void deeplyNestedSchemaIsRejected() {
        // 构造一个深度超过 MAX_SCHEMA_DEPTH 的嵌套 object schema。
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i <= SafeJsonSchemaValidator.MAX_SCHEMA_DEPTH + 1; i++) {
            sb.append("{\"type\":\"object\",\"properties\":{\"n\":");
        }
        sb.append("{\"type\":\"string\"}");
        for (int i = 0; i <= SafeJsonSchemaValidator.MAX_SCHEMA_DEPTH + 1; i++) {
            sb.append("}}");
        }
        // 字节数应该在限制内（关键是深度超限）。
        org.junit.jupiter.api.Assumptions.assumeTrue(
                sb.toString().getBytes().length <= SafeJsonSchemaValidator.MAX_SCHEMA_BYTES,
                "test schema should be within byte limit to isolate the depth check");

        assertThat(validator.validate(sb.toString(), "{}"))
                .isEqualTo(SafeJsonSchemaValidator.Result.SCHEMA_TOO_DEEP);
    }
}
