import {
  ArrowDownOutlined,
  ArrowUpOutlined,
  DeleteOutlined,
  EditOutlined,
  PlusOutlined,
  ReloadOutlined,
  SendOutlined,
  SwapOutlined,
} from "@ant-design/icons";
import {
  Button,
  Card,
  Empty,
  List,
  Popconfirm,
  Select,
  Space,
  Switch,
  Tag,
  Typography,
  message,
} from "antd";
import { useEffect, useMemo, useState } from "react";
import {
  createIntentNode,
  deleteIntentNode,
  getIntentTree,
  getKnowledgeBases,
  getOptionalTools,
  publishIntentTreeSnapshot,
  setIntentNodeKnowledgeBases,
  switchActiveIntentVersion,
  updateIntentNode,
} from "../../../api/admin.ts";
import type {
  CreateIntentNodeRequest,
  IntentNodeVO,
  IntentVersionVO,
  KnowledgeBaseVO,
  ToolVO,
  UpdateIntentNodeRequest,
} from "../../../types/admin.ts";
import IntentNodeEditDrawer, {
  type IntentNodeEditSubmitValue,
} from "../IntentNodeEditDrawer.tsx";
import IntentTreeViewer from "../IntentTreeViewer.tsx";
import {
  intentKindTone,
  intentLevelTone,
  scopePolicyLabel,
} from "../adminUtils.ts";

type EditorState =
  | {
      open: false;
      mode: "create" | "edit";
      node?: IntentNodeVO | null;
      parentNode?: IntentNodeVO | null;
    }
  | {
      open: true;
      mode: "create" | "edit";
      node?: IntentNodeVO | null;
      parentNode?: IntentNodeVO | null;
    };

function buildNodeMap(nodes: IntentNodeVO[]): Map<string, IntentNodeVO> {
  return new Map(nodes.map((node) => [node.id, node]));
}

function computePathLabel(node: IntentNodeVO | null | undefined, nodes: IntentNodeVO[]): string {
  if (!node) {
    return "";
  }
  const byId = buildNodeMap(nodes);
  const labels: string[] = [];
  let cursor: IntentNodeVO | undefined | null = node;
  while (cursor) {
    labels.unshift(cursor.name);
    cursor = cursor.parentId ? byId.get(cursor.parentId) : null;
  }
  return labels.join(" > ");
}

function sortNodes(nodes: IntentNodeVO[]): IntentNodeVO[] {
  return [...nodes].sort((left, right) => {
    if (left.sortOrder !== right.sortOrder) {
      return left.sortOrder - right.sortOrder;
    }
    return left.name.localeCompare(right.name);
  });
}

export default function IntentTreePage() {
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [publishing, setPublishing] = useState(false);
  const [activatingVersion, setActivatingVersion] = useState<number | null>(null);
  const [nodes, setNodes] = useState<IntentNodeVO[]>([]);
  const [versions, setVersions] = useState<IntentVersionVO[]>([]);
  const [activeVersion, setActiveVersion] = useState<number | null | undefined>(null);
  const [selectedNodeId, setSelectedNodeId] = useState<string>();
  const [knowledgeBases, setKnowledgeBases] = useState<KnowledgeBaseVO[]>([]);
  const [optionalTools, setOptionalTools] = useState<ToolVO[]>([]);
  const [editorState, setEditorState] = useState<EditorState>({
    open: false,
    mode: "create",
  });

  const loadIntentTreeData = async (preferredSelectedNodeId?: string) => {
    setLoading(true);
    try {
      const [intentTreeResponse, knowledgeBaseResponse, toolResponse] =
        await Promise.all([
          getIntentTree(),
          getKnowledgeBases(),
          getOptionalTools(),
        ]);
      const nextNodes = sortNodes(intentTreeResponse.nodes);
      setNodes(nextNodes);
      setKnowledgeBases(knowledgeBaseResponse.knowledgeBases);
      setOptionalTools(toolResponse.tools);
      setVersions(intentTreeResponse.versions);
      setActiveVersion(intentTreeResponse.activeVersion);
      setSelectedNodeId((currentSelectedNodeId) => {
        const nextSelectedNodeId =
          preferredSelectedNodeId ?? currentSelectedNodeId;
        if (
          nextSelectedNodeId &&
          nextNodes.some((node) => node.id === nextSelectedNodeId)
        ) {
          return nextSelectedNodeId;
        }
        return nextNodes[0]?.id;
      });
    } catch (error) {
      console.error("Failed to load intent tree:", error);
      message.error("Unable to load the intent tree.");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void loadIntentTreeData();
  }, []);

  const selectedNode = useMemo(
    () => nodes.find((node) => node.id === selectedNodeId),
    [nodes, selectedNodeId],
  );

  const selectedNodePath = useMemo(
    () => computePathLabel(selectedNode, nodes),
    [nodes, selectedNode],
  );

  const selectedNodeChildren = useMemo(
    () => nodes.filter((node) => node.parentId === selectedNode?.id),
    [nodes, selectedNode?.id],
  );

  const activeKnowledgeBaseMap = useMemo(
    () => new Map(knowledgeBases.map((knowledgeBase) => [knowledgeBase.id, knowledgeBase])),
    [knowledgeBases],
  );

  const siblingNodes = useMemo(() => {
    if (!selectedNode) {
      return [];
    }
    return sortNodes(
      nodes.filter((node) => node.parentId === selectedNode.parentId),
    );
  }, [nodes, selectedNode]);

  const selectedNodeIndex = siblingNodes.findIndex(
    (node) => node.id === selectedNode?.id,
  );

  const handleOpenCreateRoot = () => {
    setEditorState({
      open: true,
      mode: "create",
      node: null,
      parentNode: null,
    });
  };

  const handleOpenCreateChild = () => {
    if (!selectedNode) {
      message.info("Select a node first, or create a root DOMAIN node.");
      return;
    }
    if (selectedNode.nodeLevel === "TOPIC") {
      message.info("TOPIC nodes are leaf nodes and cannot have children.");
      return;
    }
    setEditorState({
      open: true,
      mode: "create",
      node: null,
      parentNode: selectedNode,
    });
  };

  const handleOpenEdit = () => {
    if (!selectedNode) {
      message.info("Select a node to edit.");
      return;
    }
    setEditorState({
      open: true,
      mode: "edit",
      node: selectedNode,
      parentNode: selectedNode.parentId
        ? nodes.find((node) => node.id === selectedNode.parentId) ?? null
        : null,
    });
  };

  const handleSubmitNode = async ({
    payload,
    knowledgeBaseIds,
  }: IntentNodeEditSubmitValue) => {
    setSaving(true);
    try {
      let targetNodeId = editorState.node?.id;
      const finalIntentKind =
        "intentKind" in payload ? payload.intentKind : editorState.node?.intentKind;

      if (editorState.mode === "create") {
        const response = await createIntentNode(payload as CreateIntentNodeRequest);
        targetNodeId = response.nodeId;
      } else if (targetNodeId) {
        await updateIntentNode(targetNodeId, payload as UpdateIntentNodeRequest);
      }

      if (targetNodeId && finalIntentKind === "KB") {
        await setIntentNodeKnowledgeBases(targetNodeId, { knowledgeBaseIds });
      }

      message.success(
        editorState.mode === "create"
          ? "Intent node created."
          : "Intent node updated.",
      );
      setEditorState({ open: false, mode: "create" });
      await loadIntentTreeData(targetNodeId);
    } catch (error) {
      console.error("Failed to save intent node:", error);
      message.error("Unable to save the intent node.");
    } finally {
      setSaving(false);
    }
  };

  const handleDeleteNode = async () => {
    if (!selectedNode) {
      return;
    }
    try {
      await deleteIntentNode(selectedNode.id);
      message.success("Intent node deleted.");
      const fallbackNodeId =
        selectedNode.parentId ?? nodes.find((node) => node.id !== selectedNode.id)?.id;
      await loadIntentTreeData(fallbackNodeId);
    } catch (error) {
      console.error("Failed to delete intent node:", error);
      message.error("Unable to delete the intent node.");
    }
  };

  const handleToggleEnabled = async (checked: boolean) => {
    if (!selectedNode) {
      return;
    }
    try {
      await updateIntentNode(selectedNode.id, { enabled: checked });
      message.success(checked ? "Node enabled." : "Node disabled.");
      await loadIntentTreeData(selectedNode.id);
    } catch (error) {
      console.error("Failed to toggle node enabled state:", error);
      message.error("Unable to update the node state.");
    }
  };

  const moveSelectedNode = async (direction: "up" | "down") => {
    if (!selectedNode || selectedNodeIndex < 0) {
      return;
    }
    const targetIndex =
      direction === "up" ? selectedNodeIndex - 1 : selectedNodeIndex + 1;
    const swapTarget = siblingNodes[targetIndex];
    if (!swapTarget) {
      return;
    }
    try {
      await Promise.all([
        updateIntentNode(selectedNode.id, { sortOrder: swapTarget.sortOrder }),
        updateIntentNode(swapTarget.id, { sortOrder: selectedNode.sortOrder }),
      ]);
      message.success("Node order updated.");
      await loadIntentTreeData(selectedNode.id);
    } catch (error) {
      console.error("Failed to reorder intent nodes:", error);
      message.error("Unable to change node order.");
    }
  };

  const handlePublish = async () => {
    setPublishing(true);
    try {
      const response = await publishIntentTreeSnapshot();
      message.success(`Published snapshot v${response.version}.`);
      await loadIntentTreeData(selectedNode?.id);
    } catch (error) {
      console.error("Failed to publish intent snapshot:", error);
      message.error("Unable to publish the current draft.");
    } finally {
      setPublishing(false);
    }
  };

  const handleActivateVersion = async (version: number) => {
    if (version === activeVersion) {
      return;
    }
    setActivatingVersion(version);
    try {
      await switchActiveIntentVersion(version);
      message.success(`Active intent version switched to v${version}.`);
      await loadIntentTreeData(selectedNode?.id);
    } catch (error) {
      console.error("Failed to switch active intent version:", error);
      message.error("Unable to switch the active version.");
    } finally {
      setActivatingVersion(null);
    }
  };

  return (
    <div className="space-y-5">
      <div className="flex flex-col gap-4 md:flex-row md:items-end md:justify-between">
        <div>
          <div className="text-xs uppercase tracking-[0.28em] text-white/40">
            Admin / Intent Tree
          </div>
          <Typography.Title level={2} className="!mb-0 !mt-2 !text-white">
            Intent routing workspace
          </Typography.Title>
          <Typography.Text className="block !text-white/60">
            Maintain the internal assistant&apos;s draft tree, publish versioned
            snapshots, and switch the active routing version without touching
            chat runtime code.
          </Typography.Text>
        </div>
        <Space wrap>
          <Button
            icon={<ReloadOutlined />}
            className=""
            onClick={() => {
              void loadIntentTreeData(selectedNode?.id);
            }}
          >
            Refresh
          </Button>
          <Button
            icon={<PlusOutlined />}
            className=""
            onClick={handleOpenCreateRoot}
          >
            Add DOMAIN
          </Button>
          <Button
            type="primary"
            icon={<SendOutlined />}
            className=""
            loading={publishing}
            onClick={() => {
              void handlePublish();
            }}
            disabled={nodes.length === 0}
          >
            Publish snapshot
          </Button>
        </Space>
      </div>

      <div className="grid gap-5 xl:grid-cols-[1.15fr_0.85fr]">
        <Card
          loading={loading}
          className="shadow-admin-card"
        >
          <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
            <div>
              <Typography.Title
                level={4}
                className="!mb-0 !mt-0 !text-white"
              >
                Draft intent tree
              </Typography.Title>
              <Typography.Text className="!text-white/60">
                Editing here changes only the draft. Users route against the
                active published snapshot.
              </Typography.Text>
            </div>
            <Space>
              <Tag color="blue">{nodes.length} draft nodes</Tag>
              <Tag color="purple">
                Active v{activeVersion ?? "none"}
              </Tag>
            </Space>
          </div>

          <div className="mb-4 flex flex-wrap gap-3">
            <Button
              icon={<PlusOutlined />}
              className=""
              onClick={handleOpenCreateChild}
              disabled={!selectedNode || selectedNode.nodeLevel === "TOPIC"}
            >
              Add child node
            </Button>
            <Button
              icon={<EditOutlined />}
              className=""
              onClick={handleOpenEdit}
              disabled={!selectedNode}
            >
              Edit selected
            </Button>
            <Popconfirm
              title="Delete intent node?"
              description={
                selectedNodeChildren.length > 0
                  ? "Its draft subtree will be deleted as well."
                  : "This action removes the node from the current draft."
              }
              okText="Delete"
              okButtonProps={{ danger: true }}
              onConfirm={() => {
                void handleDeleteNode();
              }}
              disabled={!selectedNode}
            >
              <Button
                danger
                icon={<DeleteOutlined />}
                className=""
                disabled={!selectedNode}
              >
                Delete
              </Button>
            </Popconfirm>
          </div>

          <IntentTreeViewer
            nodes={nodes}
            selectedNodeId={selectedNodeId}
            onSelect={setSelectedNodeId}
          />
        </Card>

        <div className="flex flex-col gap-5">
          <Card className="shadow-admin-card">
            <div className="mb-4 flex items-start justify-between gap-3">
              <div>
                <Typography.Title
                  level={4}
                  className="!mb-0 !mt-0 !text-white"
                >
                  Selected node
                </Typography.Title>
                <Typography.Text className="!text-white/60">
                  Inspect routing metadata, quick actions, and bindings for the
                  currently selected draft node.
                </Typography.Text>
              </div>
              {selectedNode ? (
                <Tag color={intentLevelTone(selectedNode.nodeLevel)}>
                  {selectedNode.nodeLevel}
                </Tag>
              ) : null}
            </div>

            {!selectedNode ? (
              <Empty description="Select a node from the draft tree." />
            ) : (
              <div className="space-y-5">
                <div>
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="text-lg font-semibold text-white">
                      {selectedNode.name}
                    </span>
                    {selectedNode.intentKind ? (
                      <Tag color={intentKindTone(selectedNode.intentKind)}>
                        {selectedNode.intentKind}
                      </Tag>
                    ) : null}
                    {!selectedNode.enabled ? (
                      <Tag color="default">DISABLED</Tag>
                    ) : null}
                  </div>
                  <Typography.Text className="mt-2 block text-sm leading-6 !text-white/60">
                    {selectedNode.description || "No description set for this node."}
                  </Typography.Text>
                  {selectedNodePath ? (
                    <Typography.Text className="mt-2 block text-xs uppercase tracking-[0.18em] !text-white/40">
                      {selectedNodePath}
                    </Typography.Text>
                  ) : null}
                </div>

                <div className="grid gap-3 sm:grid-cols-2">
                  <div className="rounded-inset bg-white/[0.04] px-4 py-4">
                    <div className="text-xs uppercase tracking-[0.18em] text-white/40">
                      Sort Order
                    </div>
                    <div className="mt-2 text-base font-semibold text-white">
                      {selectedNode.sortOrder}
                    </div>
                  </div>
                  <div className="rounded-inset bg-white/[0.04] px-4 py-4">
                    <div className="text-xs uppercase tracking-[0.18em] text-white/40">
                      Scope
                    </div>
                    <div className="mt-2 text-base font-semibold text-white">
                      {scopePolicyLabel(selectedNode.scopePolicy ?? undefined)}
                    </div>
                  </div>
                </div>

                <div className="flex flex-wrap items-center justify-between gap-3 rounded-section border border-white/[0.06] bg-white/[0.04] px-4 py-4">
                  <div>
                    <div className="text-sm font-semibold text-white">
                      Node enabled
                    </div>
                    <Typography.Text className="block text-sm !text-white/60">
                      Disabled nodes are excluded from the published snapshot at
                      runtime.
                    </Typography.Text>
                  </div>
                  <Switch
                    checked={selectedNode.enabled}
                    onChange={(checked) => {
                      void handleToggleEnabled(checked);
                    }}
                  />
                </div>

                <div className="flex flex-wrap gap-3">
                  <Button
                    icon={<ArrowUpOutlined />}
                    className=""
                    disabled={selectedNodeIndex <= 0}
                    onClick={() => {
                      void moveSelectedNode("up");
                    }}
                  >
                    Move up
                  </Button>
                  <Button
                    icon={<ArrowDownOutlined />}
                    className=""
                    disabled={
                      selectedNodeIndex < 0 ||
                      selectedNodeIndex >= siblingNodes.length - 1
                    }
                    onClick={() => {
                      void moveSelectedNode("down");
                    }}
                  >
                    Move down
                  </Button>
                </div>

                <div>
                  <div className="mb-2 text-sm font-semibold text-white">
                    Examples
                  </div>
                  {selectedNode.examples.length === 0 ? (
                    <Typography.Text className="text-sm !text-white/60">
                      No examples configured.
                    </Typography.Text>
                  ) : (
                    <div className="flex flex-wrap gap-2">
                      {selectedNode.examples.map((example) => (
                        <Tag key={example}>{example}</Tag>
                      ))}
                    </div>
                  )}
                </div>

                {selectedNode.intentKind === "KB" ? (
                  <div>
                    <div className="mb-2 text-sm font-semibold text-white">
                      Bound knowledge bases
                    </div>
                    {selectedNode.knowledgeBaseIds.length === 0 ? (
                      <Typography.Text className="text-sm !text-white/60">
                        No knowledge bases bound yet.
                      </Typography.Text>
                    ) : (
                      <div className="flex flex-wrap gap-2">
                        {selectedNode.knowledgeBaseIds.map((knowledgeBaseId) => (
                          <Tag key={knowledgeBaseId} color="blue">
                            {activeKnowledgeBaseMap.get(knowledgeBaseId)?.name ??
                              knowledgeBaseId}
                          </Tag>
                        ))}
                      </div>
                    )}
                  </div>
                ) : null}

                {selectedNode.intentKind === "TOOL" ? (
                  <div>
                    <div className="mb-2 text-sm font-semibold text-white">
                      Allowed tools
                    </div>
                    {selectedNode.allowedTools.length === 0 ? (
                      <Typography.Text className="text-sm !text-white/60">
                        Inherits the agent's default tool pool (no narrowing).
                      </Typography.Text>
                    ) : (
                      <>
                        <Typography.Text className="mb-2 block text-xs !text-white/50">
                          Narrows the agent default pool to the intersection below.
                        </Typography.Text>
                        <div className="flex flex-wrap gap-2">
                          {selectedNode.allowedTools.map((toolName) => (
                            <Tag key={toolName} color="gold">
                              {toolName}
                            </Tag>
                          ))}
                        </div>
                      </>
                    )}
                  </div>
                ) : null}

                {selectedNode.intentKind === "SYSTEM" ? (
                  <div className="rounded-section border border-white/[0.06] bg-white/[0.04] px-4 py-4">
                    <div className="mb-2 text-sm font-semibold text-white">
                      System prompt override
                    </div>
                    <Typography.Paragraph className="!mb-0 whitespace-pre-wrap !text-sm !text-white/60">
                      {selectedNode.systemPromptOverride ||
                        "No system prompt override set."}
                    </Typography.Paragraph>
                  </div>
                ) : null}
              </div>
            )}
          </Card>

          <Card className="!bg-white/[0.06] !border !border-white/[0.08] shadow-admin-card-dark">
            <div className="mb-4 flex items-center justify-between gap-3">
              <div>
                <Typography.Title level={4} className="!mb-0 !mt-0 !text-white">
                  Published snapshots
                </Typography.Title>
                <Typography.Text className="!text-white/60">
                  Publish the current draft to create a new immutable version,
                  then switch the active runtime version when needed.
                </Typography.Text>
              </div>
              <Tag color="cyan">Active v{activeVersion ?? "none"}</Tag>
            </div>

            <div className="mb-4 rounded-inset bg-white/[0.04] px-4 py-4">
              <div className="text-xs uppercase tracking-[0.18em] text-white/40">
                Quick switch
              </div>
              <div className="mt-3 flex flex-col gap-3 md:flex-row">
                <Select<number>
                  value={activeVersion ?? undefined}
                  placeholder="Select published version"
                  className="w-full"
                  options={versions.map((version) => ({
                    value: version.version,
                    label: `Version ${version.version}${version.active ? " (active)" : ""}`,
                  }))}
                  onChange={(value) => {
                    void handleActivateVersion(value);
                  }}
                />
              </div>
            </div>

            {versions.length === 0 ? (
                <Empty
                  image={Empty.PRESENTED_IMAGE_SIMPLE}
                  description={
                  <span className="text-white/60">
                    No published snapshots yet.
                  </span>
                }
              />
            ) : (
              <List
                dataSource={versions}
                renderItem={(version) => (
                  <List.Item
                    className="!border-white/10"
                    actions={[
                      version.active ? (
                        <Tag color="blue" key="active">
                          Active
                        </Tag>
                      ) : (
                        <Button
                          key="activate"
                          type="link"
                          icon={<SwapOutlined />}
                          loading={activatingVersion === version.version}
                          className="!px-0 !text-white"
                          onClick={() => {
                            void handleActivateVersion(version.version);
                          }}
                        >
                          Activate
                        </Button>
                      ),
                    ]}
                  >
                    <List.Item.Meta
                      title={
                        <span className="text-white">Version {version.version}</span>
                      }
                      description={
                        <span className="text-white/60">
                          {version.active
                            ? "Currently serving live routing traffic."
                            : "Published and ready for rollback or activation."}
                        </span>
                      }
                    />
                  </List.Item>
                )}
              />
            )}
          </Card>
        </div>
      </div>

      <IntentNodeEditDrawer
        open={editorState.open}
        mode={editorState.mode}
        node={editorState.node ?? null}
        parentNode={editorState.parentNode ?? null}
        knowledgeBases={knowledgeBases}
        optionalTools={optionalTools}
        submitting={saving}
        onClose={() => {
          setEditorState({ open: false, mode: "create" });
        }}
        onSubmit={handleSubmitNode}
      />
    </div>
  );
}
