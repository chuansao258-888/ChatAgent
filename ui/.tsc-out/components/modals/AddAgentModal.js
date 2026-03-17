import { jsx as _jsx, jsxs as _jsxs } from "react/jsx-runtime";
import React, { useEffect, useState } from "react";
import { Button, Checkbox, Input, Modal, Select, Slider } from "antd";
import TextArea from "antd/es/input/TextArea";
import { SaveOutlined } from "@ant-design/icons";
import { getOptionalTools, } from "../../api/api.ts";
import { useKnowledgeBases } from "../../hooks/useKnowledgeBases.ts";
const menuItems = [
    { key: "base", label: "基础设置" },
    { key: "model", label: "模型设置" },
    { key: "knowledge", label: "知识库设置" },
    // { key: "mcp", label: "MCP 服务器" },
    { key: "tools", label: "工具调用" },
    // { key: "memory", label: "全局记忆" },
];
const AddAgentModal = ({ open, onClose, createAgentHandle, updateAgentHandle, editingAgent, }) => {
    // 菜单项
    const [selectedKey, setSelectedKey] = useState("base");
    // 获取知识库列表
    const { knowledgeBases } = useKnowledgeBases();
    // 工具列表
    const [tools, setTools] = useState([]);
    // 表单数据
    const [formData, setFormData] = useState({
        name: "智能体助手",
        description: "",
        systemPrompt: "你是一个很有用的智能体助手",
        model: "deepseek-chat",
        allowedTools: [],
        allowedKbs: [],
        chatOptions: {
            temperature: 0.7,
            topP: 1.0,
            messageLength: 20,
        },
    });
    const [createAgentLoading, setCreateAgentLoading] = useState(false);
    // 当编辑的 agent 变化时，更新表单数据
    useEffect(() => {
        if (editingAgent) {
            setFormData({
                name: editingAgent.name,
                description: editingAgent.description || "",
                systemPrompt: editingAgent.systemPrompt || "",
                model: editingAgent.model,
                allowedTools: editingAgent.allowedTools || [],
                allowedKbs: editingAgent.allowedKbs || [],
                chatOptions: editingAgent.chatOptions || {
                    temperature: 0.7,
                    topP: 1.0,
                    messageLength: 10,
                },
            });
        }
        else {
            // 重置表单
            setFormData({
                name: "agent",
                description: "",
                systemPrompt: "",
                model: "deepseek-chat",
                allowedTools: [],
                allowedKbs: [],
                chatOptions: {
                    temperature: 0.7,
                    topP: 1.0,
                    messageLength: 10,
                },
            });
        }
    }, [editingAgent, open]);
    // 获取工具列表
    useEffect(() => {
        async function fetchTools() {
            try {
                const resp = await getOptionalTools();
                setTools(resp.tools);
            }
            catch (error) {
                console.error("获取工具列表失败:", error);
            }
        }
        fetchTools().then();
    }, []);
    const isEditMode = !!editingAgent;
    return (_jsx(Modal, { open: open, onCancel: onClose, title: isEditMode ? "编辑智能体" : "智能体助手", footer: null, width: 800, centered: true, children: _jsxs("div", { className: "flex h-[500px]", children: [_jsx("div", { className: "w-[150px] h-full border-r border-gray-200 pr-2", children: _jsx("div", { className: "flex flex-col gap-0.5 select-none cursor-pointer", children: menuItems.map((item) => {
                            const isSelected = selectedKey === item.key;
                            return (_jsx(React.Fragment, { children: _jsx("div", { onClick: () => setSelectedKey(item.key), className: `px-3 py-2 rounded-lg hover:bg-gray-100 ${isSelected ? "bg-gray-100 text-gray-900 font-medium" : "text-gray-600"}`, children: item.label }) }, item.key));
                        }) }) }), _jsxs("div", { className: "flex-1 h-full relative", children: [_jsxs("div", { className: "px-4 pb-4 overflow-y-scroll", children: [selectedKey === "base" && (_jsxs("div", { children: [_jsxs("div", { className: "mb-3", children: [_jsx("label", { className: "block text-gray-700 font-medium mb-1", children: "\u540D\u79F0" }), _jsx("div", { className: "flex items-center", children: _jsx(Input, { placeholder: "\u8BF7\u8F93\u5165\u667A\u80FD\u4F53\u540D\u79F0", value: formData.name, onChange: (e) => setFormData({ ...formData, name: e.target.value }) }) })] }), _jsxs("div", { className: "mb-3", children: [_jsx("label", { className: "block text-gray-700 font-medium mb-1", children: "\u63CF\u8FF0" }), _jsx(TextArea, { placeholder: "\u8BF7\u8F93\u5165\u667A\u80FD\u4F53\u63CF\u8FF0", rows: 2, value: formData.description, onChange: (e) => setFormData({ ...formData, description: e.target.value }) })] }), _jsxs("div", { className: "mb-3", children: [_jsx("label", { className: "block text-gray-700 font-medium mb-1", children: "\u63D0\u793A\u8BCD" }), _jsx(TextArea, { placeholder: "\u9ED8\u8BA4\u63D0\u793A\u8BCD", rows: 11, value: formData.systemPrompt, onChange: (e) => setFormData({ ...formData, systemPrompt: e.target.value }) })] })] })), selectedKey === "model" && (_jsxs("div", { children: [_jsxs("div", { className: "mb-4", children: [_jsx("label", { className: "block text-gray-700 font-medium mb-1", children: "\u9009\u62E9\u6A21\u578B" }), _jsx(Select, { options: [
                                                        {
                                                            value: "deepseek-chat",
                                                            label: "deepseek-chat",
                                                        },
                                                        {
                                                            value: "glm-4.6",
                                                            label: "glm-4.6",
                                                        },
                                                    ], placeholder: "\u8BF7\u9009\u62E9\u6A21\u578B", style: { width: "300px" }, value: formData.model, onChange: (value) => setFormData({ ...formData, model: value }) })] }), _jsxs("div", { className: "mb-4", children: [_jsx("label", { className: "block text-gray-700 font-medium mb-2", children: "\u6A21\u578B\u53C2\u6570" }), _jsxs("div", { className: "space-y-4", children: [_jsxs("div", { children: [_jsxs("div", { className: "flex items-center justify-between mb-2", children: [_jsxs("label", { className: "block text-sm text-gray-600", children: ["Temperature\uFF08\u6E29\u5EA6\uFF09", _jsx("span", { className: "text-gray-400 ml-1 text-xs", children: "(0.0 - 2.0)" })] }), _jsx("span", { className: "text-sm font-medium text-gray-700 min-w-[40px] text-right", children: formData?.chatOptions?.temperature?.toFixed(1) })] }), _jsx(Slider, { min: 0, max: 2, step: 0.1, value: formData?.chatOptions?.temperature, onChange: (value) => setFormData({
                                                                        ...formData,
                                                                        chatOptions: {
                                                                            ...formData.chatOptions,
                                                                            temperature: value,
                                                                        },
                                                                    }) })] }), _jsxs("div", { children: [_jsxs("div", { className: "flex items-center justify-between mb-2", children: [_jsxs("label", { className: "block text-sm text-gray-600", children: ["Top P\uFF08\u6838\u91C7\u6837\uFF09", _jsx("span", { className: "text-gray-400 ml-1 text-xs", children: "(0.0 - 1.0)" })] }), _jsx("span", { className: "text-sm font-medium text-gray-700 min-w-[40px] text-right", children: formData?.chatOptions?.topP?.toFixed(1) })] }), _jsx(Slider, { min: 0, max: 1, step: 0.1, value: formData?.chatOptions?.topP, onChange: (value) => setFormData({
                                                                        ...formData,
                                                                        chatOptions: {
                                                                            ...formData.chatOptions,
                                                                            topP: value,
                                                                        },
                                                                    }) })] }), _jsxs("div", { children: [_jsxs("div", { className: "flex items-center justify-between mb-2", children: [_jsxs("label", { className: "block text-sm text-gray-600", children: ["\u6D88\u606F\u7A97\u53E3\u957F\u5EA6", _jsx("span", { className: "text-gray-400 ml-1 text-xs", children: "(1 - 100)" })] }), _jsx("span", { className: "text-sm font-medium text-gray-700 min-w-[40px] text-right", children: formData?.chatOptions?.messageLength })] }), _jsx(Slider, { min: 1, max: 100, step: 1, value: formData?.chatOptions?.messageLength, onChange: (value) => setFormData({
                                                                        ...formData,
                                                                        chatOptions: {
                                                                            ...formData.chatOptions,
                                                                            messageLength: value,
                                                                        },
                                                                    }) })] })] })] })] })), selectedKey === "knowledge" && (_jsxs("div", { children: [_jsxs("div", { className: "mb-4", children: [_jsx("label", { className: "block text-gray-700 font-medium mb-3", children: "\u77E5\u8BC6\u5E93" }), _jsx("p", { className: "text-sm text-gray-500 mb-4", children: "\u9009\u62E9\u667A\u80FD\u4F53\u53EF\u4EE5\u8BBF\u95EE\u7684\u77E5\u8BC6\u5E93\uFF0C\u652F\u6301\u591A\u9009\uFF08\u6700\u591A10\u4E2A\uFF09" }), knowledgeBases.length === 0 ? (_jsx("div", { className: "text-center py-8 text-gray-500", children: _jsx("p", { children: "\u6682\u65E0\u77E5\u8BC6\u5E93\uFF0C\u8BF7\u5148\u521B\u5EFA\u77E5\u8BC6\u5E93" }) })) : (_jsx("div", { className: "space-y-3", children: knowledgeBases.map((kb) => {
                                                        const kbId = kb.knowledgeBaseId;
                                                        const isSelected = formData.allowedKbs?.includes(kbId);
                                                        return (_jsx("div", { className: `border rounded-lg p-4 cursor-pointer transition-all hover:border-blue-400 hover:bg-blue-50 ${isSelected
                                                                ? "border-blue-500 bg-blue-50"
                                                                : "border-gray-200"}`, onClick: () => {
                                                                const currentKbs = formData.allowedKbs || [];
                                                                if (isSelected) {
                                                                    setFormData({
                                                                        ...formData,
                                                                        allowedKbs: currentKbs.filter((k) => k !== kbId),
                                                                    });
                                                                }
                                                                else {
                                                                    if (currentKbs.length >= 10) {
                                                                        return; // 最多选择10个
                                                                    }
                                                                    setFormData({
                                                                        ...formData,
                                                                        allowedKbs: [...currentKbs, kbId],
                                                                    });
                                                                }
                                                            }, children: _jsxs("div", { className: "flex items-start gap-2", children: [_jsx(Checkbox, { checked: isSelected, onChange: (e) => {
                                                                            e.stopPropagation();
                                                                            const currentKbs = formData.allowedKbs || [];
                                                                            if (e.target.checked) {
                                                                                if (currentKbs.length >= 10) {
                                                                                    return; // 最多选择10个
                                                                                }
                                                                                setFormData({
                                                                                    ...formData,
                                                                                    allowedKbs: [...currentKbs, kbId],
                                                                                });
                                                                            }
                                                                            else {
                                                                                setFormData({
                                                                                    ...formData,
                                                                                    allowedKbs: currentKbs.filter((k) => k !== kbId),
                                                                                });
                                                                            }
                                                                        }, className: "mr-3" }), _jsxs("div", { className: "flex-1", children: [_jsx("div", { className: "flex items-center mb-1", children: _jsx("span", { className: "font-medium text-gray-900", children: kb.name }) }), kb.description && (_jsx("p", { className: "text-sm text-gray-600", children: kb.description }))] })] }) }, kbId));
                                                    }) }))] }), _jsx("div", { children: _jsx("label", { className: "block text-gray-700 font-medium mb-1", children: "\u68C0\u7D22\u8BBE\u7F6E" }) })] })), selectedKey === "tools" && (_jsx("div", { children: _jsxs("div", { className: "mb-4", children: [_jsx("label", { className: "block text-gray-700 font-medium mb-3", children: "\u5DE5\u5177\u8C03\u7528" }), _jsx("p", { className: "text-sm text-gray-500 mb-4", children: "\u9009\u62E9\u667A\u80FD\u4F53\u53EF\u4EE5\u4F7F\u7528\u7684\u5DE5\u5177\uFF0C\u652F\u6301\u591A\u9009" }), tools.length === 0 ? (_jsx("div", { className: "text-center py-8 text-gray-500", children: _jsx("p", { children: "\u6682\u65E0\u53EF\u7528\u5DE5\u5177" }) })) : (_jsx("div", { className: "space-y-3", children: tools.map((tool) => {
                                                    const toolId = tool.name;
                                                    const isSelected = formData.allowedTools?.includes(toolId);
                                                    return (_jsx("div", { className: `border rounded-lg p-4 cursor-pointer transition-all hover:border-blue-400 hover:bg-blue-50 ${isSelected
                                                            ? "border-blue-500 bg-blue-50"
                                                            : "border-gray-200"}`, onClick: () => {
                                                            const currentTools = formData.allowedTools || [];
                                                            if (isSelected) {
                                                                setFormData({
                                                                    ...formData,
                                                                    allowedTools: currentTools.filter((t) => t !== toolId),
                                                                });
                                                            }
                                                            else {
                                                                setFormData({
                                                                    ...formData,
                                                                    allowedTools: [...currentTools, toolId],
                                                                });
                                                            }
                                                        }, children: _jsxs("div", { className: "flex items-start gap-2", children: [_jsx(Checkbox, { checked: isSelected, onChange: (e) => {
                                                                        e.stopPropagation();
                                                                        const currentTools = formData.allowedTools || [];
                                                                        if (e.target.checked) {
                                                                            setFormData({
                                                                                ...formData,
                                                                                allowedTools: [...currentTools, toolId],
                                                                            });
                                                                        }
                                                                        else {
                                                                            setFormData({
                                                                                ...formData,
                                                                                allowedTools: currentTools.filter((t) => t !== toolId),
                                                                            });
                                                                        }
                                                                    }, className: "mr-3" }), _jsxs("div", { className: "flex-1", children: [_jsx("div", { className: "flex items-center mb-1", children: _jsx("span", { className: "font-medium text-gray-900", children: tool.name }) }), _jsx("p", { className: "text-sm text-gray-600", children: tool.description })] })] }) }, toolId));
                                                }) }))] }) }))] }), _jsx("div", { className: "absolute bottom-0 right-0", children: _jsx(Button, { type: "primary", icon: _jsx(SaveOutlined, {}), loading: createAgentLoading, onClick: async () => {
                                    setCreateAgentLoading(true);
                                    try {
                                        if (isEditMode && editingAgent && updateAgentHandle) {
                                            await updateAgentHandle(editingAgent.id, formData);
                                        }
                                        else {
                                            await createAgentHandle(formData);
                                        }
                                        onClose();
                                    }
                                    finally {
                                        setCreateAgentLoading(false);
                                    }
                                }, children: isEditMode ? "更新" : "保存" }) })] })] }) }));
};
export default AddAgentModal;
