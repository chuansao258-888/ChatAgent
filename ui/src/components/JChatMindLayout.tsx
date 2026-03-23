import { Navigate, Route, Routes } from "react-router-dom";
import Layout from "../layout/Layout.tsx";
import Sidebar from "../layout/Sidebar.tsx";
import SideMenu from "./SideMenu.tsx";
import Content from "../layout/Content.tsx";
import AgentChatView from "./views/AgentChatView.tsx";

export default function JChatMindLayout() {
  return (
    <Layout>
      <Sidebar>
        <SideMenu />
      </Sidebar>
      <Content>
        <Routes>
          <Route path="/" element={<Navigate to="/chat" replace />} />
          <Route path="/agent" element={<Navigate to="/chat" replace />} />
          <Route path="/chat" element={<AgentChatView />} />
          <Route path="/chat/:chatSessionId" element={<AgentChatView />} />
          <Route path="*" element={<Navigate to="/chat" replace />} />
        </Routes>
      </Content>
    </Layout>
  );
}
