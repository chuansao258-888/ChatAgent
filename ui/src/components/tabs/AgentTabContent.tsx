import React, { useMemo } from "react";
import {
  DeleteOutlined,
  EditOutlined,
  MoreOutlined,
  PlusOutlined,
} from "@ant-design/icons";
import { Button, Dropdown, Modal } from "antd";
import type { MenuProps } from "antd";
import type { AgentVO } from "../../api/api.ts";
import { formatDateTime, getAgentEmoji } from "../../utils";

interface AgentTabContentProps {
  agents: AgentVO[];
  onCreateAgentClick: () => void;
  onSelectAgent: (agentId: string) => void;
  onEditAgent?: (agent: AgentVO) => void;
  onDeleteAgent?: (agentId: string) => void;
}

const AgentTabContent: React.FC<AgentTabContentProps> = ({
  agents,
  onCreateAgentClick,
  onSelectAgent,
  onEditAgent,
  onDeleteAgent,
}) => {
  const agentsWithEmoji = useMemo(() => {
    return agents.map((agent) => ({
      ...agent,
      emoji: getAgentEmoji(agent.id),
    }));
  }, [agents]);

  const getContextMenuItems = (agent: AgentVO): MenuProps["items"] => {
    const items: MenuProps["items"] = [];

    if (onEditAgent) {
      items.push({
        key: "edit",
        label: "Edit",
        icon: <EditOutlined />,
        onClick: (event) => {
          event.domEvent.stopPropagation();
          onEditAgent(agent);
        },
      });
    }

    if (onDeleteAgent) {
      items.push({
        key: "delete",
        label: "Delete",
        icon: <DeleteOutlined />,
        danger: true,
        onClick: (event) => {
          event.domEvent.stopPropagation();
          Modal.confirm({
            title: "Delete this assistant?",
            content: "This cannot be undone.",
            okText: "Delete",
            cancelText: "Cancel",
            okType: "danger",
            onOk: () => {
              onDeleteAgent(agent.id);
            },
          });
        },
      });
    }

    return items;
  };

  return (
    <div className="flex h-full flex-col">
      <Button
        icon={<PlusOutlined />}
        onClick={onCreateAgentClick}
        className="!mb-4 !h-12 !w-full !rounded-2xl !border !border-white/6 !bg-white/[0.03] !text-white hover:!border-white/10 hover:!bg-white/[0.06]"
      >
        New assistant
      </Button>

      <div className="scrollbar-hide flex-1 overflow-y-auto rounded-[28px] border border-white/6 bg-white/[0.025] p-3">
        {agents.length === 0 ? (
          <div className="flex h-full min-h-[280px] flex-col items-center justify-center rounded-[24px] border border-dashed border-white/8 bg-black/10 px-6 text-center text-white/40">
            <p className="text-sm font-medium text-white/68">No assistants yet</p>
            <p className="mt-2 text-xs leading-6 text-white/36">
              Create an assistant when you want a custom configuration.
            </p>
          </div>
        ) : (
          <div className="space-y-2">
            {agentsWithEmoji.map((agent) => {
              const menuItems = getContextMenuItems(agent);
              const hasMenu = Boolean(menuItems && menuItems.length > 0);

              return (
                <div
                  key={agent.id}
                  onClick={() => onSelectAgent(agent.id)}
                  className="group relative w-full cursor-pointer rounded-2xl border border-transparent bg-white/[0.035] px-4 py-3 transition hover:border-white/8 hover:bg-white/[0.06]"
                >
                  <div className="flex items-start gap-3">
                    <div className="mt-0.5 flex h-9 w-9 shrink-0 items-center justify-center rounded-2xl bg-white/[0.06] text-lg">
                      {agent.emoji}
                    </div>

                    <div className="min-w-0 flex-1">
                      <div className="truncate text-sm font-medium text-white">
                        {agent.name}
                      </div>
                      {agent.description ? (
                        <div className="mt-1 line-clamp-1 text-xs text-white/42">
                          {agent.description}
                        </div>
                      ) : null}
                      {agent.updatedAt ? (
                        <div className="mt-1 text-xs text-white/28">
                          {formatDateTime(agent.updatedAt)}
                        </div>
                      ) : null}
                    </div>

                    {hasMenu ? (
                      <div
                        onClick={(event) => {
                          event.stopPropagation();
                        }}
                        onContextMenu={(event) => {
                          event.stopPropagation();
                        }}
                        className="opacity-0 transition-opacity group-hover:opacity-100"
                      >
                        <Dropdown
                          menu={{ items: menuItems }}
                          trigger={["contextMenu", "click"]}
                          placement="bottomRight"
                        >
                          <Button
                            type="text"
                            size="small"
                            icon={<MoreOutlined />}
                            onClick={(event) => {
                              event.stopPropagation();
                            }}
                            className="!flex !h-8 !w-8 !items-center !justify-center !rounded-full !text-white/38 hover:!bg-white/[0.06] hover:!text-white"
                          />
                        </Dropdown>
                      </div>
                    ) : null}
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </div>
    </div>
  );
};

export default AgentTabContent;
