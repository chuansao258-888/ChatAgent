import { jsx as _jsx, Fragment as _Fragment, jsxs as _jsxs } from "react/jsx-runtime";
import React, { useState, useRef, useEffect, useCallback } from "react";
import { Bubble } from "@ant-design/x";
import XMarkdown from "@ant-design/x-markdown";
import { ToolOutlined, CheckCircleOutlined, RobotOutlined, DownOutlined, RightOutlined, } from "@ant-design/icons";
// 工具调用展示组件（简化版，用于 assistant 消息内）
const ToolCallDisplay = ({ toolCall }) => {
    let parsedArgs = {};
    try {
        parsedArgs = JSON.parse(toolCall.arguments);
    }
    catch {
        // 如果解析失败，使用原始字符串
    }
    const argCount = Object.keys(parsedArgs).length;
    const argPreview = argCount > 0
        ? Object.keys(parsedArgs).slice(0, 2).join(", ") + (argCount > 2 ? "..." : "")
        : toolCall.arguments.slice(0, 50) + (toolCall.arguments.length > 50 ? "..." : "");
    return (_jsxs("div", { className: "text-xs text-gray-500 flex items-center gap-1.5", children: [_jsx(ToolOutlined, { className: "text-blue-500" }), _jsx("span", { className: "font-mono text-blue-600", children: toolCall.name }), argPreview && (_jsxs(_Fragment, { children: [_jsx("span", { className: "text-gray-400", children: "\u00B7" }), _jsx("span", { className: "text-gray-500 truncate max-w-[200px]", children: argPreview })] }))] }));
};
// 工具响应展示组件（可折叠）
const ToolResponseDisplay = ({ toolResponse, }) => {
    const [expanded, setExpanded] = useState(false);
    let parsedData = null;
    let isJson = false;
    let dataPreview = "";
    try {
        parsedData = JSON.parse(toolResponse.responseData);
        isJson = true;
        const jsonStr = JSON.stringify(parsedData);
        dataPreview = jsonStr.length > 100 ? jsonStr.slice(0, 100) + "..." : jsonStr;
    }
    catch {
        dataPreview = toolResponse.responseData.length > 100
            ? toolResponse.responseData.slice(0, 100) + "..."
            : toolResponse.responseData;
    }
    return (_jsxs("div", { className: "my-1.5 text-xs", children: [_jsxs("div", { className: "flex items-center gap-2 text-gray-500 cursor-pointer hover:text-gray-700 transition-colors", onClick: () => setExpanded(!expanded), children: [expanded ? (_jsx(DownOutlined, { className: "text-gray-400" })) : (_jsx(RightOutlined, { className: "text-gray-400" })), _jsx(CheckCircleOutlined, { className: "text-green-500" }), _jsx("span", { className: "font-mono text-green-600", children: toolResponse.name }), _jsx("span", { className: "text-gray-400", children: "\u00B7" }), _jsx("span", { className: "text-gray-500 truncate flex-1", children: dataPreview })] }), expanded && (_jsx("div", { className: "ml-5 mt-1.5 p-2 bg-gray-50 rounded border border-gray-200", children: _jsx("div", { className: "text-xs text-gray-600 font-mono", children: isJson ? (_jsx("pre", { className: "whitespace-pre-wrap break-words overflow-x-auto max-h-60 overflow-y-auto", children: JSON.stringify(parsedData, null, 2) })) : (_jsx("div", { className: "whitespace-pre-wrap break-words", children: toolResponse.responseData })) }) }))] }));
};
const AgentChatHistory = ({ messages, displayAgentStatus = false, agentStatusText = "", agentStatusType, }) => {
    // 滚动容器引用
    const scrollContainerRef = useRef(null);
    // 是否允许自动滚动（用户是否接近底部）
    const [isNearBottom, setIsNearBottom] = useState(true);
    // 容错阈值（像素）
    const SCROLL_THRESHOLD = 20;
    // 上一次消息数量，用于检测新消息
    const prevMessagesLengthRef = useRef(messages.length);
    // 检查是否接近底部
    const checkIfNearBottom = useCallback(() => {
        const container = scrollContainerRef.current;
        if (!container)
            return false;
        const { scrollTop, clientHeight, scrollHeight } = container;
        const distanceFromBottom = scrollHeight - scrollTop - clientHeight;
        return distanceFromBottom <= SCROLL_THRESHOLD;
    }, []);
    // 滚动到底部
    const scrollToBottom = useCallback(() => {
        const container = scrollContainerRef.current;
        if (!container)
            return;
        // 使用 requestAnimationFrame 确保 DOM 更新完成后再滚动
        requestAnimationFrame(() => {
            if (container) {
                container.scrollTop = container.scrollHeight;
            }
        });
    }, []);
    // 处理滚动事件，实时更新是否接近底部的状态
    const handleScroll = useCallback(() => {
        const nearBottom = checkIfNearBottom();
        setIsNearBottom(nearBottom);
    }, [checkIfNearBottom]);
    // 监听滚动事件
    useEffect(() => {
        const container = scrollContainerRef.current;
        if (!container)
            return;
        // 初始化时检查是否在底部（延迟执行以避免同步 setState）
        const initTimer = setTimeout(() => {
            setIsNearBottom(checkIfNearBottom());
        }, 0);
        container.addEventListener("scroll", handleScroll, { passive: true });
        return () => {
            clearTimeout(initTimer);
            container.removeEventListener("scroll", handleScroll);
        };
    }, [handleScroll, checkIfNearBottom]);
    // 监听消息变化，决定是否自动滚动
    useEffect(() => {
        const hasNewMessage = messages.length > prevMessagesLengthRef.current;
        prevMessagesLengthRef.current = messages.length;
        // 如果有新消息且用户接近底部，则自动滚动
        if (hasNewMessage && isNearBottom) {
            scrollToBottom();
        }
    }, [messages, isNearBottom, scrollToBottom]);
    // 当 displayAgentStatus 变化时，如果用户接近底部，也自动滚动
    useEffect(() => {
        if (displayAgentStatus && isNearBottom) {
            scrollToBottom();
        }
    }, [displayAgentStatus, isNearBottom, scrollToBottom]);
    // 获取状态标签
    const getStatusLabel = () => {
        switch (agentStatusType) {
            case "AI_PLANNING":
                return "规划中";
            case "AI_THINKING":
                return "思考中";
            case "AI_EXECUTING":
                return "执行中";
            default:
                return "处理中";
        }
    };
    return (_jsxs("div", { ref: scrollContainerRef, className: "flex-1 px-16 pt-4 overflow-y-scroll", children: [messages.map((message) => {
                return (_jsxs("div", { className: "mb-4", children: [message.role === "assistant" && (_jsx(Bubble, { content: _jsxs("div", { className: "w-full", children: [message.metadata?.toolCalls &&
                                        message.metadata.toolCalls.length > 0 && (_jsx("div", { className: "mb-2 flex flex-wrap gap-2", children: message.metadata.toolCalls.map((toolCall) => (_jsx(ToolCallDisplay, { toolCall: toolCall }, toolCall.id))) })), message.content && (_jsx("div", { children: _jsx(XMarkdown, { streaming: { enableAnimation: false, hasNextChunk: true }, children: message.content }) }))] }), placement: "start" })), message.role === "tool" && message.metadata?.toolResponse && (_jsx("div", { className: "flex justify-start", children: _jsx("div", { className: "max-w-[85%]", children: _jsx(ToolResponseDisplay, { toolResponse: message.metadata.toolResponse }) }) })), message.role === "user" && (_jsx(Bubble, { content: message.content, placement: "end" })), message.role === "system" && (_jsx("div", { className: "flex justify-center", children: _jsxs("div", { className: "px-3 py-1 bg-gray-100 text-gray-600 text-xs rounded-full flex items-center gap-1", children: [_jsx(RobotOutlined, {}), _jsx("span", { children: message.content })] }) }))] }, message.id));
            }), displayAgentStatus && (_jsx("div", { className: "mb-3", children: _jsx("div", { className: "animate-pulse", style: {
                        animation: "pulse 0.8s cubic-bezier(0.4, 0, 0.6, 1) infinite",
                        filter: "brightness(1.15)",
                    }, children: _jsx(Bubble, { content: _jsxs("span", { className: "flex items-center gap-2", children: [_jsxs("span", { className: "font-semibold text-blue-600", style: {
                                        animation: "pulse 0.7s cubic-bezier(0.4, 0, 0.6, 1) infinite",
                                        textShadow: "0 0 10px rgba(37, 99, 235, 1), 0 0 20px rgba(37, 99, 235, 0.8), 0 0 30px rgba(37, 99, 235, 0.5)",
                                        filter: "brightness(1.3)",
                                    }, children: ["\u2728 ", getStatusLabel()] }), _jsx("span", { className: "text-gray-400", children: "\u00B7" }), _jsx("span", { className: "text-gray-600", children: agentStatusText })] }), placement: "start" }) }) }))] }));
};
export default AgentChatHistory;
