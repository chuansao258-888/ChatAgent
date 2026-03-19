import { BrowserRouter } from "react-router-dom";
import { Spin } from "antd";
import JChatMindLayout from "./components/JChatMindLayout.tsx";
import LoginPage from "./components/auth/LoginPage.tsx";
import { AuthProvider } from "./contexts/AuthContext.tsx";
import { ChatSessionsProvider } from "./contexts/ChatSessionsContext.tsx";
import { useAuth } from "./hooks/useAuth.ts";

function AppShell() {
  const { initializing, isAuthenticated } = useAuth();

  if (initializing) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-slate-100">
        <Spin size="large" />
      </div>
    );
  }

  if (!isAuthenticated) {
    return <LoginPage />;
  }

  return (
    <BrowserRouter>
      <ChatSessionsProvider>
        <JChatMindLayout />
      </ChatSessionsProvider>
    </BrowserRouter>
  );
}

function App() {
  return (
    <AuthProvider>
      <AppShell />
    </AuthProvider>
  );
}

export default App;
