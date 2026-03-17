import { jsx as _jsx, jsxs as _jsxs } from "react/jsx-runtime";
import React, { useState } from "react";
import { Button, Input, Modal } from "antd";
import TextArea from "antd/es/input/TextArea";
import { SaveOutlined } from "@ant-design/icons";
import {} from "../../api/api.ts";
const AddKnowledgeBaseModal = ({ open, onClose, createKnowledgeBaseHandle, }) => {
    const [formData, setFormData] = useState({
        name: "",
        description: "",
    });
    const [createLoading, setCreateLoading] = useState(false);
    const handleSubmit = async () => {
        if (!formData.name.trim()) {
            return;
        }
        setCreateLoading(true);
        try {
            await createKnowledgeBaseHandle(formData);
            // 重置表单
            setFormData({
                name: "",
                description: "",
            });
            onClose();
        }
        finally {
            setCreateLoading(false);
        }
    };
    const handleCancel = () => {
        // 重置表单
        setFormData({
            name: "",
            description: "",
        });
        onClose();
    };
    return (_jsx(Modal, { open: open, onCancel: handleCancel, title: "\u65B0\u5EFA\u77E5\u8BC6\u5E93", footer: null, width: 600, centered: true, children: _jsxs("div", { className: "py-4", children: [_jsxs("div", { className: "mb-4", children: [_jsxs("label", { className: "block text-gray-700 font-medium mb-2", children: ["\u540D\u79F0 ", _jsx("span", { className: "text-red-500", children: "*" })] }), _jsx(Input, { placeholder: "\u8BF7\u8F93\u5165\u77E5\u8BC6\u5E93\u540D\u79F0", value: formData.name, onChange: (e) => setFormData({ ...formData, name: e.target.value }), onPressEnter: handleSubmit })] }), _jsxs("div", { className: "mb-6", children: [_jsx("label", { className: "block text-gray-700 font-medium mb-2", children: "\u63CF\u8FF0" }), _jsx(TextArea, { placeholder: "\u8BF7\u8F93\u5165\u77E5\u8BC6\u5E93\u63CF\u8FF0\uFF08\u53EF\u9009\uFF09", rows: 4, value: formData.description, onChange: (e) => setFormData({ ...formData, description: e.target.value }) })] }), _jsxs("div", { className: "flex justify-end gap-2", children: [_jsx(Button, { onClick: handleCancel, children: "\u53D6\u6D88" }), _jsx(Button, { type: "primary", icon: _jsx(SaveOutlined, {}), loading: createLoading, onClick: handleSubmit, disabled: !formData.name.trim(), children: "\u521B\u5EFA" })] })] }) }));
};
export default AddKnowledgeBaseModal;
