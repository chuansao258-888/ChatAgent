import { SaveOutlined } from "@ant-design/icons";
import { Button, Card, Checkbox, Empty, Spin, Typography, message } from "antd";
import { useEffect, useMemo, useState } from "react";
import {
  getAssistantKnowledgeBases,
  getKnowledgeBases,
  setAssistantKnowledgeBases,
} from "../../../api/admin.ts";
import type { KnowledgeBaseVO } from "../../../types/admin.ts";
import { statusTone } from "../adminUtils.ts";

export default function AssistantSettingsPage() {
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [knowledgeBases, setKnowledgeBases] = useState<KnowledgeBaseVO[]>([]);
  const [selectedKnowledgeBaseIds, setSelectedKnowledgeBaseIds] = useState<
    string[]
  >([]);

  const loadAssistantBindings = async () => {
    setLoading(true);
    try {
      const [knowledgeBasesResponse, assistantResponse] = await Promise.all([
        getKnowledgeBases(),
        getAssistantKnowledgeBases(),
      ]);
      setKnowledgeBases(knowledgeBasesResponse.knowledgeBases);
      setSelectedKnowledgeBaseIds(
        assistantResponse.knowledgeBases.map((knowledgeBase) => knowledgeBase.id),
      );
    } catch (error) {
      console.error("Failed to load assistant settings:", error);
      message.error("Unable to load assistant settings.");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void loadAssistantBindings();
  }, []);

  const activeKnowledgeBases = useMemo(
    () =>
      knowledgeBases.filter(
        (knowledgeBase) => knowledgeBase.status.toUpperCase() === "ACTIVE",
      ),
    [knowledgeBases],
  );

  const handleSave = async () => {
    setSaving(true);
    try {
      await setAssistantKnowledgeBases({
        knowledgeBaseIds: selectedKnowledgeBaseIds,
      });
      message.success("Assistant bindings updated.");
      await loadAssistantBindings();
    } catch (error) {
      console.error("Failed to save assistant bindings:", error);
      message.error("Unable to save assistant bindings.");
    } finally {
      setSaving(false);
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
    <div className="space-y-5">
      <div className="flex flex-col gap-3 md:flex-row md:items-end md:justify-between">
        <div>
          <div className="text-xs uppercase tracking-[0.28em] text-white/40">
            Admin / Assistant
          </div>
          <Typography.Title level={2} className="!mb-0 !mt-2 !text-white">
            Internal assistant bindings
          </Typography.Title>
          <Typography.Text className="block !text-white/60">
            Choose which active knowledge bases the fixed internal assistant can
            search.
          </Typography.Text>
        </div>
        <Button
          type="primary"
          icon={<SaveOutlined />}
          loading={saving}
          onClick={() => {
            void handleSave();
          }}
          className=""
        >
          Save bindings
        </Button>
      </div>

      <div className="grid gap-5 md:grid-cols-[1.15fr_0.85fr]">
        <Card className="shadow-admin-card">
          <Typography.Title level={4} className="!mt-0 !text-white">
            Active knowledge bases
          </Typography.Title>
          {activeKnowledgeBases.length === 0 ? (
            <Empty description="Create and activate a knowledge base first." />
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
                    className="flex cursor-pointer items-start gap-4 rounded-section border border-white/[0.06] bg-white/[0.04] px-4 py-4 transition hover:border-white/10"
                  >
                    <Checkbox value={knowledgeBase.id} className="!mt-1" />
                    <div className="min-w-0 flex-1">
                      <div className="flex flex-wrap items-center gap-2">
                        <span className="text-sm font-semibold text-white">
                          {knowledgeBase.name}
                        </span>
                        <span className="rounded-full bg-white/[0.08] px-2 py-0.5 text-[11px] uppercase tracking-[0.16em] text-white/60">
                          {knowledgeBase.visibility}
                        </span>
                        <span
                          className={`rounded-full px-2 py-0.5 text-[11px] uppercase tracking-[0.16em] ${
                            statusTone(knowledgeBase.status) === "green"
                              ? "bg-emerald-500/15 text-emerald-400"
                              : "bg-white/[0.06] text-white/60"
                          }`}
                        >
                          {knowledgeBase.status}
                        </span>
                      </div>
                      <div className="mt-2 text-sm leading-6 text-white/60">
                        {knowledgeBase.description || "No description provided."}
                      </div>
                    </div>
                  </label>
                ))}
              </div>
            </Checkbox.Group>
          )}
        </Card>

        <Card className="!bg-white/[0.06] !border !border-white/[0.08] shadow-admin-card-dark">
          <Typography.Title level={4} className="!mt-0 !text-white">
            Binding notes
          </Typography.Title>
          <div className="space-y-3 text-sm leading-7 text-white/60">
            <p>
              The chat entrypoint now uses one fixed internal assistant. Users do
              not select agents anymore.
            </p>
            <p>
              Only ACTIVE knowledge bases can be bound here. Archived entries
              stay visible in the catalog but are excluded from the assistant
              search scope.
            </p>
            <p>
              Phase 2 will further constrain retrieval scope through
              intent-driven policies; this page only controls the assistant-level
              default pool.
            </p>
          </div>
        </Card>
      </div>
    </div>
  );
}
