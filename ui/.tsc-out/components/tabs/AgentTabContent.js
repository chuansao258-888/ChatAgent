import { jsx as _jsx, jsxs as _jsxs } from "react/jsx-runtime";
import React, { useMemo } from "react";
import { Button, Divider, Dropdown, Modal } from "antd";
import { PlusOutlined, EditOutlined, DeleteOutlined, MoreOutlined, } from "@ant-design/icons";
import { formatDateTime, getAgentEmoji } from "../../utils";
const AgentTabContent = ({ agents, onCreateAgentClick, onSelectAgent, onEditAgent, onDeleteAgent, }) => {
    // 为每个 agent 生成 emoji
    const agentsWithEmoji = useMemo(() => {
        return agents.map((agent) => ({
            ...agent,
            emoji: getAgentEmoji(agent.id),
        }));
    }, [agents]);
    // 创建右键菜单
    const getContextMenuItems = (agent) => {
        const items = [];
        if (onEditAgent) {
            items.push({
                key: "edit",
                label: "编辑",
                icon: _jsx(EditOutlined, {}),
                onClick: (e) => {
                    e.domEvent.stopPropagation();
                    onEditAgent(agent);
                },
            });
        }
        if (onDeleteAgent) {
            items.push({
                key: "delete",
                label: "删除",
                icon: _jsx(DeleteOutlined, {}),
                danger: true,
                onClick: (e) => {
                    e.domEvent.stopPropagation();
                    Modal.confirm({
                        title: "确定要删除这个智能体吗？",
                        content: "删除后将无法恢复",
                        okText: "确定",
                        cancelText: "取消",
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
    return (_jsxs("div", { className: "flex flex-col h-full", children: [_jsx(Button, { color: "geekblue", variant: "filled", icon: _jsx(PlusOutlined, {}), onClick: onCreateAgentClick, className: "w-full", children: "\u667A\u80FD\u4F53\u52A9\u624B" }), _jsx(Divider, {}), _jsx("div", { className: "flex-1 overflow-y-auto bg-gray-50 rounded-lg p-1.5", children: agents.length === 0 ? (_jsxs("div", { className: "flex flex-col items-center justify-center h-full text-gray-400", children: [_jsx("p", { className: "text-sm", children: "\u6682\u65E0\u667A\u80FD\u4F53" }), _jsx("p", { className: "text-xs mt-1", children: "\u70B9\u51FB\u4E0A\u65B9\u6309\u94AE\u6DFB\u52A0" })] })) : (_jsx("div", { className: "space-y-1.5", children: agentsWithEmoji.map((agent) => {
                        const menuItems = getContextMenuItems(agent);
                        const hasMenu = menuItems && menuItems.length > 0;
                        return (_jsx("div", { onClick: () => onSelectAgent(agent.id), className: "w-full px-3 py-3 rounded-lg bg-white cursor-pointer transition-all hover:bg-gray-100 hover:shadow-sm group relative", children: _jsxs("div", { className: "flex items-start gap-3", children: [_jsx("div", { className: "w-8 h-8 rounded-lg bg-gradient-to-br from-yellow-200 to-orange-200 flex items-center justify-center shrink-0 text-lg mt-0.5", children: agent.emoji }), _jsxs("div", { className: "flex-1 min-w-0", children: [_jsx("div", { className: "font-medium text-gray-900 truncate", children: agent.name }), agent.description && (_jsx("div", { className: "text-xs text-gray-500 mt-1 line-clamp-1", children: agent.description })), agent.updatedAt && (_jsx("div", { className: "text-xs text-gray-400 mt-1", children: formatDateTime(agent.updatedAt) }))] }), hasMenu && (_jsx("div", { onClick: (e) => e.stopPropagation(), onContextMenu: (e) => e.stopPropagation(), className: "opacity-0 group-hover:opacity-100 transition-opacity shrink-0", children: _jsx(Dropdown, { menu: { items: menuItems }, trigger: ["contextMenu", "click"], placement: "bottomRight", children: _jsx(Button, { type: "text", size: "small", icon: _jsx(MoreOutlined, {}), onClick: (e) => e.stopPropagation(), className: "text-gray-400 hover:text-gray-600" }) }) }))] }) }, agent.id));
                    }) })) })] }));
};
export default AgentTabContent;
