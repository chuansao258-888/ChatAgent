package com.yulong.chatagent.agent.tools;

/**
 * 一次工具回调的执行级别元数据（ARRB Phase 1），按 model-facing callback 名解析。
 * <p>
 * 把以下三个跨模式共享的判定集中到一处：
 * <ul>
 *   <li>{@link ToolEffectClass}：副作用分类，决定是否需要精确确认、能否自动重试、
 *       以及崩溃窗口如何归类（ARRB-DEC-017）；</li>
 *   <li>{@link DeadlineMode}：回调是否执行剩余 run 截止时间（ARRB-DEC-018）；</li>
 *   <li>{@code returnDirect}：沿用 Spring {@code ToolMetadata.returnDirect}，
 *       coordinator 仅在全部保留调用成功且全部回调声明 returnDirect 时才 honor 它。</li>
 * </ul>
 * 一个可选的 {@code perRunDispatchCap} 允许 owned adapter 声明比 run-wide 预算更紧的
 * 单工具派发上限；未设置（{@code <=0}）表示沿用 run-wide 上限。
 *
 * @param callbackName       model-facing callback 名（与 ToolDefinition.name() 一致）
 * @param effectClass        副作用分类
 * @param deadlineMode       截止时间执行模式
 * @param returnDirect       是否直接返回工具结果（Spring returnDirect 语义）
 * @param perRunDispatchCap  该工具单 run 派发上限；{@code <=0} 表示沿用 run-wide 上限
 */
public record ToolExecutionDescriptor(
        String callbackName,
        ToolEffectClass effectClass,
        DeadlineMode deadlineMode,
        boolean returnDirect,
        int perRunDispatchCap) {

    /**
     * 默认描述符：副作用 UNKNOWN、截止时间 UNSUPPORTED、returnDirect=false。
     * <p>
     * 用于 MCP 等尚未声明 effect policy 的回调，coordinator 据此走最保守路径。
     */
    public static ToolExecutionDescriptor unknown(String callbackName) {
        return new ToolExecutionDescriptor(callbackName, ToolEffectClass.UNKNOWN,
                DeadlineMode.UNSUPPORTED, false, 0);
    }

    /**
     * 只读 + 强制截止时间的便利构造。
     */
    public static ToolExecutionDescriptor readOnlyEnforced(String callbackName, boolean returnDirect) {
        return new ToolExecutionDescriptor(callbackName, ToolEffectClass.READ_ONLY,
                DeadlineMode.ENFORCED, returnDirect, 0);
    }

    public boolean hasPerRunDispatchCap() {
        return perRunDispatchCap > 0;
    }

    /**
     * 该描述符是否允许 coordinator 在不先精确确认的情况下派发。
     * 非 READ_ONLY 的副作用一律需要先确认；UNKNOWN 按 NON_IDEMPOTENT 处理。
     */
    public boolean requiresConfirmation() {
        return effectClass != ToolEffectClass.READ_ONLY;
    }

    /**
     * 该描述符是否允许一次有限自动重试（仅 READ_ONLY/IDEMPOTENT 且 typed retryable）。
     */
    public boolean retryable() {
        return effectClass == ToolEffectClass.READ_ONLY || effectClass == ToolEffectClass.IDEMPOTENT;
    }

    public String stableHash() {
        String value = callbackName + "|" + effectClass + "|" + deadlineMode
                + "|" + returnDirect + "|" + perRunDispatchCap;
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(
                    digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
