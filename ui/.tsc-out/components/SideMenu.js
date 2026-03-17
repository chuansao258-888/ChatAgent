import { jsx as _jsx, jsxs as _jsxs } from "react/jsx-runtime";
import React, { useState } from "react";
import { RobotOutlined } from "@ant-design/icons";
import { Tabs } from "antd";
import { useNavigate } from "react-router-dom";
import AgentTabContent from "./tabs/AgentTabContent.tsx";
import AddAgentModal from "./modals/AddAgentModal.tsx";
import ChatTabContent from "./tabs/ChatTabContent.tsx";
import KnowledgeBaseTabContent from "./tabs/KnowledgeBaseTabContent.tsx";
import AddKnowledgeBaseModal from "./modals/AddKnowledgeBaseModal.tsx";
import { useAgents } from "../hooks/useAgents.ts";
import { useKnowledgeBases } from "../hooks/useKnowledgeBases.ts";
const SideMenu = () => {
    const navigate = useNavigate();
    const [isAddAgentModalOpen, setIsAddAgentModalOpen] = useState(false);
    const toggleAddAgentModal = () => {
        setIsAddAgentModalOpen(!isAddAgentModalOpen);
        setEditingAgent(null);
    };
    const [editingAgent, setEditingAgent] = useState(null);
    /**
     * 添加知识库模态框状态
     */
    const [isAddKnowledgeBaseModalOpen, setIsAddKnowledgeBaseModalOpen] = useState(false);
    const toggleAddKnowledgeBaseModal = () => {
        setIsAddKnowledgeBaseModalOpen(!isAddKnowledgeBaseModalOpen);
    };
    const { agents, createAgentHandle, deleteAgentHandle, updateAgentHandle } = useAgents();
    const [activeKey, setActiveKey] = useState(() => {
        if (location.pathname.startsWith("/agent"))
            return "agent";
        if (location.pathname.startsWith("/knowledge-base"))
            return "knowledgeBase";
        if (location.pathname.startsWith("/chat"))
            return "chat";
        return "agent";
    });
    const { knowledgeBases, createKnowledgeBaseHandle } = useKnowledgeBases();
    // 处理标签页切换
    const handleTabChange = (key) => {
        setActiveKey(key);
    };
    const items = [
        {
            key: "agent",
            label: _jsx("span", { className: "select-none", children: "\u667A\u80FD\u4F53\u52A9\u624B" }),
            children: (_jsx(AgentTabContent, { agents: agents, onSelectAgent: () => { }, onCreateAgentClick: toggleAddAgentModal, onEditAgent: (agent) => {
                    setEditingAgent(agent);
                    setIsAddAgentModalOpen(true);
                }, onDeleteAgent: deleteAgentHandle })),
        },
        {
            key: "chat",
            label: _jsx("span", { className: "select-none", children: "\u804A\u5929\u8BB0\u5F55" }),
            children: _jsx(ChatTabContent, {}),
        },
        {
            key: "knowledgeBase",
            label: _jsx("span", { className: "select-none", children: "\u77E5\u8BC6\u5E93" }),
            children: (_jsx(KnowledgeBaseTabContent, { knowledgeBases: knowledgeBases, onCreateKnowledgeBaseClick: toggleAddKnowledgeBaseModal, onSelectKnowledgeBase: (knowledgeBaseId) => {
                    navigate(`/knowledge-base/${knowledgeBaseId}`);
                } })),
        },
    ];
    return (_jsxs("div", { className: "px-4 flex flex-col h-full", children: [_jsx("div", { className: "h-14 w-full flex items-center border-b border-gray-200", children: _jsxs("div", { className: "flex items-center gap-2.5 mx-4", children: [_jsx(RobotOutlined, { className: "text-xl text-indigo-600" }), _jsx("div", { className: "text-lg font-semibold select-none text-gray-900", children: "JChatMind" })] }) }), _jsx("div", { className: "flex-1 min-h-0 flex flex-col", children: _jsx(Tabs, { activeKey: activeKey, onChange: handleTabChange, items: items }) }), _jsx(AddAgentModal, { open: isAddAgentModalOpen, onClose: toggleAddAgentModal, createAgentHandle: createAgentHandle, updateAgentHandle: updateAgentHandle, editingAgent: editingAgent }), _jsx(AddKnowledgeBaseModal, { open: isAddKnowledgeBaseModalOpen, onClose: toggleAddKnowledgeBaseModal, createKnowledgeBaseHandle: createKnowledgeBaseHandle })] }));
};
export default SideMenu;
