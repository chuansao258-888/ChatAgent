import { Button, Drawer, Form, Input, InputNumber, Select, Switch, Tag, Typography } from "antd";
import { useEffect, useMemo } from "react";
import IntentKnowledgeBaseBindPanel from "./IntentKnowledgeBaseBindPanel.tsx";
import type {
  CreateIntentNodeRequest,
  IntentKind,
  IntentNodeLevel,
  IntentNodeVO,
  KnowledgeBaseVO,
  ScopePolicy,
  ToolVO,
  UpdateIntentNodeRequest,
} from "../../types/admin.ts";

export interface IntentNodeEditSubmitValue {
  payload: CreateIntentNodeRequest | UpdateIntentNodeRequest;
  knowledgeBaseIds: string[];
}

interface IntentNodeEditDrawerProps {
  open: boolean;
  mode: "create" | "edit";
  node?: IntentNodeVO | null;
  parentNode?: IntentNodeVO | null;
  knowledgeBases: KnowledgeBaseVO[];
  optionalTools: ToolVO[];
  onClose: () => void;
  onSubmit: (value: IntentNodeEditSubmitValue) => Promise<void> | void;
  submitting?: boolean;
}

interface IntentNodeFormValues {
  name: string;
  description?: string;
  examples: string[];
  intentKind?: IntentKind;
  scopePolicy?: ScopePolicy;
  allowedTools: string[];
  systemPromptOverride?: string;
  enabled: boolean;
  sortOrder?: number;
  knowledgeBaseIds: string[];
}

function deriveCreateLevel(parentNode?: IntentNodeVO | null): IntentNodeLevel {
  if (!parentNode) {
    return "DOMAIN";
  }
  if (parentNode.nodeLevel === "DOMAIN") {
    return "CATEGORY";
  }
  return "TOPIC";
}

function sanitizeString(value?: string): string | undefined {
  const trimmed = value?.trim();
  return trimmed ? trimmed : undefined;
}

function sanitizeNullableString(value?: string): string | null {
  const trimmed = value?.trim();
  return trimmed ? trimmed : null;
}

function sanitizeList(values?: string[]): string[] {
  return (values ?? [])
    .map((value) => value.trim())
    .filter(Boolean);
}

export default function IntentNodeEditDrawer({
  open,
  mode,
  node,
  parentNode,
  knowledgeBases,
  optionalTools,
  onClose,
  onSubmit,
  submitting = false,
}: IntentNodeEditDrawerProps) {
  const [form] = Form.useForm<IntentNodeFormValues>();
  const currentLevel: IntentNodeLevel =
    mode === "create"
      ? deriveCreateLevel(parentNode)
      : node?.nodeLevel ?? "TOPIC";
  const watchedIntentKind = Form.useWatch("intentKind", form);
  const isTopicNode = currentLevel === "TOPIC";
  const showKnowledgeBaseBindings = isTopicNode && watchedIntentKind === "KB";
  const showToolBindings = isTopicNode && watchedIntentKind === "TOOL";
  const showSystemPrompt = isTopicNode && watchedIntentKind === "SYSTEM";

  useEffect(() => {
    if (!open) {
      form.resetFields();
      return;
    }
    form.setFieldsValue({
      name: node?.name ?? "",
      description: node?.description ?? "",
      examples: node?.examples ?? [],
      intentKind: isTopicNode ? node?.intentKind ?? "KB" : undefined,
      scopePolicy: node?.scopePolicy ?? "FALLBACK_ALLOWED",
      allowedTools: node?.allowedTools ?? [],
      systemPromptOverride: node?.systemPromptOverride ?? "",
      enabled: node?.enabled ?? true,
      sortOrder: node?.sortOrder,
      knowledgeBaseIds: node?.knowledgeBaseIds ?? [],
    });
  }, [form, isTopicNode, node, open]);

  const toolOptions = useMemo(
    () =>
      optionalTools.map((tool) => ({
        value: tool.name,
        label: tool.name,
      })),
    [optionalTools],
  );

  const drawerTitle = mode === "create" ? "Create intent node" : "Edit intent node";

  const handleFinish = async (values: IntentNodeFormValues) => {
    const finalIntentKind = isTopicNode ? values.intentKind : undefined;
    const payload =
      mode === "create"
        ? ({
            parentId: parentNode?.id,
            nodeLevel: currentLevel,
            name: values.name.trim(),
            description: sanitizeString(values.description),
            examples: sanitizeList(values.examples),
            intentKind: finalIntentKind,
            scopePolicy:
              finalIntentKind === "KB"
                ? values.scopePolicy ?? "FALLBACK_ALLOWED"
                : undefined,
            allowedTools:
              finalIntentKind === "TOOL" ? sanitizeList(values.allowedTools) : [],
            systemPromptOverride:
              finalIntentKind === "SYSTEM"
                ? sanitizeString(values.systemPromptOverride)
                : undefined,
            enabled: values.enabled,
            sortOrder: values.sortOrder,
          } satisfies CreateIntentNodeRequest)
        : ({
            name: values.name.trim(),
            description: sanitizeNullableString(values.description),
            examples: sanitizeList(values.examples),
            intentKind: finalIntentKind,
            scopePolicy:
              finalIntentKind === "KB"
                ? values.scopePolicy ?? "FALLBACK_ALLOWED"
                : undefined,
            allowedTools:
              finalIntentKind === "TOOL" ? sanitizeList(values.allowedTools) : [],
            systemPromptOverride:
              finalIntentKind === "SYSTEM"
                ? sanitizeNullableString(values.systemPromptOverride)
                : null,
            enabled: values.enabled,
            sortOrder: values.sortOrder,
          } satisfies UpdateIntentNodeRequest);

    await onSubmit({
      payload,
      knowledgeBaseIds:
        finalIntentKind === "KB" ? sanitizeList(values.knowledgeBaseIds) : [],
    });
  };

  return (
    <Drawer
      open={open}
      onClose={onClose}
      width={560}
      title={drawerTitle}
      destroyOnClose
      className="admin-dark-drawer"
    >
      <div className="space-y-5">
        <div className="rounded-section border border-white/[0.06] bg-white/[0.04] px-5 py-4">
          <div className="flex flex-wrap items-center gap-2">
            <Tag color="geekblue">{currentLevel}</Tag>
            {parentNode ? <Tag color="default">Parent: {parentNode.name}</Tag> : <Tag color="default">Root node</Tag>}
          </div>
          <Typography.Text className="mt-3 block text-sm leading-6 !text-white/60">
            {isTopicNode
              ? "Topic nodes define runtime behavior. Choose whether this topic searches KBs, dispatches tools, or answers from a system template."
              : "Non-leaf nodes are used only for hierarchical routing and classification."}
          </Typography.Text>
        </div>

        <Form<IntentNodeFormValues>
          form={form}
          layout="vertical"
          onFinish={(values) => {
            void handleFinish(values);
          }}
        >
          <Form.Item
            label="Name"
            name="name"
            rules={[{ required: true, message: "Node name is required." }]}
          >
            <Input size="large" className="" placeholder="e.g. Leave Policy" />
          </Form.Item>

          <Form.Item label="Description" name="description">
            <Input.TextArea
              rows={3}
              className=""
              placeholder="Short operator-facing explanation for this node"
            />
          </Form.Item>

          <div className="grid gap-4 md:grid-cols-[1fr_160px]">
            <Form.Item label="Examples" name="examples">
              <Select
                mode="tags"
                tokenSeparators={[","]}
                placeholder="Add sample user queries"
                className="w-full"
              />
            </Form.Item>
            <Form.Item label="Sort order" name="sortOrder">
              <InputNumber min={0} className="!w-full" />
            </Form.Item>
          </div>

          <Form.Item label="Enabled" name="enabled" valuePropName="checked">
            <Switch />
          </Form.Item>

          {isTopicNode ? (
            <>
              <Form.Item
                label="Intent kind"
                name="intentKind"
                rules={[{ required: true, message: "Intent kind is required." }]}
              >
                <Select
                  options={[
                    { value: "KB", label: "KB" },
                    { value: "TOOL", label: "TOOL" },
                    { value: "SYSTEM", label: "SYSTEM" },
                  ]}
                />
              </Form.Item>

              {showKnowledgeBaseBindings ? (
                <>
                  <Form.Item label="Scope policy" name="scopePolicy">
                    <Select
                      options={[
                        { value: "STRICT", label: "STRICT" },
                        {
                          value: "FALLBACK_ALLOWED",
                          label: "FALLBACK_ALLOWED",
                        },
                      ]}
                    />
                  </Form.Item>
                  <Form.Item name="knowledgeBaseIds" className="mb-0">
                    <IntentKnowledgeBaseBindPanel knowledgeBases={knowledgeBases} />
                  </Form.Item>
                </>
              ) : null}

              {showToolBindings ? (
                <Form.Item label="Allowed tools" name="allowedTools">
                  <Select
                    mode="multiple"
                    allowClear
                    placeholder="Choose optional tools for this TOOL intent"
                    options={toolOptions}
                    optionFilterProp="label"
                  />
                </Form.Item>
              ) : null}

              {showSystemPrompt ? (
                <Form.Item
                  label="System prompt override"
                  name="systemPromptOverride"
                  rules={[
                    {
                      required: true,
                      message: "System intents should provide a response template.",
                    },
                  ]}
                >
                  <Input.TextArea
                    rows={6}
                    className=""
                    placeholder="Template or direct-response prompt used by the orchestrator"
                  />
                </Form.Item>
              ) : null}
            </>
          ) : null}

          <div className="flex items-center justify-end gap-3">
            <Button onClick={onClose} className="">
              Cancel
            </Button>
            <Button
              htmlType="submit"
              type="primary"
              loading={submitting}
              className="admin-primary-button"
            >
              {mode === "create" ? "Create node" : "Save changes"}
            </Button>
          </div>
        </Form>
      </div>
    </Drawer>
  );
}
