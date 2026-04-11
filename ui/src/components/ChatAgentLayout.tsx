import { Navigate, Outlet, Route, Routes } from "react-router-dom";
import Layout from "../layout/Layout.tsx";
import Sidebar from "../layout/Sidebar.tsx";
import SideMenu from "./SideMenu.tsx";
import Content from "../layout/Content.tsx";
import AgentChatView from "./views/AgentChatView.tsx";
import AdminLayout from "./admin/AdminLayout.tsx";
import AdminRouteGuard from "./admin/AdminRouteGuard.tsx";
import AdminOverviewPage from "./admin/pages/AdminOverviewPage.tsx";
import AssistantSettingsPage from "./admin/pages/AssistantSettingsPage.tsx";
import IntentTreePage from "./admin/pages/IntentTreePage.tsx";
import KnowledgeBaseDetailPage from "./admin/pages/KnowledgeBaseDetailPage.tsx";
import KnowledgeBaseListPage from "./admin/pages/KnowledgeBaseListPage.tsx";
import McpOperationsPage from "./admin/pages/McpOperationsPage.tsx";
import UserManagementPage from "./admin/pages/UserManagementPage.tsx";

function ChatWorkspaceLayout() {
  return (
    <Layout>
      <Sidebar>
        <SideMenu />
      </Sidebar>
      <Content>
        <Outlet />
      </Content>
    </Layout>
  );
}

export default function ChatAgentLayout() {
  return (
    <Routes>
      <Route path="/" element={<Navigate to="/chat" replace />} />
      <Route element={<ChatWorkspaceLayout />}>
        <Route path="/agent" element={<Navigate to="/chat" replace />} />
        <Route path="/chat" element={<AgentChatView />} />
        <Route path="/chat/:chatSessionId" element={<AgentChatView />} />
      </Route>
      <Route
        path="/admin"
        element={
          <AdminRouteGuard>
            <AdminLayout />
          </AdminRouteGuard>
        }
      >
        <Route index element={<AdminOverviewPage />} />
        <Route path="mcp" element={<McpOperationsPage />} />
        <Route path="knowledge-bases" element={<KnowledgeBaseListPage />} />
        <Route
          path="knowledge-bases/:kbId"
          element={<KnowledgeBaseDetailPage />}
        />
        <Route path="intent-tree" element={<IntentTreePage />} />
        <Route path="users" element={<UserManagementPage />} />
        <Route path="assistant" element={<AssistantSettingsPage />} />
        <Route path="*" element={<Navigate to="/admin" replace />} />
      </Route>
      <Route path="*" element={<Navigate to="/chat" replace />} />
    </Routes>
  );
}
