import { BrowserRouter } from "react-router-dom";
import JChatMindLayout from "./components/JChatMindLayout.tsx";
import AuthDialog from "./components/auth/AuthDialog.tsx";
import { AuthProvider } from "./contexts/AuthContext.tsx";
import { ChatSessionsProvider } from "./contexts/ChatSessionsContext.tsx";

function AppShell() {
  return (
    <BrowserRouter>
      <ChatSessionsProvider>
        <JChatMindLayout />
        <AuthDialog />
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
