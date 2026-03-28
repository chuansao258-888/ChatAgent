import React, { useCallback, useEffect, useState } from "react";
import { message as antdMessage } from "antd";
import { useLocation, useNavigate, useParams } from "react-router-dom";
import { getAccessToken } from "../../auth/token.ts";
import { BASE_URL } from "../../api/http.ts";
import {
  createChatMessage,
  createChatSession,
  detachChatSessionFile,
  getChatMessagesBySessionId,
  getChatSession,
  getChatSessionFiles,
  type ChatSessionFileVO,
  uploadChatSessionFile,
} from "../../api/api.ts";
import { useAuth } from "../../hooks/useAuth.ts";
import { useChatSessions } from "../../hooks/useChatSessions.ts";
import type { ChatMessageVO, SseMessage, SseMessageType } from "../../types";
import AgentChatHistory from "./agentChatView/AgentChatHistory.tsx";
import AgentChatInput from "./agentChatView/AgentChatInput.tsx";
import EmptyAgentChatView from "./agentChatView/EmptyAgentChatView.tsx";

function isMissingChatSessionError(error: unknown): boolean {
  return (
    error instanceof Error &&
    (error.message.includes("Chat session not found") ||
      error.message.includes("HTTP error! status: 404"))
  );
}

const AgentChatView: React.FC = () => {
  const { chatSessionId } = useParams<{ chatSessionId: string }>();
  const navigate = useNavigate();
  const { state } = useLocation();
  const { initializing, isAuthenticated, openAuthDialog } = useAuth();
  const [loading, setLoading] = useState(false);
  const [uploadingFile, setUploadingFile] = useState(false);
  const { refreshChatSessions } = useChatSessions();

  const [messages, setMessages] = useState<ChatMessageVO[]>([]);
  const [sessionFiles, setSessionFiles] = useState<ChatSessionFileVO[]>([]);
  const [displayAgentStatus, setDisplayAgentStatus] = useState(false);
  const [agentStatusText, setAgentStatusText] = useState("");
  const [agentStatusType, setAgentStatusType] = useState<
    SseMessageType | undefined
  >(undefined);

  const addMessage = (message: ChatMessageVO) => {
    setMessages((prevMessages) => {
      if (prevMessages.some((m) => m.id === message.id)) {
        return prevMessages;
      }
      return [...prevMessages, message];
    });
  };

  const getChatData = useCallback(async () => {
    if (!chatSessionId) {
      setMessages([]);
      setSessionFiles([]);
      return;
    }
    if (initializing) {
      return;
    }
    if (!isAuthenticated) {
      setMessages([]);
      setSessionFiles([]);
      navigate("/chat", { replace: true });
      return;
    }
    try {
      const [messagesResp, , filesResp] = await Promise.all([
        getChatMessagesBySessionId(chatSessionId),
        getChatSession(chatSessionId),
        getChatSessionFiles(chatSessionId),
      ]);
      setMessages(messagesResp.chatMessages);
      setSessionFiles(filesResp.files);
    } catch (error) {
      if (isMissingChatSessionError(error)) {
        setMessages([]);
        setSessionFiles([]);
        await refreshChatSessions();
        navigate("/chat", { replace: true });
        return;
      }
      throw error;
    }
  }, [chatSessionId, initializing, isAuthenticated, navigate, refreshChatSessions]);

  useEffect(() => {
    if (!chatSessionId || initializing) {
      return;
    }
    void getChatData().catch((error) => {
      console.error("Failed to load chat session:", error);
      antdMessage.error("Failed to load the conversation. Please try again.");
    });
  }, [chatSessionId, getChatData, initializing]);

  const handleSendMessage = async (value: string | { text: string }) => {
    const message = typeof value === "string" ? value : value.text;

    if (!message || !message.trim()) {
      return;
    }

    const activeChatSessionId = chatSessionId;

    if (!isAuthenticated) {
      openAuthDialog("login");
      return;
    }

    if (!activeChatSessionId) {
      setLoading(true);
      try {
        const response = await createChatSession({
          title: message.slice(0, 20),
        });
        await refreshChatSessions();
        navigate(`/chat/${response.chatSessionId}`, {
          replace: true,
          state: {
            init: false,
            initMessage: message,
          },
        });
      } catch (error) {
        console.error("Failed to create chat session:", error);
        antdMessage.error("Failed to create the conversation. Please try again.");
      } finally {
        setLoading(false);
      }
      return;
    }

    if (state?.init) {
      await createChatMessage({
        sessionId: activeChatSessionId,
        role: "user",
        content: state.initMessage ?? "",
      });
    } else {
      await createChatMessage({
        sessionId: activeChatSessionId,
        role: "user",
        content: message,
      });
    }
    await getChatData();
  };

  const handleUploadFile = useCallback(
    async (file: File) => {
      if (!isAuthenticated) {
        openAuthDialog("login");
        return;
      }
      if (!chatSessionId) {
        antdMessage.warning("Start a chat first, then attach files.");
        return;
      }

      setUploadingFile(true);
      try {
        await uploadChatSessionFile(chatSessionId, file);
        const filesResp = await getChatSessionFiles(chatSessionId);
        setSessionFiles(filesResp.files);
        antdMessage.success(`${file.name} uploaded.`);
      } catch (error) {
        console.error("Failed to upload chat file:", error);
        antdMessage.error("Failed to upload file. Please try again.");
      } finally {
        setUploadingFile(false);
      }
    },
    [chatSessionId, isAuthenticated, openAuthDialog],
  );

  const handleRemoveFile = useCallback(
    async (sessionFileId: string) => {
      if (!chatSessionId) {
        return;
      }
      try {
        await detachChatSessionFile(chatSessionId, sessionFileId);
        setSessionFiles((prevFiles) =>
          prevFiles.filter((file) => file.id !== sessionFileId),
        );
      } catch (error) {
        console.error("Failed to remove chat file:", error);
        antdMessage.error("Failed to remove file. Please try again.");
      }
    },
    [chatSessionId],
  );

  useEffect(() => {
    if (!chatSessionId || initializing || !isAuthenticated) {
      return;
    }
    const accessToken = getAccessToken();
    if (!accessToken) {
      return;
    }
    const es = new EventSource(
      `${BASE_URL}/sse/connect/${chatSessionId}?access_token=${encodeURIComponent(accessToken)}`,
    );

    es.onmessage = (event) => {
      console.log("Received message:", event.data);
    };

    es.onerror = (error) => {
      console.error("SSE error:", error);
    };

    es.addEventListener("message", (event) => {
      const message = JSON.parse(event.data) as SseMessage;
      if (message.type === "AI_GENERATED_CONTENT") {
        addMessage(message.payload.message);
      } else if (message.type === "AI_PLANNING") {
        setDisplayAgentStatus(true);
        setAgentStatusText(message.payload.statusText);
        setAgentStatusType("AI_PLANNING");
      } else if (message.type === "AI_THINKING") {
        setDisplayAgentStatus(true);
        setAgentStatusText(message.payload.statusText);
        setAgentStatusType("AI_THINKING");
      } else if (message.type === "AI_EXECUTING") {
        setDisplayAgentStatus(true);
        setAgentStatusText(message.payload.statusText);
        setAgentStatusType("AI_EXECUTING");
      } else if (message.type === "AI_DONE") {
        setDisplayAgentStatus(false);
        setAgentStatusText("");
        setAgentStatusType(undefined);
      } else {
        throw new Error(`Unknown message type: ${message.type}`);
      }
    });

    return () => {
      es.close();
    };
  }, [chatSessionId, initializing, isAuthenticated]);

  if (!chatSessionId) {
    return <EmptyAgentChatView loading={loading} />;
  }

  return (
    <div className="relative flex h-full flex-col overflow-x-hidden bg-[#212121] text-white">
      <AgentChatHistory
        messages={messages}
        displayAgentStatus={displayAgentStatus}
        agentStatusText={agentStatusText}
        agentStatusType={agentStatusType}
      />
      <div className="bg-[#212121] px-5 pb-6 pt-2 md:px-8">
        <AgentChatInput
          onSend={handleSendMessage}
          onUploadFile={handleUploadFile}
          onRemoveFile={handleRemoveFile}
          attachments={sessionFiles}
          uploading={uploadingFile}
        />
      </div>
    </div>
  );
};

export default AgentChatView;
