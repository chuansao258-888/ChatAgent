import { jsx as _jsx, jsxs as _jsxs } from "react/jsx-runtime";
import React, { useMemo } from "react";
import { Button, Divider } from "antd";
import { PlusOutlined, BookOutlined } from "@ant-design/icons";
import { getKnowledgeBaseEmoji } from "../../utils";
const KnowledgeBaseTabContent = ({ knowledgeBases, onCreateKnowledgeBaseClick, onSelectKnowledgeBase, }) => {
    // 为每个知识库生成 emoji
    const knowledgeBasesWithEmoji = useMemo(() => {
        return knowledgeBases.map((kb) => ({
            ...kb,
            emoji: getKnowledgeBaseEmoji(kb.knowledgeBaseId),
        }));
    }, [knowledgeBases]);
    return (_jsxs("div", { className: "flex flex-col h-full", children: [_jsx(Button, { color: "geekblue", variant: "filled", icon: _jsx(PlusOutlined, {}), onClick: onCreateKnowledgeBaseClick, className: "w-full", children: "\u65B0\u5EFA\u77E5\u8BC6\u5E93" }), _jsx(Divider, {}), _jsx("div", { className: "flex-1 overflow-y-scroll rounded-lg", children: knowledgeBases.length === 0 ? (_jsxs("div", { className: "flex flex-col items-center justify-center h-full text-gray-400", children: [_jsx(BookOutlined, { className: "text-4xl mb-2" }), _jsx("p", { className: "text-sm", children: "\u6682\u65E0\u77E5\u8BC6\u5E93" }), _jsx("p", { className: "text-xs mt-1", children: "\u70B9\u51FB\u4E0A\u65B9\u6309\u94AE\u521B\u5EFA" })] })) : (_jsx("div", { className: "space-y-1.5 p-1.5", children: knowledgeBasesWithEmoji.map((kb) => (_jsx("div", { onClick: () => onSelectKnowledgeBase?.(kb.knowledgeBaseId), className: "w-full px-3 py-2.5 rounded-lg bg-white cursor-pointer transition-all hover:bg-gray-100 hover:shadow-sm", children: _jsxs("div", { className: "flex items-start gap-3", children: [_jsx("div", { className: "w-8 h-8 rounded-lg bg-gradient-to-br from-blue-200 to-purple-200 flex items-center justify-center shrink-0 text-lg mt-0.5", children: kb.emoji }), _jsxs("div", { className: "flex-1 min-w-0", children: [_jsx("div", { className: "font-medium text-gray-900 truncate", children: kb.name }), kb.description && (_jsx("div", { className: "text-xs text-gray-500 mt-1 line-clamp-2", children: kb.description }))] })] }) }, kb.knowledgeBaseId))) })) })] }));
};
export default KnowledgeBaseTabContent;
