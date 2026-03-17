import React, { useEffect, useMemo, useRef, useState } from "react";
import { useParams } from "react-router-dom";
import {
  Card,
  Typography,
  Button,
  Upload,
  Table,
  Tag,
  Popconfirm,
  Space,
  message,
  Empty,
} from "antd";
import {
  BookOutlined,
  UploadOutlined,
  DeleteOutlined,
  FileOutlined,
} from "@ant-design/icons";
import type { UploadProps } from "antd";
import { useKnowledgeBases } from "../../hooks/useKnowledgeBases.ts";
import { useDocuments } from "../../hooks/useDocuments.ts";
import { useIngestionTasks } from "../../hooks/useIngestionTasks.ts";
import {
  uploadDocument,
  type DocumentVO,
  type IngestionTaskVO,
} from "../../api/api.ts";
import { BASE_URL } from "../../api/http.ts";

const { Title, Text, Paragraph } = Typography;

type IngestionTaskEvent = {
  taskId: string;
  documentId: string;
  status: IngestionTaskVO["status"];
  chunkCount?: number | null;
  errorMessage?: string | null;
};

const KnowledgeBaseView: React.FC = () => {
  const { knowledgeBaseId } = useParams<{ knowledgeBaseId?: string }>();
  const { knowledgeBases } = useKnowledgeBases();
  const { documents, loading, refreshDocuments, deleteDocument } =
    useDocuments(knowledgeBaseId);
  const {
    tasks,
    loading: tasksLoading,
    hasActiveTasks,
    refreshTasks,
    updateTask,
  } = useIngestionTasks(knowledgeBaseId);

  const [uploadingCount, setUploadingCount] = useState(0);
  const uploading = uploadingCount > 0;
  const taskEventSourcesRef = useRef<Map<string, EventSource>>(new Map());

  const currentKnowledgeBase = useMemo(() => {
    if (!knowledgeBaseId) return null;
    return (
      knowledgeBases.find((kb) => kb.knowledgeBaseId === knowledgeBaseId) ??
      null
    );
  }, [knowledgeBaseId, knowledgeBases]);

  useEffect(() => {
    return () => {
      taskEventSourcesRef.current.forEach((eventSource) => eventSource.close());
      taskEventSourcesRef.current.clear();
    };
  }, []);

  const closeTaskEventSource = (taskId: string) => {
    const eventSource = taskEventSourcesRef.current.get(taskId);
    if (!eventSource) {
      return;
    }
    eventSource.close();
    taskEventSourcesRef.current.delete(taskId);
  };

  const subscribeTaskEvents = (taskId: string, filename: string) => {
    closeTaskEventSource(taskId);

    const eventSource = new EventSource(
      `${BASE_URL}/ingestion-tasks/${taskId}/events`,
    );
    taskEventSourcesRef.current.set(taskId, eventSource);

    eventSource.addEventListener("message", (event) => {
      const taskEvent = JSON.parse(event.data) as IngestionTaskEvent;
      const finishedAt = new Date().toISOString();

      updateTask(taskId, (currentTask) => ({
        ...currentTask,
        status: taskEvent.status,
        chunkCount:
          taskEvent.chunkCount === null || taskEvent.chunkCount === undefined
            ? currentTask.chunkCount
            : taskEvent.chunkCount,
        errorMessage: taskEvent.errorMessage ?? undefined,
        finishedAt,
        updatedAt: finishedAt,
      }));

      if (taskEvent.status === "SUCCESS") {
        message.success(
          `${filename} 入库完成，生成 ${taskEvent.chunkCount ?? 0} 个 chunk`,
        );
      } else if (taskEvent.status === "FAILED") {
        message.error(
          `${filename} 入库失败${taskEvent.errorMessage ? `: ${taskEvent.errorMessage}` : ""}`,
        );
      } else {
        return;
      }

      closeTaskEventSource(taskId);
    });

    eventSource.addEventListener("init", () => {
      void refreshTasks();
    });

    eventSource.onerror = () => {
      closeTaskEventSource(taskId);
    };
  };

  const handleUpload: UploadProps["customRequest"] = async (options) => {
    const { file, onSuccess, onError } = options;

    if (!knowledgeBaseId) {
      message.error("请先选择知识库");
      return;
    }

    const uploadFile = file as File;
    setUploadingCount((count) => count + 1);

    try {
      const response = await uploadDocument(knowledgeBaseId, uploadFile);
      message.success(
        response.taskId
          ? `${uploadFile.name} 上传成功，入库任务已创建`
          : `${uploadFile.name} 上传成功，正在后台处理中`,
      );
      await Promise.all([refreshDocuments(), refreshTasks()]);
      if (response.taskId) {
        subscribeTaskEvents(response.taskId, uploadFile.name);
      }
      onSuccess?.(response);
    } catch (error) {
      message.error(error instanceof Error ? error.message : "上传失败");
      onError?.(error as Error);
    } finally {
      setUploadingCount((count) => Math.max(0, count - 1));
    }
  };

  const handleDeleteDocument = async (documentId: string) => {
    await deleteDocument(documentId);
    await refreshTasks();
  };

  const formatFileSize = (bytes: number): string => {
    if (bytes === 0) return "0 B";
    const k = 1024;
    const sizes = ["B", "KB", "MB", "GB"];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return Math.round((bytes / Math.pow(k, i)) * 100) / 100 + " " + sizes[i];
  };

  const formatDateTime = (value?: string): string => {
    if (!value) return "-";
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) {
      return value;
    }
    return date.toLocaleString("zh-CN", { hour12: false });
  };

  const renderTaskStatus = (status: IngestionTaskVO["status"]) => {
    switch (status) {
      case "PENDING":
        return <Tag color="gold">待处理</Tag>;
      case "RUNNING":
        return <Tag color="processing">处理中</Tag>;
      case "SUCCESS":
        return <Tag color="success">成功</Tag>;
      case "FAILED":
        return <Tag color="error">失败</Tag>;
      default:
        return <Tag>{status}</Tag>;
    }
  };

  const documentColumns = [
    {
      title: "文件名",
      dataIndex: "filename",
      key: "filename",
      render: (text: string) => (
        <Space>
          <FileOutlined />
          <span>{text}</span>
        </Space>
      ),
    },
    {
      title: "文档 ID",
      dataIndex: "id",
      key: "id",
      width: 140,
      render: (id: string) => <Text code>{id.slice(0, 8)}</Text>,
    },
    {
      title: "类型",
      dataIndex: "filetype",
      key: "filetype",
      width: 120,
    },
    {
      title: "大小",
      dataIndex: "size",
      key: "size",
      width: 120,
      render: (size: number) => formatFileSize(size),
    },
    {
      title: "操作",
      key: "action",
      width: 100,
      render: (_: unknown, record: DocumentVO) => (
        <Popconfirm
          title="确定要删除这个文档吗？"
          description="删除后将无法恢复"
          onConfirm={() => handleDeleteDocument(record.id)}
          okText="确定"
          cancelText="取消"
        >
          <Button type="text" danger icon={<DeleteOutlined />} size="small">
            删除
          </Button>
        </Popconfirm>
      ),
    },
  ];

  const taskColumns = [
    {
      title: "任务 ID",
      dataIndex: "id",
      key: "id",
      width: 120,
      render: (id: string) => <Text code>{id.slice(0, 8)}</Text>,
    },
    {
      title: "文档 ID",
      dataIndex: "documentId",
      key: "documentId",
      width: 120,
      render: (documentId: string) => <Text code>{documentId.slice(0, 8)}</Text>,
    },
    {
      title: "类型",
      dataIndex: "fileType",
      key: "fileType",
      width: 100,
    },
    {
      title: "状态",
      dataIndex: "status",
      key: "status",
      width: 120,
      render: (status: IngestionTaskVO["status"]) => renderTaskStatus(status),
    },
    {
      title: "Chunk 数",
      dataIndex: "chunkCount",
      key: "chunkCount",
      width: 100,
      render: (chunkCount?: number) => chunkCount ?? "-",
    },
    {
      title: "错误信息",
      dataIndex: "errorMessage",
      key: "errorMessage",
      render: (errorMessage?: string) =>
        errorMessage ? <Text type="danger">{errorMessage}</Text> : "-",
    },
    {
      title: "开始时间",
      dataIndex: "startedAt",
      key: "startedAt",
      width: 180,
      render: (startedAt?: string) => formatDateTime(startedAt),
    },
    {
      title: "结束时间",
      dataIndex: "finishedAt",
      key: "finishedAt",
      width: 180,
      render: (finishedAt?: string) => formatDateTime(finishedAt),
    },
  ];

  if (!knowledgeBaseId) {
    return (
      <div className="flex flex-col h-full items-center justify-center p-6">
        <Empty
          image={<BookOutlined className="text-6xl text-gray-300" />}
          description={
            <div className="mt-4">
              <Title level={4} type="secondary">
                未选择知识库
              </Title>
              <Text type="secondary" className="text-sm">
                请从左侧知识库列表中选择一个知识库查看详情
              </Text>
            </div>
          }
        />
      </div>
    );
  }

  if (!currentKnowledgeBase) {
    return (
      <div className="flex flex-col h-full items-center justify-center p-6">
        <Empty
          description={
            <div className="mt-4">
              <Title level={4} type="secondary">
                知识库不存在
              </Title>
              <Text type="secondary" className="text-sm">
                请检查知识库 ID 是否正确
              </Text>
            </div>
          }
        />
      </div>
    );
  }

  return (
    <div className="flex flex-col h-full p-6 overflow-y-auto">
      <div className="max-w-6xl w-full mx-auto">
        <div className="mb-3">
          <Card>
            <div className="flex items-start gap-4">
              <div className="w-16 h-16 rounded-lg bg-gradient-to-br from-blue-200 to-purple-200 flex items-center justify-center text-3xl shrink-0">
                <BookOutlined />
              </div>
              <div className="flex-1">
                <Title level={3} className="mb-2">
                  {currentKnowledgeBase.name}
                </Title>
                {currentKnowledgeBase.description && (
                  <Paragraph className="text-gray-600 mb-0">
                    {currentKnowledgeBase.description}
                  </Paragraph>
                )}
                <Text type="secondary" className="text-sm">
                  知识库 ID: {currentKnowledgeBase.knowledgeBaseId}
                </Text>
              </div>
            </div>
          </Card>
        </div>

        <div className="mb-3">
          <Card title="上传文档">
            <Upload
              customRequest={handleUpload}
              showUploadList={false}
              accept=".md"
              multiple
              disabled={uploading}
            >
              <Button
                type="primary"
                icon={<UploadOutlined />}
                loading={uploading}
                size="large"
              >
                选择文件批量上传
              </Button>
            </Upload>
            <Text type="secondary" className="block mt-2 text-xs">
              支持格式: Markdown，可一次选择多个文件
            </Text>
          </Card>
        </div>

        <div className="mb-3">
          <Card title={`文档列表 (${documents.length})`}>
            {loading ? (
              <div className="text-center py-8">
                <Text type="secondary">加载中...</Text>
              </div>
            ) : documents.length === 0 ? (
              <Empty
                description={<Text type="secondary">暂无文档，请先上传文档。</Text>}
              />
            ) : (
              <Table
                columns={documentColumns}
                dataSource={documents}
                rowKey="id"
                pagination={{
                  pageSize: 10,
                  showTotal: (total) => `共 ${total} 条`,
                }}
              />
            )}
          </Card>
        </div>

        <div className="mb-3">
          <Card
            title={`入库任务 (${tasks.length})`}
            extra={
              hasActiveTasks ? (
                <Text type="secondary" className="text-xs">
                  当前存在处理中任务
                </Text>
              ) : null
            }
          >
            {tasksLoading ? (
              <div className="text-center py-8">
                <Text type="secondary">加载中...</Text>
              </div>
            ) : tasks.length === 0 ? (
              <Empty
                description={
                  <Text type="secondary">
                    暂无入库任务，上传 Markdown 后会出现在这里。
                  </Text>
                }
              />
            ) : (
              <Table
                columns={taskColumns}
                dataSource={tasks}
                rowKey="id"
                pagination={{
                  pageSize: 10,
                  showTotal: (total) => `共 ${total} 条`,
                }}
                scroll={{ x: 1100 }}
              />
            )}
          </Card>
        </div>
      </div>
    </div>
  );
};

export default KnowledgeBaseView;
