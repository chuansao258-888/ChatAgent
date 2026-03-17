import { createContext } from "react";
import type { ChatSessionVO } from "../api/api.ts";

export interface ChatSessionsContextType {
  chatSessions: ChatSessionVO[];
  loading: boolean;
  refreshChatSessions: () => Promise<void>;
  deleteChatSession: (chatSessionId: string) => Promise<void>;
}

export const ChatSessionsContext = createContext<
  ChatSessionsContextType | undefined
>(undefined);
