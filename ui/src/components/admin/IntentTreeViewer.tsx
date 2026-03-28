import { Empty, Tag, Tree, Typography } from "antd";
import type { DataNode } from "antd/es/tree";
import { useMemo } from "react";
import type { IntentNodeVO } from "../../types/admin.ts";
import { intentKindTone, intentLevelTone } from "./adminUtils.ts";

interface IntentTreeViewerProps {
  nodes: IntentNodeVO[];
  selectedNodeId?: string;
  onSelect: (nodeId: string) => void;
}

interface IntentTreeDataNode extends DataNode {
  meta: IntentNodeVO;
}

function sortNodes(nodes: IntentNodeVO[]): IntentNodeVO[] {
  return [...nodes].sort((left, right) => {
    if (left.sortOrder !== right.sortOrder) {
      return left.sortOrder - right.sortOrder;
    }
    return left.name.localeCompare(right.name);
  });
}

function buildTreeData(nodes: IntentNodeVO[]): IntentTreeDataNode[] {
  const byParent = new Map<string, IntentNodeVO[]>();
  sortNodes(nodes).forEach((node) => {
    const parentId = node.parentId ?? "__root__";
    const siblings = byParent.get(parentId) ?? [];
    siblings.push(node);
    byParent.set(parentId, siblings);
  });

  const build = (parentId?: string | null): IntentTreeDataNode[] => {
    const children = byParent.get(parentId ?? "__root__") ?? [];
    return children.map((node) => ({
      key: node.id,
      meta: node,
      title: (
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-2">
            <span className="truncate text-sm font-semibold text-white">
              {node.name}
            </span>
            <Tag color={intentLevelTone(node.nodeLevel)}>{node.nodeLevel}</Tag>
            {node.intentKind ? (
              <Tag color={intentKindTone(node.intentKind)}>{node.intentKind}</Tag>
            ) : null}
            {!node.enabled ? <Tag color="default">DISABLED</Tag> : null}
          </div>
          {node.description ? (
            <Typography.Text className="block text-xs leading-5 !text-white/40">
              {node.description}
            </Typography.Text>
          ) : null}
        </div>
      ),
      children: build(node.id),
    }));
  };

  return build(null);
}

export default function IntentTreeViewer({
  nodes,
  selectedNodeId,
  onSelect,
}: IntentTreeViewerProps) {
  const treeData = useMemo(() => buildTreeData(nodes), [nodes]);

  if (nodes.length === 0) {
    return (
      <div className="rounded-section border border-dashed border-white/10 bg-white/[0.04] py-10">
        <Empty description="Create the first DOMAIN node to start the draft tree." />
      </div>
    );
  }

  return (
    <Tree
      blockNode
      showLine={{ showLeafIcon: false }}
      defaultExpandAll
      treeData={treeData}
      selectedKeys={selectedNodeId ? [selectedNodeId] : []}
      onSelect={(selectedKeys) => {
        const nextSelected = selectedKeys[0];
        if (typeof nextSelected === "string") {
          onSelect(nextSelected);
        }
      }}
      className="rounded-section bg-white/[0.04] px-3 py-3"
    />
  );
}
