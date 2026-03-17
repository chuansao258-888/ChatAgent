import { jsx as _jsx } from "react/jsx-runtime";
import React, { createContext, useContext, useEffect, useState, useCallback } from "react";
import { getChatSessions, deleteChatSession, } from "../api/api.ts";
const ChatSessionsContext = createContext(undefined);
export function ChatSessionsProvider({ children }) {
    const [chatSessions, setChatSessions] = useState([]);
    const [loading, setLoading] = useState(false);
    const fetchChatSessions = useCallback(async () => {
        setLoading(true);
        try {
            const resp = await getChatSessions();
            setChatSessions(resp.chatSessions);
        }
        finally {
            setLoading(false);
        }
    }, []);
    useEffect(() => {
        fetchChatSessions();
    }, [fetchChatSessions]);
    const deleteChatSessionHandle = useCallback(async (chatSessionId) => {
        await deleteChatSession(chatSessionId);
        await fetchChatSessions();
    }, [fetchChatSessions]);
    return (_jsx(ChatSessionsContext.Provider, { value: {
            chatSessions,
            loading,
            refreshChatSessions: fetchChatSessions,
            deleteChatSession: deleteChatSessionHandle,
        }, children: children }));
}
export function useChatSessionsContext() {
    const context = useContext(ChatSessionsContext);
    if (context === undefined) {
        throw new Error("useChatSessionsContext must be used within a ChatSessionsProvider");
    }
    return context;
}
