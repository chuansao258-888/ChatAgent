import { jsx as _jsx, jsxs as _jsxs } from "react/jsx-runtime";
import React, { useState, useMemo } from "react";
import { Card, Space, Typography, Select } from "antd";
import { BulbOutlined, MessageOutlined, RobotOutlined, DownOutlined, } from "@ant-design/icons";
import { Sender } from "@ant-design/x";
import { useNavigate } from "react-router-dom";
import { createChatMessage, createChatSession, } from "../../../api/api.ts";
import { getAgentEmoji } from "../../../utils";
import { useChatSessions } from "../../../hooks/useChatSessions.ts";
const { Title, Text } = Typography;
const EmptyAgentChatView = ({ loading, agents, }) => {
    const [message, setMessage] = useState("");
    const [selectedAgentId, setSelectedAgentId] = useState(null);
    const navigate = useNavigate();
    const { refreshChatSessions } = useChatSessions();
    // 为每个 agent 生成 emoji
    const agentsWithEmoji = useMemo(() => {
        return agents.map((agent) => ({
            ...agent,
            emoji: getAgentEmoji(agent.id),
        }));
    }, [agents]);
    // 计算实际选中的 agent ID（如果用户没有选择，则使用默认的第一个）
    const effectiveAgentId = useMemo(() => {
        if (selectedAgentId) {
            return selectedAgentId;
        }
        return agents.length > 0 ? agents[0].id : null;
    }, [selectedAgentId, agents]);
    return (_jsxs("div", { className: "flex flex-col h-full", children: [agents.length > 0 && (_jsx("div", { className: "border-b border-gray-200 bg-white px-4 py-3", children: _jsx("div", { className: "flex items-center justify-start", children: _jsx(Select, { value: effectiveAgentId, onChange: (value) => setSelectedAgentId(value), style: { width: 200 }, className: "agent-selector", suffixIcon: _jsx(DownOutlined, { className: "text-gray-400" }), placeholder: "\u9009\u62E9\u667A\u80FD\u4F53\u52A9\u624B", optionRender: (option) => (_jsxs("div", { className: "flex items-center gap-2", children: [_jsx("span", { className: "text-lg", children: agentsWithEmoji.find((a) => a.id === option.value)?.emoji }), _jsx("span", { className: "text-sm", children: option.label })] })), options: agentsWithEmoji.map((agent) => ({
                            value: agent.id,
                            label: agent.name,
                        })) }) }) })), _jsx("div", { className: "flex-1 flex items-center justify-center p-6", children: _jsxs("div", { className: "max-w-2xl w-full space-y-6", children: [_jsxs("div", { className: "text-center mb-8", children: [_jsx(Title, { level: 2, className: "mb-2", children: "\u5F00\u59CB\u65B0\u7684\u5BF9\u8BDD" }), _jsx(Text, { type: "secondary", className: "text-base", children: "\u9009\u62E9\u4E00\u4E2A\u667A\u80FD\u4F53\u52A9\u624B\u5F00\u59CB\u804A\u5929\uFF0C\u6216\u76F4\u63A5\u53D1\u9001\u6D88\u606F\u521B\u5EFA\u65B0\u4F1A\u8BDD" })] }), _jsxs(Space, { orientation: "vertical", size: "large", className: "w-full", children: [_jsx(Card, { hoverable: true, className: "cursor-pointer transition-all hover:shadow-lg", children: _jsxs(Space, { size: "middle", children: [_jsx("div", { className: "w-12 h-12 rounded-full bg-gradient-to-br from-blue-400 to-purple-400 flex items-center justify-center", children: _jsx(RobotOutlined, { className: "text-white text-xl" }) }), _jsxs("div", { children: [_jsx(Title, { level: 5, className: "mb-1", children: "\u667A\u80FD\u5BF9\u8BDD" }), _jsx(Text, { type: "secondary", children: "\u4E0E AI \u52A9\u624B\u8FDB\u884C\u667A\u80FD\u5BF9\u8BDD\uFF0C\u83B7\u53D6\u5E2E\u52A9\u548C\u5EFA\u8BAE" })] })] }) }), _jsx(Card, { hoverable: true, className: "cursor-pointer transition-all hover:shadow-lg", children: _jsxs(Space, { size: "middle", children: [_jsx("div", { className: "w-12 h-12 rounded-full bg-gradient-to-br from-green-400 to-teal-400 flex items-center justify-center", children: _jsx(BulbOutlined, { className: "text-white text-xl" }) }), _jsxs("div", { children: [_jsx(Title, { level: 5, className: "mb-1", children: "\u77E5\u8BC6\u95EE\u7B54" }), _jsx(Text, { type: "secondary", children: "\u57FA\u4E8E\u77E5\u8BC6\u5E93\u8FDB\u884C\u95EE\u7B54\uFF0C\u83B7\u53D6\u51C6\u786E\u7684\u4FE1\u606F" })] })] }) }), _jsx(Card, { hoverable: true, className: "cursor-pointer transition-all hover:shadow-lg", children: _jsxs(Space, { size: "middle", children: [_jsx("div", { className: "w-12 h-12 rounded-full bg-gradient-to-br from-orange-400 to-red-400 flex items-center justify-center", children: _jsx(MessageOutlined, { className: "text-white text-xl" }) }), _jsxs("div", { children: [_jsx(Title, { level: 5, className: "mb-1", children: "\u5FEB\u901F\u5F00\u59CB" }), _jsx(Text, { type: "secondary", children: "\u5728\u4E0B\u65B9\u8F93\u5165\u6846\u8F93\u5165\u6D88\u606F\uFF0C\u7ACB\u5373\u5F00\u59CB\u5BF9\u8BDD" })] })] }) })] })] }) }), _jsx("div", { className: "border-t border-gray-200 bg-white", children: _jsx("div", { className: "px-4 pb-4 pt-4", children: _jsx(Sender, { onSubmit: async () => {
                            if (!effectiveAgentId)
                                return;
                            console.log("发送消息", message);
                            const response = await createChatSession({
                                agentId: effectiveAgentId,
                                title: message.slice(0, 20),
                            });
                            await createChatMessage({
                                sessionId: response.chatSessionId ?? "",
                                content: message,
                                role: "user",
                                agentId: effectiveAgentId,
                            });
                            // 刷新聊天会话列表
                            await refreshChatSessions();
                            setMessage("");
                            navigate(`/chat/${response.chatSessionId}`);
                        }, value: message, loading: loading, placeholder: "\u8F93\u5165\u6D88\u606F\u5F00\u59CB\u5BF9\u8BDD...", onChange: (value) => {
                            setMessage(value);
                        } }) }) })] }));
};
export default EmptyAgentChatView;
