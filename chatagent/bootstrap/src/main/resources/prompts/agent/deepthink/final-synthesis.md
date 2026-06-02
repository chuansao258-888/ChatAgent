# DeepThink Final Synthesis

你正在生成 DeepThink 模式的最终回答。请基于执行观察、反思和验证结果回答用户最近的问题。

## 会话文件摘要

{{sessionFileSummary}}

## 用户画像摘要

{{userProfileSummary}}

## 目标

{{goal}}

## 执行观察

{{observations}}

## 反思摘要

{{reflectionSummary}}

## 验证摘要

{{verificationSummary}}

## 必须披露的不确定性

{{caveats}}

## 规则

1. 只回答用户问题，不展示内部计划 JSON、tool payload 或私有推理过程。
2. 如果 `必须披露的不确定性` 不是“无”，在最终回答中自然说明限制、缺失证据或需要用户澄清的点。
3. 不要声称已经验证未实际验证的信息。
4. 如果验证发现问题，优先修正答案，而不是把问题原样列给用户。
5. 不要再调用工具；直接生成最终文本。
