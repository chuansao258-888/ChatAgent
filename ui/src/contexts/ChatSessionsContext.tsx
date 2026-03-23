import React, { useEffect, useState, useCallback } from "react";
import {
  type ChatSessionVO,
  getChatSessions,
  deleteChatSession,
} from "../api/api.ts";
import { ChatSessionsContext } from "./chatSessionsContext.ts";
import { useAuth } from "../hooks/useAuth.ts";

export function ChatSessionsProvider({ children }: { children: React.ReactNode }) {
  const [chatSessions, setChatSessions] = useState<ChatSessionVO[]>([]);
  const [loading, setLoading] = useState(false);
  const { isAuthenticated } = useAuth();

  const fetchChatSessions = useCallback(async () => {
    if (!isAuthenticated) {
      setChatSessions([]);
      setLoading(false);
      return;
    }

    setLoading(true);
    try {
      const resp = await getChatSessions();
      setChatSessions(resp.chatSessions);
    } finally {
      setLoading(false);
    }
  }, [isAuthenticated]);

  useEffect(() => {
    void fetchChatSessions();
  }, [fetchChatSessions]);

  const deleteChatSessionHandle = useCallback(async (chatSessionId: string) => {
    await deleteChatSession(chatSessionId);
    await fetchChatSessions();
  }, [fetchChatSessions]);

  return (
    <ChatSessionsContext.Provider
      value={{
        chatSessions,
        loading,
        refreshChatSessions: fetchChatSessions,
        deleteChatSession: deleteChatSessionHandle,
      }}
    >
      {children}
    </ChatSessionsContext.Provider>
  );
}
