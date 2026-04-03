import { BrowserRouter } from "react-router-dom";
import ChatAgentLayout from "./components/ChatAgentLayout.tsx";
import AuthDialog from "./components/auth/AuthDialog.tsx";
import { AuthProvider } from "./contexts/AuthContext.tsx";
import { ChatSessionsProvider } from "./contexts/ChatSessionsContext.tsx";

function AppShell() {
  return (
    <BrowserRouter>
      <ChatSessionsProvider>
        <ChatAgentLayout />
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
