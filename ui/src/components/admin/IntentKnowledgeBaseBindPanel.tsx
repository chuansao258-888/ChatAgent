import { Select, Tag, Typography } from "antd";
import { useMemo } from "react";
import type { KnowledgeBaseVO } from "../../types/admin.ts";

interface IntentKnowledgeBaseBindPanelProps {
  knowledgeBases: KnowledgeBaseVO[];
  value?: string[];
  onChange?: (nextValue: string[]) => void;
}

export default function IntentKnowledgeBaseBindPanel({
  knowledgeBases,
  value,
  onChange,
}: IntentKnowledgeBaseBindPanelProps) {
  const activeKnowledgeBases = useMemo(
    () =>
      knowledgeBases.filter(
        (knowledgeBase) => knowledgeBase.status.toUpperCase() === "ACTIVE",
      ),
    [knowledgeBases],
  );

  return (
    <div className="space-y-3 rounded-section border border-white/[0.06] bg-white/[0.04] p-4">
      <div className="flex items-start justify-between gap-3">
        <div>
          <div className="text-sm font-semibold text-white">
            Knowledge base bindings
          </div>
          <Typography.Text className="block text-sm leading-6 !text-white/60">
            KB intents search only the knowledge bases bound here before any
            fallback policy is applied.
          </Typography.Text>
        </div>
        <Tag color="blue">{activeKnowledgeBases.length} active</Tag>
      </div>
      <Select
        mode="multiple"
        allowClear
        placeholder="Select active knowledge bases"
        className="w-full"
        value={value}
        onChange={(nextValue) => {
          onChange?.(nextValue);
        }}
        options={activeKnowledgeBases.map((knowledgeBase) => ({
          value: knowledgeBase.id,
          label: knowledgeBase.name,
        }))}
        optionFilterProp="label"
      />
    </div>
  );
}
