import {
  AppstoreOutlined,
  ClusterOutlined,
  RocketOutlined,
  SettingOutlined,
} from "@ant-design/icons";
import { Button, Card, Empty, Space, Spin, Tag, Typography, message } from "antd";
import { useEffect, useMemo, useState } from "react";
import {
  getAssistantTemplates,
  getKnowledgeBases,
  initializeAssistantFromTemplate,
} from "../../../api/admin.ts";
import type {
  AssistantTemplateVO,
  KnowledgeBaseVO,
} from "../../../types/admin.ts";
import TemplateInitDialog from "../TemplateInitDialog.tsx";

function summarizeTemplate(template: AssistantTemplateVO) {
  return template.intentTree.reduce(
    (summary, node) => {
      if (node.nodeLevel === "TOPIC") {
        summary.topics += 1;
      }
      if (node.intentKind === "KB") {
        summary.kbTopics += 1;
      }
      if (node.intentKind === "TOOL") {
        summary.toolTopics += 1;
      }
      if (node.intentKind === "SYSTEM") {
        summary.systemTopics += 1;
      }
      return summary;
    },
    { topics: 0, kbTopics: 0, toolTopics: 0, systemTopics: 0 },
  );
}

export default function AssistantTemplatePage() {
  const [loading, setLoading] = useState(true);
  const [initializing, setInitializing] = useState(false);
  const [templates, setTemplates] = useState<AssistantTemplateVO[]>([]);
  const [knowledgeBases, setKnowledgeBases] = useState<KnowledgeBaseVO[]>([]);
  const [selectedTemplate, setSelectedTemplate] =
    useState<AssistantTemplateVO | null>(null);

  const loadPageData = async () => {
    setLoading(true);
    try {
      const [templateResponse, knowledgeBaseResponse] = await Promise.all([
        getAssistantTemplates(),
        getKnowledgeBases(),
      ]);
      setTemplates(templateResponse.templates);
      setKnowledgeBases(knowledgeBaseResponse.knowledgeBases);
    } catch (error) {
      console.error("Failed to load assistant templates:", error);
      message.error("Unable to load assistant templates.");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void loadPageData();
  }, []);

  const activeKnowledgeBaseCount = useMemo(
    () =>
      knowledgeBases.filter(
        (knowledgeBase) => knowledgeBase.status.toUpperCase() === "ACTIVE",
      ).length,
    [knowledgeBases],
  );

  const handleInitialize = async (knowledgeBaseIds: string[]) => {
    if (!selectedTemplate) {
      return;
    }
    setInitializing(true);
    try {
      const response = await initializeAssistantFromTemplate(selectedTemplate.id, {
        knowledgeBaseIds,
      });
      message.success(
        `Initialized ${selectedTemplate.name}. Active intent version is now v${response.activeIntentVersion}.`,
      );
      setSelectedTemplate(null);
    } catch (error) {
      console.error("Failed to initialize assistant from template:", error);
      message.error("Unable to initialize the assistant from this template.");
    } finally {
      setInitializing(false);
    }
  };

  if (loading) {
    return (
      <div className="flex h-full min-h-[320px] items-center justify-center">
        <Spin size="large" />
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <div className="flex flex-col gap-4 md:flex-row md:items-end md:justify-between">
        <div>
          <div className="text-xs uppercase tracking-[0.28em] text-white/40">
            Admin / Assistant Templates
          </div>
          <Typography.Title level={2} className="!mb-0 !mt-2 !text-white">
            Assistant template starter kit
          </Typography.Title>
          <Typography.Text className="block !text-white/60">
            Start the single internal assistant from a curated template instead
            of hand-configuring prompt, tools, and intent tree every time.
          </Typography.Text>
        </div>
        <div className="rounded-section border border-white/[0.06] bg-white/[0.04] px-4 py-4 shadow-admin-card">
          <div className="text-xs uppercase tracking-[0.18em] text-white/40">
            Active knowledge bases ready for binding
          </div>
          <div className="mt-2 text-2xl font-semibold text-white">
            {activeKnowledgeBaseCount}
          </div>
        </div>
      </div>

      {templates.length === 0 ? (
        <Card className="shadow-admin-card">
          <Empty description="No assistant templates found." />
        </Card>
      ) : (
        <div className="grid gap-4 xl:grid-cols-2">
          {templates.map((template) => {
            const summary = summarizeTemplate(template);
            return (
              <Card
                key={template.id}
                className="shadow-admin-card"
              >
                <div className="flex h-full flex-col gap-5">
                  <div className="flex flex-wrap items-start justify-between gap-3">
                    <div>
                      <div className="flex flex-wrap items-center gap-2">
                        <Typography.Title
                          level={3}
                          className="!mb-0 !mt-0 !text-white"
                        >
                          {template.name}
                        </Typography.Title>
                        {template.builtIn ? <Tag color="blue">Built-in</Tag> : null}
                      </div>
                      <Typography.Text className="mt-2 block !text-white/60">
                        {template.description || "No description provided."}
                      </Typography.Text>
                    </div>
                    <Tag color="purple">{template.model}</Tag>
                  </div>

                  <div className="grid gap-3 md:grid-cols-2">
                    <div className="rounded-inset bg-white/[0.04] px-4 py-4">
                      <div className="flex items-center gap-2 text-xs uppercase tracking-[0.18em] text-white/40">
                        <ClusterOutlined />
                        Intent scope
                      </div>
                      <div className="mt-2 text-sm font-semibold text-white">
                        {summary.topics} topics
                      </div>
                      <div className="mt-1 text-xs text-white/60">
                        {summary.kbTopics} KB / {summary.toolTopics} TOOL /{" "}
                        {summary.systemTopics} SYSTEM
                      </div>
                    </div>
                    <div className="rounded-inset bg-white/[0.04] px-4 py-4">
                      <div className="flex items-center gap-2 text-xs uppercase tracking-[0.18em] text-white/40">
                        <SettingOutlined />
                        Optional tools
                      </div>
                      <div className="mt-2 text-sm font-semibold text-white">
                        {template.allowedTools.length || 0}
                      </div>
                      <div className="mt-1 text-xs text-white/60">
                        {template.allowedTools.join(", ") || "None"}
                      </div>
                    </div>
                  </div>

                  <div className="rounded-section border border-white/[0.06] bg-white/[0.04] px-4 py-4">
                    <div className="mb-2 flex items-center gap-2 text-xs uppercase tracking-[0.18em] text-white/40">
                      <AppstoreOutlined />
                      Prompt preview
                    </div>
                    <Typography.Paragraph
                      ellipsis={{ rows: 4 }}
                      className="!mb-0 !text-sm leading-7 !text-white/60"
                    >
                      {template.systemPrompt}
                    </Typography.Paragraph>
                  </div>

                  <div className="mt-auto flex items-center justify-between gap-3">
                    <Space wrap>
                      {template.allowedTools.map((toolName) => (
                        <Tag key={toolName}>{toolName}</Tag>
                      ))}
                    </Space>
                    <Button
                      type="primary"
                      icon={<RocketOutlined />}
                      className=""
                      onClick={() => {
                        setSelectedTemplate(template);
                      }}
                    >
                      Initialize assistant
                    </Button>
                  </div>
                </div>
              </Card>
            );
          })}
        </div>
      )}

      <TemplateInitDialog
        open={selectedTemplate !== null}
        template={selectedTemplate}
        knowledgeBases={knowledgeBases}
        loading={initializing}
        onClose={() => {
          setSelectedTemplate(null);
        }}
        onConfirm={handleInitialize}
      />
    </div>
  );
}
