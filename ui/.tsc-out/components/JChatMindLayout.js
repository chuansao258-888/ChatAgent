import { jsx as _jsx, jsxs as _jsxs } from "react/jsx-runtime";
import { Routes, Route } from "react-router-dom";
import Layout from "../layout/Layout.tsx";
import Sidebar from "../layout/Sidebar.tsx";
import SideMenu from "./SideMenu.tsx";
import Content from "../layout/Content.tsx";
import AgentChatView from "./views/AgentChatView.tsx";
import KnowledgeBaseView from "./views/KnowledgeBaseView.tsx";
export default function JChatMindLayout() {
    return (_jsxs(Layout, { children: [_jsx(Sidebar, { children: _jsx(SideMenu, {}) }), _jsx(Content, { children: _jsxs(Routes, { children: [_jsx(Route, { path: "/", element: _jsx(AgentChatView, {}) }), _jsx(Route, { path: "/agent", element: _jsx(AgentChatView, {}) }), _jsx(Route, { path: "/chat", element: _jsx(AgentChatView, {}) }), _jsx(Route, { path: "/chat/:chatSessionId", element: _jsx(AgentChatView, {}) }), _jsx(Route, { path: "/knowledge-base", element: _jsx(KnowledgeBaseView, {}) }), _jsx(Route, { path: "/knowledge-base/:knowledgeBaseId", element: _jsx(KnowledgeBaseView, {}) })] }) })] }));
}
