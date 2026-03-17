import { jsx as _jsx } from "react/jsx-runtime";
import { BrowserRouter } from "react-router-dom";
import JChatMindLayout from "./components/JChatMindLayout.tsx";
import { ChatSessionsProvider } from "./contexts/ChatSessionsContext.tsx";
function App() {
    return (_jsx(BrowserRouter, { children: _jsx(ChatSessionsProvider, { children: _jsx(JChatMindLayout, {}) }) }));
}
export default App;
