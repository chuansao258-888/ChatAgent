import { jsx as _jsx, jsxs as _jsxs } from "react/jsx-runtime";
import React, { useMemo, useState } from "react";
import { useParams } from "react-router-dom";
import { Card, Typography, Button, Upload, Table, Popconfirm, Space, message, Empty, } from "antd";
import { BookOutlined, UploadOutlined, DeleteOutlined, FileOutlined, } from "@ant-design/icons";
import { useKnowledgeBases } from "../../hooks/useKnowledgeBases.ts";
import { useDocuments } from "../../hooks/useDocuments.ts";
import { uploadDocument } from "../../api/api.ts";
const { Title, Text, Paragraph } = Typography;
const KnowledgeBaseView = () => {
    const { knowledgeBaseId } = useParams();
    const { knowledgeBases } = useKnowledgeBases();
    const { documents, loading, refreshDocuments, deleteDocument } = useDocuments(knowledgeBaseId);
    const [uploading, setUploading] = useState(false);
    // 查找当前知识库的详细信息
    const currentKnowledgeBase = useMemo(() => {
        if (!knowledgeBaseId)
            return null;
        return (knowledgeBases.find((kb) => kb.knowledgeBaseId === knowledgeBaseId) ||
            null);
    }, [knowledgeBaseId, knowledgeBases]);
    // 处理文件上传
    const handleUpload = async (options) => {
        const { file, onSuccess, onError } = options;
        if (!knowledgeBaseId) {
            message.error("请先选择知识库");
            return;
        }
        setUploading(true);
        try {
            await uploadDocument(knowledgeBaseId, file);
            message.success("文档上传成功");
            await refreshDocuments();
            onSuccess?.(file);
        }
        catch (error) {
            message.error(error instanceof Error ? error.message : "上传失败");
            onError?.(error);
        }
        finally {
            setUploading(false);
        }
    };
    // 格式化文件大小
    const formatFileSize = (bytes) => {
        if (bytes === 0)
            return "0 B";
        const k = 1024;
        const sizes = ["B", "KB", "MB", "GB"];
        const i = Math.floor(Math.log(bytes) / Math.log(k));
        return Math.round((bytes / Math.pow(k, i)) * 100) / 100 + " " + sizes[i];
    };
    // 表格列定义
    const columns = [
        {
            title: "文件名",
            dataIndex: "filename",
            key: "filename",
            render: (text) => (_jsxs(Space, { children: [_jsx(FileOutlined, {}), _jsx("span", { children: text })] })),
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
            render: (size) => formatFileSize(size),
        },
        {
            title: "操作",
            key: "action",
            width: 100,
            render: (_, record) => (_jsx(Popconfirm, { title: "\u786E\u5B9A\u8981\u5220\u9664\u8FD9\u4E2A\u6587\u6863\u5417\uFF1F", description: "\u5220\u9664\u540E\u5C06\u65E0\u6CD5\u6062\u590D", onConfirm: () => deleteDocument(record.id), okText: "\u786E\u5B9A", cancelText: "\u53D6\u6D88", children: _jsx(Button, { type: "text", danger: true, icon: _jsx(DeleteOutlined, {}), size: "small", children: "\u5220\u9664" }) })),
        },
    ];
    // 未选择知识库时的提示
    if (!knowledgeBaseId) {
        return (_jsx("div", { className: "flex flex-col h-full items-center justify-center p-6", children: _jsx(Empty, { image: _jsx(BookOutlined, { className: "text-6xl text-gray-300" }), description: _jsxs("div", { className: "mt-4", children: [_jsx(Title, { level: 4, type: "secondary", children: "\u672A\u9009\u62E9\u77E5\u8BC6\u5E93" }), _jsx(Text, { type: "secondary", className: "text-sm", children: "\u8BF7\u4ECE\u5DE6\u4FA7\u77E5\u8BC6\u5E93\u5217\u8868\u4E2D\u9009\u62E9\u4E00\u4E2A\u77E5\u8BC6\u5E93\u67E5\u770B\u8BE6\u60C5" })] }) }) }));
    }
    // 知识库不存在
    if (!currentKnowledgeBase) {
        return (_jsx("div", { className: "flex flex-col h-full items-center justify-center p-6", children: _jsx(Empty, { description: _jsxs("div", { className: "mt-4", children: [_jsx(Title, { level: 4, type: "secondary", children: "\u77E5\u8BC6\u5E93\u4E0D\u5B58\u5728" }), _jsx(Text, { type: "secondary", className: "text-sm", children: "\u8BF7\u68C0\u67E5\u77E5\u8BC6\u5E93 ID \u662F\u5426\u6B63\u786E" })] }) }) }));
    }
    // 显示知识库详情和文档列表
    return (_jsx("div", { className: "flex flex-col h-full p-6 overflow-y-auto", children: _jsxs("div", { className: "max-w-6xl w-full mx-auto", children: [_jsx("div", { className: "mb-3", children: _jsx(Card, { children: _jsxs("div", { className: "flex items-start gap-4", children: [_jsx("div", { className: "w-16 h-16 rounded-lg bg-gradient-to-br from-blue-200 to-purple-200 flex items-center justify-center text-3xl shrink-0", children: _jsx(BookOutlined, {}) }), _jsxs("div", { className: "flex-1", children: [_jsx(Title, { level: 3, className: "mb-2", children: currentKnowledgeBase.name }), currentKnowledgeBase.description && (_jsx(Paragraph, { className: "text-gray-600 mb-0", children: currentKnowledgeBase.description })), _jsxs(Text, { type: "secondary", className: "text-sm", children: ["\u77E5\u8BC6\u5E93 ID: ", currentKnowledgeBase.knowledgeBaseId] })] })] }) }) }), _jsx("div", { className: "mb-3", children: _jsxs(Card, { title: "\u4E0A\u4F20\u6587\u6863", children: [_jsx(Upload, { customRequest: handleUpload, showUploadList: false, accept: ".md", disabled: uploading, children: _jsx(Button, { type: "primary", icon: _jsx(UploadOutlined, {}), loading: uploading, size: "large", children: "\u9009\u62E9\u6587\u4EF6\u4E0A\u4F20" }) }), _jsx(Text, { type: "secondary", className: "block mt-2 text-xs", children: "\u652F\u6301\u683C\u5F0F: Markdown" })] }) }), _jsx("div", { className: "mb-3", children: _jsx(Card, { title: `文档列表 (${documents.length})`, children: loading ? (_jsx("div", { className: "text-center py-8", children: _jsx(Text, { type: "secondary", children: "\u52A0\u8F7D\u4E2D..." }) })) : documents.length === 0 ? (_jsx(Empty, { description: _jsx(Text, { type: "secondary", children: "\u6682\u65E0\u6587\u6863\uFF0C\u8BF7\u4E0A\u4F20\u6587\u6863" }) })) : (_jsx(Table, { columns: columns, dataSource: documents, rowKey: "id", pagination: {
                                pageSize: 10,
                                // showSizeChanger: true,
                                showTotal: (total) => `共 ${total} 条`,
                            } })) }) })] }) }));
};
export default KnowledgeBaseView;
