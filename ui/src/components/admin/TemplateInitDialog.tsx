import {
  Alert,
  Checkbox,
  Divider,
  Empty,
  Modal,
  Tag,
  Typography,
} from "antd";
import { useEffect, useMemo, useState } from "react";
import type { AssistantTemplateVO, KnowledgeBaseVO } from "../../types/admin.ts";

interface TemplateInitDialogProps {
  open: boolean;
  template: AssistantTemplateVO | null;
  knowledgeBases: KnowledgeBaseVO[];
  loading?: boolean;
  onClose: () => void;
  onConfirm: (knowledgeBaseIds: string[]) => Promise<void>;
}

function summarizeIntentTree(template: AssistantTemplateVO | null) {
  if (!template) {
    return { domains: 0, categories: 0, topics: 0 };
  }
  return template.intentTree.reduce(
    (summary, node) => {
      if (node.nodeLevel === "DOMAIN") {
        summary.domains += 1;
      } else if (node.nodeLevel === "CATEGORY") {
        summary.categories += 1;
      } else if (node.nodeLevel === "TOPIC") {
        summary.topics += 1;
      }
      return summary;
    },
    { domains: 0, categories: 0, topics: 0 },
  );
}

export default function TemplateInitDialog({
  open,
  template,
  knowledgeBases,
  loading = false,
  onClose,
  onConfirm,
}: TemplateInitDialogProps) {
  const [selectedKnowledgeBaseIds, setSelectedKnowledgeBaseIds] = useState<
    string[]
  >([]);

  useEffect(() => {
    if (!open) {
      setSelectedKnowledgeBaseIds([]);
    }
  }, [open, template?.id]);

  const activeKnowledgeBases = useMemo(
    () =>
      knowledgeBases.filter(
        (knowledgeBase) => knowledgeBase.status.toUpperCase() === "ACTIVE",
      ),
    [knowledgeBases],
  );

  const summary = useMemo(() => summarizeIntentTree(template), [template]);

  return (
    <Modal
      open={open}
      title={template ? `Initialize from ${template.name}` : "Initialize assistant"}
      okText="Initialize assistant"
      okButtonProps={{
        className: "",
        loading,
      }}
      cancelButtonProps={{ className: "" }}
      onCancel={onClose}
      onOk={() => {
        void onConfirm(selectedKnowledgeBaseIds);
      }}
      width={760}
      destroyOnHidden
    >
      {!template ? (
        <Empty description="Choose a template first." />
      ) : (
        <div className="space-y-5">
          <Alert
            type="warning"
            showIcon
            message="This action rewrites the internal assistant configuration"
            description="We will replace the assistant prompt, allowed tools, draft intent tree, publish a fresh snapshot, and refresh routing cache. Existing chat sessions stay intact."
          />

          <div className="grid gap-3 sm:grid-cols-3">
            <div className="rounded-inset bg-white/[0.04] px-4 py-4">
              <div className="text-xs uppercase tracking-[0.18em] text-white/40">
                Model
              </div>
              <div className="mt-2 text-base font-semibold text-white">
                {template.model}
              </div>
            </div>
            <div className="rounded-inset bg-white/[0.04] px-4 py-4">
              <div className="text-xs uppercase tracking-[0.18em] text-white/40">
                Intent tree
              </div>
              <div className="mt-2 text-base font-semibold text-white">
                {summary.domains} / {summary.categories} / {summary.topics}
              </div>
              <div className="mt-1 text-xs text-white/60">
                DOMAIN / CATEGORY / TOPIC
              </div>
            </div>
            <div className="rounded-inset bg-white/[0.04] px-4 py-4">
              <div className="text-xs uppercase tracking-[0.18em] text-white/40">
                Optional tools
              </div>
              <div className="mt-2 text-base font-semibold text-white">
                {template.allowedTools.length}
              </div>
              <div className="mt-1 text-xs text-white/60">
                {template.allowedTools.join(", ") || "No optional tools"}
              </div>
            </div>
          </div>

          <div>
            <Typography.Title level={5} className="!mb-2 !mt-0 !text-white">
              Assistant prompt
            </Typography.Title>
            <div className="rounded-inset border border-white/[0.06] bg-white/[0.04] px-4 py-4 text-sm leading-7 text-white/60">
              {template.systemPrompt}
            </div>
          </div>

          <div>
            <Typography.Title level={5} className="!mb-2 !mt-0 !text-white">
              Bind active knowledge bases
            </Typography.Title>
            <Typography.Text className="block !text-white/60">
              Templates do not guess KBs by name. Pick the active knowledge
              bases you want this initialized assistant to search.
            </Typography.Text>
            <div className="mt-3 rounded-section border border-white/[0.06] bg-white/[0.04] px-4 py-4">
              {activeKnowledgeBases.length === 0 ? (
                <Empty description="No active knowledge bases yet. You can still initialize the template and bind KBs later." />
              ) : (
                <Checkbox.Group
                  value={selectedKnowledgeBaseIds}
                  onChange={(checkedValues) => {
                    setSelectedKnowledgeBaseIds(checkedValues as string[]);
                  }}
                  className="!w-full"
                >
                  <div className="space-y-3">
                    {activeKnowledgeBases.map((knowledgeBase) => (
                      <label
                        key={knowledgeBase.id}
                        className="flex cursor-pointer items-start gap-4 rounded-inset border border-white/[0.06] bg-white/[0.04] px-4 py-4 transition hover:border-white/10"
                      >
                        <Checkbox value={knowledgeBase.id} className="!mt-1" />
                        <div className="min-w-0 flex-1">
                          <div className="flex flex-wrap items-center gap-2">
                            <span className="text-sm font-semibold text-white">
                              {knowledgeBase.name}
                            </span>
                            <Tag>{knowledgeBase.visibility}</Tag>
                          </div>
                          <div className="mt-2 text-sm text-white/60">
                            {knowledgeBase.description ||
                              "No description provided."}
                          </div>
                        </div>
                      </label>
                    ))}
                  </div>
                </Checkbox.Group>
              )}
            </div>
          </div>

          <Divider className="!my-3" />

          <div>
            <Typography.Title level={5} className="!mb-2 !mt-0 !text-white">
              Template structure preview
            </Typography.Title>
            <div className="space-y-2">
              {template.intentTree.map((node) => (
                <div
                  key={node.code}
                  className="rounded-input border border-white/[0.06] bg-white/[0.04] px-4 py-3"
                >
                  <div className="flex flex-wrap items-center gap-2">
                    <Tag color="blue">{node.nodeLevel}</Tag>
                    {node.intentKind ? <Tag color="purple">{node.intentKind}</Tag> : null}
                    <span className="text-sm font-semibold text-white">
                      {node.name}
                    </span>
                  </div>
                  {node.description ? (
                    <div className="mt-2 text-sm text-white/60">
                      {node.description}
                    </div>
                  ) : null}
                </div>
              ))}
            </div>
          </div>
        </div>
      )}
    </Modal>
  );
}

