import {
  DatabaseOutlined,
  FolderOpenOutlined,
  SettingOutlined,
} from "@ant-design/icons";
import { Card, Spin, Statistic, Typography, message } from "antd";
import { useEffect, useState } from "react";
import {
  getAssistantKnowledgeBases,
  getKnowledgeBases,
} from "../../../api/admin.ts";

interface OverviewState {
  totalKnowledgeBases: number;
  activeKnowledgeBases: number;
  assistantBindings: number;
}

export default function AdminOverviewPage() {
  const [loading, setLoading] = useState(true);
  const [overview, setOverview] = useState<OverviewState>({
    totalKnowledgeBases: 0,
    activeKnowledgeBases: 0,
    assistantBindings: 0,
  });

  useEffect(() => {
    let mounted = true;

    async function loadOverview() {
      setLoading(true);
      try {
        const [knowledgeBasesResponse, assistantResponse] = await Promise.all([
          getKnowledgeBases(),
          getAssistantKnowledgeBases(),
        ]);
        if (!mounted) {
          return;
        }
        setOverview({
          totalKnowledgeBases: knowledgeBasesResponse.knowledgeBases.length,
          activeKnowledgeBases: knowledgeBasesResponse.knowledgeBases.filter(
            (knowledgeBase) => knowledgeBase.status.toUpperCase() === "ACTIVE",
          ).length,
          assistantBindings: assistantResponse.knowledgeBases.length,
        });
      } catch (error) {
        console.error("Failed to load admin overview:", error);
        message.error("Unable to load admin overview right now.");
      } finally {
        if (mounted) {
          setLoading(false);
        }
      }
    }

    void loadOverview();
    return () => {
      mounted = false;
    };
  }, []);

  if (loading) {
    return (
      <div className="flex h-full min-h-[320px] items-center justify-center">
        <Spin size="large" />
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <div>
        <div className="text-xs uppercase tracking-[0.28em] text-white/40">
          Phase 1C
        </div>
        <Typography.Title level={2} className="!mb-0 !mt-2 !text-white">
          Admin workspace
        </Typography.Title>
        <Typography.Text className="block !text-white/60">
          This is the operating surface for knowledge-base lifecycle, document
          ingestion, and internal assistant binding.
        </Typography.Text>
      </div>

      <div className="grid gap-4 md:grid-cols-3">
        <Card className="shadow-admin-card">
          <Statistic
            title="Knowledge bases"
            value={overview.totalKnowledgeBases}
            prefix={<DatabaseOutlined />}
          />
        </Card>
        <Card className="shadow-admin-card">
          <Statistic
            title="Active today"
            value={overview.activeKnowledgeBases}
            prefix={<FolderOpenOutlined />}
          />
        </Card>
        <Card className="shadow-admin-card">
          <Statistic
            title="Assistant bindings"
            value={overview.assistantBindings}
            prefix={<SettingOutlined />}
          />
        </Card>
      </div>

      <div className="grid gap-4 md:grid-cols-[1.2fr_0.8fr]">
        <Card className="shadow-admin-card">
          <Typography.Title level={4} className="!mt-0 !text-white">
            What Phase 1C closes
          </Typography.Title>
          <div className="space-y-3 text-sm leading-7 text-white/60">
            <p>
              Knowledge bases now have a dedicated admin surface instead of the
              old placeholder route.
            </p>
            <p>
              Document uploads and replacements are routed through one explicit
              drawer backed by the Phase 1B ingestion pipeline.
            </p>
            <p>
              The internal assistant binding is editable from the admin area
              rather than hidden in backend-only flows.
            </p>
          </div>
        </Card>

        <Card className="!bg-white/[0.06] !border !border-white/[0.08] shadow-admin-card-dark">
          <Typography.Title level={4} className="!mt-0 !text-white">
            Next operator checks
          </Typography.Title>
          <div className="space-y-3 text-sm leading-7 text-white/60">
            <p>
              Upload one document to a new knowledge base and confirm parse
              status moves out of PENDING.
            </p>
            <p>
              Bind that knowledge base to the internal assistant from the
              Assistant page.
            </p>
            <p>
              Run one chat query that should hit the new document to complete
              the online smoke test.
            </p>
          </div>
        </Card>
      </div>
    </div>
  );
}
