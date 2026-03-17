import { useContext } from "react";
import { ChatSessionsContext } from "../contexts/chatSessionsContext.ts";

export function useChatSessions() {
  const context = useContext(ChatSessionsContext);
  if (context === undefined) {
    throw new Error(
      "useChatSessions must be used within a ChatSessionsProvider",
    );
  }
  return context;
}
