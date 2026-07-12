package com.yulong.chatagent.chat.routing;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Data
@Configuration
@ConfigurationProperties(prefix = "chat.routing")
public class ChatRoutingProperties {

    /** 兼容属性：旧普通路由请求优先提升的模型 id。Agent 路由使用 agentPrimaryModel。 */
    private String defaultModel = "glm-5.2";
    /** 兼容属性：旧 deepThinking 首选模型。Agent DeepThink 使用 agentPrimaryModel/agentFallbackModel。 */
    private String deepThinkingModel;
    /** Agent ReAct/DeepThink 首个尝试的模型 id。 */
    private String agentPrimaryModel = "glm-5.2";
    /** Agent ReAct/DeepThink 首包失败后尝试的备用模型 id。 */
    private String agentFallbackModel = "deepseek-v4-flash";
    /** 首包探测等待时间，超过后取消当前候选并尝试下一个模型。 */
    private int firstPacketTimeoutSeconds = 60;
    /** 流式决策整体等待时间，用于避免 collector 永久阻塞。 */
    private int decisionTotalTimeoutSeconds = 180;
    /** 用户可见最终回答流的整体等待时间，用于避免最终回答永久阻塞。 */
    private int streamTotalTimeoutSeconds = 300;
    /** 底层 HTTP 连接超时配置，供相关客户端配置使用。 */
    private int httpConnectTimeoutSeconds = 10;
    /** 底层 HTTP 读超时配置，通常要覆盖正常流式读取窗口。 */
    private int httpReadTimeoutSeconds = 65;

    /** 模型熔断与 HALF_OPEN 探针相关配置。 */
    private HealthConfig health = new HealthConfig();
    /** 健康检查/指标观测相关策略。 */
    private ObservabilityConfig observability = new ObservabilityConfig();
    /** 路由候选模型列表，按 priority 和首选模型提升规则参与选择。 */
    private List<CandidateConfig> candidates = new ArrayList<>();

    @PostConstruct
    public void validateTimings() {
        requirePositive(firstPacketTimeoutSeconds, "chat.routing.first-packet-timeout-seconds");
        requirePositive(health.failureThreshold, "chat.routing.health.failure-threshold");
        requirePositive(health.openDurationMs, "chat.routing.health.open-duration-ms");
        requirePositive(health.halfOpenFlightTimeoutMs,
                "chat.routing.health.half-open-flight-timeout-ms");
        long firstPacketTimeoutMs = TimeUnit.SECONDS.toMillis(firstPacketTimeoutSeconds);
        if (health.halfOpenFlightTimeoutMs < firstPacketTimeoutMs) {
            throw new IllegalStateException(
                    "chat.routing.health.half-open-flight-timeout-ms must not be shorter than "
                            + "chat.routing.first-packet-timeout-seconds");
        }
    }

    private static void requirePositive(long value, String propertyName) {
        if (value <= 0L) {
            throw new IllegalStateException(propertyName + " must be positive");
        }
    }

    @Data
    public static class CandidateConfig {
        /** 路由层模型 id，用于日志、熔断状态和首选模型匹配。 */
        private String id;
        /** 对应 ChatClientRegistry 中的 bean key */
        private String springClientKey;
        /** 值越小优先级越高；null 会被排序到最后。 */
        private Integer priority = 100;
        /** 是否参与路由；运行时 override 可临时开关。 */
        private Boolean enabled = true;
        /**
         * Optional explicit override. When null, provider capability auto-discovery is used.
         */
        private Boolean supportsThinking;
        /**
         * NONE | ANTHROPIC_THINKING | ZHIPU_THINKING_FLAG | MODEL_OVERRIDE
         */
        private String thinkingStrategy = "NONE";
        /**
         * Optional provider model override to use when deepThinking=true and strategy=MODEL_OVERRIDE.
         */
        private String thinkingModel;
    }

    @Data
    public static class HealthConfig {
        /** CLOSED 状态下连续失败达到该阈值后进入 OPEN。 */
        private int failureThreshold = 3;
        /** OPEN 状态保持多久；到期后下一次请求会进入 HALF_OPEN 探针。 */
        private long openDurationMs = 300_000L;
        /** HALF_OPEN 探针飞行超过该时间后，允许发起新一代探针。 */
        private long halfOpenFlightTimeoutMs = 120_000L;
    }

    @Data
    public static class ObservabilityConfig {
        /** 打开熔断器比例超过该值时可触发 warning 观测。 */
        private double openCircuitWarningRatio = 0.5D;
        /** 无任何可路由候选时，健康检查是否标记 down。 */
        private boolean downWhenNoRoutableCandidates = true;
        /** 所有可路由候选都 OPEN 时，健康检查是否标记 out-of-service。 */
        private boolean outOfServiceWhenAllRoutableCandidatesOpen = true;
        /** 运行时 override 指向不存在候选时是否告警。 */
        private boolean warnOnOrphanOverrides = true;
    }
}
