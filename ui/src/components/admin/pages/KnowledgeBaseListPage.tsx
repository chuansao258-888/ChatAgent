import { PlusOutlined, ReloadOutlined } from "@ant-design/icons";
import {
  Button,
  Card,
  Form,
  Input,
  Popconfirm,
  Space,
  Table,
  Tag,
  Typography,
  message,
} from "antd";
import type { ColumnsType } from "antd/es/table";
import { useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  createKnowledgeBase,
  deleteKnowledgeBase,
  getKnowledgeBases,
} from "../../../api/admin.ts";
import type { KnowledgeBaseVO } from "../../../types/admin.ts";
import { formatTimestamp } from "../adminUtils.ts";

interface CreateKnowledgeBaseFormValues {
  name: string;
  description?: string;
}

export default function KnowledgeBaseListPage() {
  const navigate = useNavigate();
  const [form] = Form.useForm<CreateKnowledgeBaseFormValues>();
  const [knowledgeBases, setKnowledgeBases] = useState<KnowledgeBaseVO[]>([]);
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);

  const loadKnowledgeBases = async () => {
    setLoading(true);
    try {
      const response = await getKnowledgeBases();
      setKnowledgeBases(response);
    } catch (error) {
      console.error("Failed to load knowledge bases:", error);
      message.error("Unable to load knowledge bases.");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void loadKnowledgeBases();
  }, []);

  const handleDelete = async (knowledgeBaseId: string) => {
    try {
      await deleteKnowledgeBase(knowledgeBaseId);
      message.success("Knowledge base deleted.");
      await loadKnowledgeBases();
    } catch (error) {
      console.error("Failed to delete knowledge base:", error);
      message.error("Unable to delete the knowledge base.");
    }
  };

  const columns = useMemo<ColumnsType<KnowledgeBaseVO>>(
    () => [
      {
        title: "Knowledge base",
        dataIndex: "name",
        key: "name",
        render: (_, record) => (
          <button
            type="button"
            className="text-left text-[15px] font-semibold text-white transition hover:text-white/80"
            onClick={() => {
              navigate(`/admin/knowledge-bases/${record.id}`);
            }}
          >
            {record.name}
          </button>
        ),
      },
      {
        title: "Description",
        dataIndex: "description",
        key: "description",
        render: (description?: string) => description || "No description yet",
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
        width: 160,
        render: (_, record) => (
          <Popconfirm
            title="Delete this knowledge base?"
            description="This will delete the knowledge base, its documents, chunks, vector index, and stored source files."
            okText="Delete"
            cancelText="Cancel"
            okButtonProps={{ danger: true }}
            onConfirm={() => {
              void handleDelete(record.id);
            }}
          >
            <Button size="small" danger>
              Delete
            </Button>
          </Popconfirm>
        ),
      },
    ],
    [navigate],
  );

  const handleCreate = async (values: CreateKnowledgeBaseFormValues) => {
    setSubmitting(true);
    try {
      const response = await createKnowledgeBase(values);
      message.success("Knowledge base created.");
      form.resetFields();
      await loadKnowledgeBases();
      navigate(`/admin/knowledge-bases/${response}`);
    } catch (error) {
      console.error("Failed to create knowledge base:", error);
      message.error("Unable to create the knowledge base.");
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="space-y-5">
      <div className="flex flex-col gap-3 md:flex-row md:items-end md:justify-between">
        <div>
          <div className="text-xs uppercase tracking-[0.28em] text-white/40">
            Admin / Knowledge Bases
          </div>
          <Typography.Title level={2} className="!mb-0 !mt-2 !text-white">
            Knowledge base catalog
          </Typography.Title>
          <Typography.Text className="block !text-white/60">
            Create, delete, and inspect the knowledge assets available for the
            internal assistant.
          </Typography.Text>
        </div>
        <Button
          icon={<ReloadOutlined />}
          className=""
          onClick={() => {
            void loadKnowledgeBases();
          }}
        >
          Refresh
        </Button>
      </div>

      <div className="flex flex-col gap-5">
        <Card className="shadow-admin-card">
          <div className="mb-5 flex items-center gap-3">
            <div className="flex h-11 w-11 items-center justify-center rounded-input bg-white/[0.08] text-white/60">
              <PlusOutlined />
            </div>
            <div>
              <div className="text-base font-semibold text-white">
                Create knowledge base
              </div>
              <div className="text-sm text-white/60">
                Spin up a new persistent knowledge asset before uploading documents.
              </div>
            </div>
          </div>

          <Form
            form={form}
            layout="vertical"
            onFinish={(values) => {
              void handleCreate(values);
            }}
          >
            <div className="grid gap-4 md:grid-cols-[1fr_1.2fr_auto]">
              <Form.Item
                label="Name"
                name="name"
                className="mb-0"
                rules={[
                  {
                    required: true,
                    message: "Knowledge base name is required.",
                  },
                ]}
              >
                <Input
                  size="large"
                  placeholder="Employee Handbook"
                  className=""
                />
              </Form.Item>
              <Form.Item label="Description" name="description" className="mb-0">
                <Input
                  size="large"
                  placeholder="What this knowledge base covers"
                  className=""
                />
              </Form.Item>
              <div className="flex items-end">
                <Button
                  htmlType="submit"
                  type="primary"
                  loading={submitting}
                  icon={<PlusOutlined />}
                  className="!h-11"
                >
                  Create
                </Button>
              </div>
            </div>
          </Form>
        </Card>

        <Card className="shadow-admin-card">
          <div className="mb-4 flex items-center justify-between">
            <div>
              <Typography.Title
                level={4}
                className="!mb-0 !mt-0 !text-white"
              >
                Existing knowledge bases
              </Typography.Title>
              <Typography.Text className="!text-white/60">
                Click a name to manage documents and lifecycle details.
              </Typography.Text>
            </div>
            <Space>
              <Tag color="blue">{knowledgeBases.length} total</Tag>
            </Space>
          </div>

          <Table
            rowKey="id"
            loading={loading}
            columns={columns}
            dataSource={knowledgeBases}
            pagination={false}
          />
        </Card>
      </div>
    </div>
  );
}
