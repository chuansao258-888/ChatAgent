import {
  ArrowLeftOutlined,
  ReloadOutlined,
  UploadOutlined,
} from "@ant-design/icons";
import {
  Button,
  Card,
  Empty,
  Form,
  Input,
  Space,
  Table,
  Tag,
  Typography,
  message,
} from "antd";
import type { ColumnsType } from "antd/es/table";
import { useEffect, useMemo, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import {
  archiveKnowledgeBase,
  archiveKnowledgeDocument,
  getKnowledgeBase,
  getKnowledgeDocuments,
  restoreKnowledgeBase,
  updateKnowledgeBase,
} from "../../../api/admin.ts";
import type {
  KnowledgeBaseVO,
  KnowledgeDocumentVO,
} from "../../../types/admin.ts";
import DocumentUploadDrawer from "../DocumentUploadDrawer.tsx";
import { formatBytes, formatTimestamp, statusTone } from "../adminUtils.ts";

interface KnowledgeBaseFormValues {
  name: string;
  description?: string;
}

export default function KnowledgeBaseDetailPage() {
  const { kbId } = useParams<{ kbId: string }>();
  const navigate = useNavigate();
  const [form] = Form.useForm<KnowledgeBaseFormValues>();
  const [knowledgeBase, setKnowledgeBase] = useState<KnowledgeBaseVO | null>(
    null,
  );
  const [documents, setDocuments] = useState<KnowledgeDocumentVO[]>([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [replaceTarget, setReplaceTarget] =
    useState<KnowledgeDocumentVO | null>(null);

  const loadKnowledgeBase = async () => {
    if (!kbId) {
      return;
    }
    setLoading(true);
    try {
      const [knowledgeBaseResponse, documentsResponse] = await Promise.all([
        getKnowledgeBase(kbId),
        getKnowledgeDocuments(kbId),
      ]);
      setKnowledgeBase(knowledgeBaseResponse.knowledgeBase);
      setDocuments(documentsResponse.documents);
      form.setFieldsValue({
        name: knowledgeBaseResponse.knowledgeBase.name,
        description: knowledgeBaseResponse.knowledgeBase.description,
      });
    } catch (error) {
      console.error("Failed to load knowledge base detail:", error);
      message.error("Unable to load knowledge base detail.");
      navigate("/admin/knowledge-bases", { replace: true });
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void loadKnowledgeBase();
  }, [kbId]);

  const handleSave = async (values: KnowledgeBaseFormValues) => {
    if (!kbId) {
      return;
    }
    setSaving(true);
    try {
      await updateKnowledgeBase(kbId, values);
      message.success("Knowledge base updated.");
      await loadKnowledgeBase();
    } catch (error) {
      console.error("Failed to update knowledge base:", error);
      message.error("Unable to save knowledge base changes.");
    } finally {
      setSaving(false);
    }
  };

  const handleToggleArchive = async () => {
    if (!kbId || !knowledgeBase) {
      return;
    }
    try {
      if (knowledgeBase.status.toUpperCase() === "ARCHIVED") {
        await restoreKnowledgeBase(kbId);
        message.success("Knowledge base restored.");
      } else {
        await archiveKnowledgeBase(kbId);
        message.success("Knowledge base archived.");
      }
      await loadKnowledgeBase();
    } catch (error) {
      console.error("Failed to toggle knowledge base status:", error);
      message.error("Unable to update knowledge base status.");
    }
  };

  const handleArchiveDocument = async (documentId: string) => {
    if (!kbId) {
      return;
    }
    try {
      await archiveKnowledgeDocument(kbId, documentId);
      message.success("Document archived.");
      await loadKnowledgeBase();
    } catch (error) {
      console.error("Failed to archive document:", error);
      message.error("Unable to archive the document.");
    }
  };

  const columns = useMemo<ColumnsType<KnowledgeDocumentVO>>(
    () => [
      {
        title: "Document",
        dataIndex: "filename",
        key: "filename",
        render: (filename: string, record) => (
          <div>
            <div className="font-semibold text-white">{filename}</div>
            <div className="text-xs text-white/40">{record.mimeType}</div>
          </div>
        ),
      },
      {
        title: "Status",
        dataIndex: "parseStatus",
        key: "parseStatus",
        width: 140,
        render: (parseStatus: string) => (
          <Tag color={statusTone(parseStatus)}>{parseStatus}</Tag>
        ),
      },
      {
        title: "Size",
        dataIndex: "sizeBytes",
        key: "sizeBytes",
        width: 120,
        render: (sizeBytes: number) => formatBytes(sizeBytes),
      },
      {
        title: "Updated",
        dataIndex: "updatedAt",
        key: "updatedAt",
        width: 220,
        render: (updatedAt?: string) => formatTimestamp(updatedAt),
      },
      {
        title: "Action",
        key: "action",
        width: 200,
        render: (_, record) => (
          <Space>
            <Button
              size="small"
              onClick={() => {
                setReplaceTarget(record);
                setDrawerOpen(true);
              }}
            >
              Replace
            </Button>
            <Button
              size="small"
              danger
              onClick={() => {
                void handleArchiveDocument(record.id);
              }}
            >
              Archive
            </Button>
          </Space>
        ),
      },
    ],
    [],
  );

  if (!kbId) {
    return null;
  }

  return (
    <div className="space-y-6">
      <div className="flex flex-col gap-4 md:flex-row md:items-end md:justify-between">
        <div>
          <Link
            to="/admin/knowledge-bases"
            className="inline-flex items-center gap-2 text-sm font-medium text-white/60 hover:text-white"
          >
            <ArrowLeftOutlined />
            Back to knowledge bases
          </Link>
          <Typography.Title level={2} className="!mb-0 !mt-3 !text-white">
            {knowledgeBase?.name ?? "Knowledge base"}
          </Typography.Title>
          <Typography.Text className="block !text-white/60">
            Manage metadata, uploaded documents, and lifecycle state for this
            knowledge base.
          </Typography.Text>
        </div>
        <Space wrap>
          <Button
            icon={<ReloadOutlined />}
            className=""
            onClick={() => {
              void loadKnowledgeBase();
            }}
          >
            Refresh
          </Button>
          <Button
            icon={<UploadOutlined />}
            className=""
            onClick={() => {
              setReplaceTarget(null);
              setDrawerOpen(true);
            }}
            disabled={knowledgeBase?.status?.toUpperCase() === "ARCHIVED"}
          >
            Upload document
          </Button>
          <Button
            danger={knowledgeBase?.status?.toUpperCase() !== "ARCHIVED"}
            className=""
            onClick={() => {
              void handleToggleArchive();
            }}
          >
            {knowledgeBase?.status?.toUpperCase() === "ARCHIVED"
              ? "Restore"
              : "Archive"}
          </Button>
        </Space>
      </div>

      <div className="grid gap-4 md:grid-cols-[1.1fr_0.9fr]">
        <Card
          loading={loading}
          className="shadow-admin-card"
        >
          <Typography.Title level={4} className="!mt-0 !text-white">
            Knowledge base metadata
          </Typography.Title>
          <Form
            form={form}
            layout="vertical"
            onFinish={(values) => {
              void handleSave(values);
            }}
          >
            <Form.Item
              label="Name"
              name="name"
              rules={[
                {
                  required: true,
                  message: "Knowledge base name is required.",
                },
              ]}
            >
              <Input size="large" className="" />
            </Form.Item>
            <Form.Item label="Description" name="description">
              <Input.TextArea rows={4} className="" />
            </Form.Item>
            <Button
              htmlType="submit"
              type="primary"
              loading={saving}
              className=""
            >
              Save changes
            </Button>
          </Form>
        </Card>

        <Card
          loading={loading}
          className="!bg-white/[0.06] !border !border-white/[0.08] shadow-admin-card-dark"
        >
          <Typography.Title level={4} className="!mt-0 !text-white">
            Lifecycle snapshot
          </Typography.Title>
          <div className="space-y-4 text-sm leading-7 text-white/60">
            <div className="flex items-center justify-between">
              <span>Status</span>
              <Tag color={statusTone(knowledgeBase?.status)}>
                {knowledgeBase?.status ?? "UNKNOWN"}
              </Tag>
            </div>
            <div className="flex items-center justify-between">
              <span>Visibility</span>
              <span>{knowledgeBase?.visibility ?? "SHARED"}</span>
            </div>
            <div className="flex items-center justify-between">
              <span>Documents</span>
              <span>{documents.length}</span>
            </div>
            <div className="border-t border-white/10 pt-4">
              <div>Created: {formatTimestamp(knowledgeBase?.createdAt)}</div>
              <div>Updated: {formatTimestamp(knowledgeBase?.updatedAt)}</div>
            </div>
          </div>
        </Card>
      </div>

      <Card className="shadow-admin-card">
        <div className="mb-4 flex items-center justify-between">
          <div>
            <Typography.Title
              level={4}
              className="!mb-0 !mt-0 !text-white"
            >
              Uploaded documents
            </Typography.Title>
            <Typography.Text className="!text-white/60">
              Replace or archive documents without leaving the detail page.
            </Typography.Text>
          </div>
          <Tag color="blue">{documents.length} items</Tag>
        </div>

        {documents.length === 0 ? (
          <Empty description="No documents uploaded yet." />
        ) : (
          <Table
            rowKey="id"
            columns={columns}
            dataSource={documents}
            pagination={false}
          />
        )}
      </Card>

      <DocumentUploadDrawer
        open={drawerOpen}
        knowledgeBaseId={kbId}
        document={replaceTarget}
        onClose={() => {
          setDrawerOpen(false);
          setReplaceTarget(null);
        }}
        onSuccess={loadKnowledgeBase}
      />
    </div>
  );
}
