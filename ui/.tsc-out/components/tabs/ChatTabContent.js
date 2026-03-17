import { jsx as _jsx, jsxs as _jsxs } from "react/jsx-runtime";
import React, { useMemo } from "react";
import { useNavigate } from "react-router-dom";
import { Button, Divider, Popconfirm } from "antd";
import { PlusOutlined, MessageOutlined, DeleteOutlined, } from "@ant-design/icons";
import { useChatSessions } from "../../hooks/useChatSessions.ts";
import { useAgents } from "../../hooks/useAgents.ts";
const ChatTabContent = () => {
    const navigate = useNavigate();
    const { chatSessions, loading, deleteChatSession } = useChatSessions();
    const { agents } = useAgents();
    // 创建 agentId 到 agent 的映射
    const agentMap = useMemo(() => {
        const map = new Map();
        agents.forEach((agent) => {
            map.set(agent.id, agent.name);
        });
        return map;
    }, [agents]);
    const handleCreateNewChat = () => {
        navigate("/chat");
    };
    const handleSelectChatSession = (chatSessionId) => {
        navigate(`/chat/${chatSessionId}`);
    };
    const handleDeleteChatSession = async (chatSessionId) => {
        await deleteChatSession(chatSessionId);
    };
    // 格式化标题显示
    const getDisplayTitle = (session) => {
        if (session.title) {
            return session.title;
        }
        const agentName = agentMap.get(session.agentId);
        return agentName ? `与 ${agentName} 的对话` : "新对话";
    };
    return (_jsxs("div", { className: "flex flex-col h-full", children: [_jsx(Button, { color: "geekblue", variant: "filled", icon: _jsx(PlusOutlined, {}), onClick: handleCreateNewChat, className: "w-full", children: "\u65B0\u804A\u5929" }), _jsx(Divider, {}), _jsx("div", { className: "flex-1 min-h-0 overflow-y-auto bg-gray-50 rounded-lg", children: loading ? (_jsx("div", { className: "flex flex-col items-center justify-center h-full text-gray-400", children: _jsx("p", { className: "text-sm", children: "\u52A0\u8F7D\u4E2D..." }) })) : chatSessions.length === 0 ? (_jsxs("div", { className: "flex flex-col items-center justify-center h-full text-gray-400", children: [_jsx(MessageOutlined, { className: "text-4xl mb-2" }), _jsx("p", { className: "text-sm", children: "\u6682\u65E0\u804A\u5929\u8BB0\u5F55" }), _jsx("p", { className: "text-xs mt-1", children: "\u70B9\u51FB\u4E0A\u65B9\u6309\u94AE\u521B\u5EFA\u65B0\u804A\u5929" })] })) : (_jsx("div", { className: "space-y-1.5 p-1.5", children: chatSessions.map((session) => (_jsx("div", { onClick: () => handleSelectChatSession(session.id), className: "w-full px-3 py-2.5 rounded-lg bg-white cursor-pointer transition-all hover:bg-gray-100 hover:shadow-sm group relative", children: _jsxs("div", { className: "flex items-start gap-3", children: [_jsx("div", { className: "w-8 h-8 rounded-lg bg-gradient-to-br from-blue-200 to-purple-200 flex items-center justify-center shrink-0 text-lg mt-0.5", children: _jsx(MessageOutlined, {}) }), _jsx("div", { className: "flex-1 min-w-0", children: _jsx("div", { className: "font-medium text-gray-900 truncate", children: getDisplayTitle(session) }) }), _jsx("div", { onClick: (e) => e.stopPropagation(), children: _jsx(Popconfirm, { title: "\u786E\u5B9A\u8981\u5220\u9664\u8FD9\u6761\u804A\u5929\u8BB0\u5F55\u5417\uFF1F", description: "\u5220\u9664\u540E\u5C06\u65E0\u6CD5\u6062\u590D", onConfirm: () => handleDeleteChatSession(session.id), okText: "\u786E\u5B9A", cancelText: "\u53D6\u6D88", children: _jsx(Button, { type: "text", size: "small", icon: _jsx(DeleteOutlined, {}), className: "opacity-0 group-hover:opacity-100 transition-opacity shrink-0", onClick: (e) => e.stopPropagation(), danger: true }) }) })] }) }, session.id))) })) })] }));
};
export default ChatTabContent;
