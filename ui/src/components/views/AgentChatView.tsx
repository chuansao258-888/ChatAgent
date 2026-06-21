import React, { useCallback, useEffect, useRef, useState } from "react";
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
import type {
  AgentExecutionMode,
  ChatMessageVO,
  SseMessage,
  SseMessageType,
} from "../../types";
import AgentChatHistory from "./agentChatView/AgentChatHistory.tsx";
import AgentChatInput from "./agentChatView/AgentChatInput.tsx";
import EmptyAgentChatView from "./agentChatView/EmptyAgentChatView.tsx";
import {
  getStoredExecutionMode,
  setStoredExecutionMode,
} from "./agentChatView/executionModeStorage.ts";
import {
  clearPendingTurnId,
  getPendingTurnId,
  setPendingTurnId,
} from "./agentChatView/pendingTurnStorage.ts";

const PENDING_HINT_MS = 15_000;
const PENDING_TIMEOUT_MS = 30_000;
const COMPENSATION_POLL_MS = 10_000;
const FINAL_RECONCILE_ATTEMPTS = 4;
const FINAL_RECONCILE_DELAY_MS = 750;

function isMissingChatSessionError(error: unknown): boolean {
  return (
    error instanceof Error &&
    (error.message.includes("Chat session not found") ||
      error.message.includes("HTTP error! status: 404"))
  );
}

function mergeChatMessage(
  current: ChatMessageVO,
  incoming: ChatMessageVO,
): ChatMessageVO {
  const currentContent = current.content ?? "";
  const incomingContent = incoming.content ?? "";
  const content =
    incomingContent.length >= currentContent.length
      ? incoming.content
      : current.content;

  return {
    ...current,
    ...incoming,
    content,
    metadata: incoming.metadata ?? current.metadata,
  };
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
  const [persistentErrorText, setPersistentErrorText] = useState("");
  const [retryMessage, setRetryMessage] = useState<string | null>(null);
  const [executionMode, setExecutionMode] =
    useState<AgentExecutionMode>("REACT");
  const [activePendingTurnId, setActivePendingTurnId] = useState<string | null>(
    null,
  );

  // Use ref to avoid stale closures in the SSE listener
  const isProcessingRef = useRef(displayAgentStatus);
  useEffect(() => {
    isProcessingRef.current = displayAgentStatus;
  }, [displayAgentStatus]);

  const activePendingTurnIdRef = useRef<string | null>(activePendingTurnId);
  useEffect(() => {
    activePendingTurnIdRef.current = activePendingTurnId;
  }, [activePendingTurnId]);

  const messagesRef = useRef<ChatMessageVO[]>(messages);
  useEffect(() => {
    messagesRef.current = messages;
  }, [messages]);

  const observedTurnMessageCountRef = useRef(0);
  const safetyUnlockTimerRef = useRef<ReturnType<typeof setTimeout> | null>(
    null,
  );
  const safetyHintTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const compensationPollTimerRef = useRef<ReturnType<typeof setInterval> | null>(
    null,
  );
  const finalReconcileTimerRef = useRef<ReturnType<typeof setTimeout> | null>(
    null,
  );

  const clearSafetyTimer = () => {
    if (safetyUnlockTimerRef.current) {
      clearTimeout(safetyUnlockTimerRef.current);
      safetyUnlockTimerRef.current = null;
    }
    if (safetyHintTimerRef.current) {
      clearTimeout(safetyHintTimerRef.current);
      safetyHintTimerRef.current = null;
    }
  };

  const clearCompensationPoll = () => {
    if (compensationPollTimerRef.current) {
      clearInterval(compensationPollTimerRef.current);
      compensationPollTimerRef.current = null;
    }
  };

  const clearFinalReconcileTimer = () => {
    if (finalReconcileTimerRef.current) {
      clearTimeout(finalReconcileTimerRef.current);
      finalReconcileTimerRef.current = null;
    }
  };

  const clearPendingState = useCallback(
    (sessionId?: string) => {
      const targetSessionId = sessionId ?? chatSessionId;
      if (targetSessionId) {
        clearPendingTurnId(targetSessionId);
      }
      setActivePendingTurnId(null);
      observedTurnMessageCountRef.current = 0;
      clearSafetyTimer();
      clearCompensationPoll();
      setDisplayAgentStatus(false);
      setAgentStatusText("");
      setAgentStatusType(undefined);
    },
    [chatSessionId],
  );

  const clearPersistentError = useCallback(() => {
    setPersistentErrorText("");
    setRetryMessage(null);
  }, []);

  const resolveRetryMessage = useCallback((turnId?: string | null) => {
    const snapshot = messagesRef.current;
    const fromTurn = turnId
      ? [...snapshot]
          .reverse()
          .find((message) => message.turnId === turnId && message.role === "user")
      : undefined;
    if (fromTurn?.content?.trim()) {
      return fromTurn.content.trim();
    }
    const latestUser = [...snapshot]
      .reverse()
      .find((message) => message.role === "user" && message.content?.trim());
    return latestUser?.content?.trim() ?? null;
  }, []);

  const startSafetyTimer = useCallback(() => {
    clearSafetyTimer();
    safetyHintTimerRef.current = setTimeout(() => {
      if (!isProcessingRef.current) {
        return;
      }
      setAgentStatusText("连接不稳，正在确认状态...");
    }, PENDING_HINT_MS);
    safetyUnlockTimerRef.current = setTimeout(() => {
      console.warn("Safety unlock triggered: No SSE completion received within timeout.");
      antdMessage.error("长时间未收到稳定回复，请稍后重试。");
      clearPendingState();
    }, PENDING_TIMEOUT_MS);
  }, [clearPendingState]);

  const markPendingActivity = useCallback(() => {
    if (!isProcessingRef.current) {
      return;
    }
    startSafetyTimer();
  }, [startSafetyTimer]);

  const mergeRealtimeMessages = useCallback((realMessages: ChatMessageVO[]) => {
    setMessages((prev) => {
      const persistedUserTurnIds = new Set(
        realMessages
          .filter((message) => message.role === "user" && message.turnId)
          .map((message) => message.turnId as string),
      );
      const localRealTimeMessages = prev.filter(
        (m) => {
          if ((m.role === "assistant" || m.role === "tool") && !m.id.startsWith("temp-")) {
            return true;
          }
          if (m.id.startsWith("temp-user-")) {
            return !m.turnId || !persistedUserTurnIds.has(m.turnId);
          }
          return false;
        },
      );
      const byId = new Map<string, ChatMessageVO>(
        realMessages.map((message) => [message.id, message] as const),
      );
      for (const localMessage of localRealTimeMessages) {
        const persistedMessage = byId.get(localMessage.id);
        byId.set(
          localMessage.id,
          persistedMessage
            ? mergeChatMessage(localMessage, persistedMessage)
            : localMessage,
        );
      }

      return Array.from(byId.values()).sort(
        (a, b) => (a.seqNo || 0) - (b.seqNo || 0),
      );
    });
  }, []);

  const reconcileFinalMessages = useCallback(
    (sessionId: string) => {
      clearFinalReconcileTimer();
      let attempt = 0;
      const run = () => {
        attempt += 1;
        void getChatMessagesBySessionId(sessionId)
          .then((response) => {
            mergeRealtimeMessages(response);
          })
          .catch((error) => {
            console.warn("Final message reconciliation failed:", error);
          })
          .finally(() => {
            if (attempt < FINAL_RECONCILE_ATTEMPTS) {
              finalReconcileTimerRef.current = setTimeout(
                run,
                FINAL_RECONCILE_DELAY_MS,
              );
            }
          });
      };
      run();
    },
    [mergeRealtimeMessages],
  );

  const evaluatePendingProgress = useCallback(
    (sessionId: string, chatMessages: ChatMessageVO[]) => {
      const pendingTurnId = activePendingTurnIdRef.current;
      if (!pendingTurnId) {
        return;
      }
      const sameTurnMessages = chatMessages.filter(
        (msg) =>
          msg.turnId === pendingTurnId &&
          (msg.role === "assistant" || msg.role === "tool"),
      );
      const hasFinalAssistant = sameTurnMessages.some(
        (msg) =>
          msg.role === "assistant" &&
          (executionMode !== "DEEPTHINK" || Boolean(msg.metadata?.agentTrace)),
      );
      if (hasFinalAssistant) {
        clearPendingState(sessionId);
        reconcileFinalMessages(sessionId);
        return;
      }
      if (sameTurnMessages.length > observedTurnMessageCountRef.current) {
        observedTurnMessageCountRef.current = sameTurnMessages.length;
        markPendingActivity();
      }
    },
    [
      clearPendingState,
      executionMode,
      markPendingActivity,
      reconcileFinalMessages,
    ],
  );

  const addMessage = (message: ChatMessageVO) => {
    setMessages((prevMessages) => {
      const existingIndex = prevMessages.findIndex((m) => m.id === message.id);
      if (existingIndex === -1) {
        return [...prevMessages, message];
      }

      return prevMessages.map((existingMessage, index) =>
        index === existingIndex
          ? mergeChatMessage(existingMessage, message)
          : existingMessage,
      );
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

      mergeRealtimeMessages(messagesResp);
      evaluatePendingProgress(chatSessionId, messagesResp);
      setSessionFiles(filesResp);
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
  }, [
    chatSessionId,
    evaluatePendingProgress,
    initializing,
    isAuthenticated,
    mergeRealtimeMessages,
    navigate,
    refreshChatSessions,
  ]);

  // Clear stale messages whenever the session changes to prevent cross-session pollution
  useEffect(() => {
    setMessages([]);
    setSessionFiles([]);
    setPersistentErrorText("");
    setRetryMessage(null);
    setExecutionMode(chatSessionId ? getStoredExecutionMode(chatSessionId) : "REACT");
  }, [chatSessionId]);

  const handleExecutionModeChange = useCallback(
    (nextMode: AgentExecutionMode) => {
      if (displayAgentStatus) {
        return;
      }
      setExecutionMode(nextMode);
      if (chatSessionId) {
        setStoredExecutionMode(chatSessionId, nextMode);
      }
    },
    [chatSessionId, displayAgentStatus],
  );

  useEffect(() => {
    if (!chatSessionId || initializing) {
      return;
    }
    void getChatData().catch((error) => {
      console.error("Failed to load chat session:", error);
      antdMessage.error("Failed to load the conversation. Please try again.");
    });
  }, [chatSessionId, getChatData, initializing]);

  useEffect(() => {
    if (!chatSessionId) {
      setActivePendingTurnId(null);
      observedTurnMessageCountRef.current = 0;
      clearSafetyTimer();
      clearCompensationPoll();
      clearFinalReconcileTimer();
      return;
    }
    const pendingTurnId = getPendingTurnId(chatSessionId);
    setActivePendingTurnId(pendingTurnId);
    observedTurnMessageCountRef.current = 0;
    if (pendingTurnId) {
      setDisplayAgentStatus(true);
      setAgentStatusType("AI_THINKING");
      setAgentStatusText("Queuing...");
      startSafetyTimer();
    }
  }, [chatSessionId, startSafetyTimer]);

  useEffect(() => {
    if (!chatSessionId || !activePendingTurnId || !isAuthenticated) {
      clearCompensationPoll();
      return;
    }
    clearCompensationPoll();
    compensationPollTimerRef.current = setInterval(() => {
      void getChatMessagesBySessionId(chatSessionId)
        .then((response) => {
          mergeRealtimeMessages(response);
          evaluatePendingProgress(chatSessionId, response);
        })
        .catch((error) => {
          console.warn("Compensation poll failed:", error);
        });
    }, COMPENSATION_POLL_MS);
    return () => {
      clearCompensationPoll();
    };
  }, [
    activePendingTurnId,
    chatSessionId,
    evaluatePendingProgress,
    isAuthenticated,
    mergeRealtimeMessages,
  ]);

  const handleSendMessage = async (value: string | { text: string }) => {
    const message = typeof value === "string" ? value : value.text;

    if (!message || !message.trim()) {
      return;
    }

    const activeChatSessionId = chatSessionId;
    clearPersistentError();

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
        navigate(`/chat/${response}`, {
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

    const turnId = crypto.randomUUID();

    // Local Pending State: Instantly show the user's message in the UI
    const tempId = `temp-user-${Date.now()}`;
    const tempUserMessage: ChatMessageVO = {
      id: tempId,
      sessionId: activeChatSessionId,
      turnId,
      role: "user",
      content: message,
    };

    setMessages((prev) => [...prev, tempUserMessage]);

    // Initial Processing State
    observedTurnMessageCountRef.current = 0;
    setPendingTurnId(activeChatSessionId, turnId);
    setActivePendingTurnId(turnId);
    setDisplayAgentStatus(true);
    setAgentStatusText("Queuing...");
    setAgentStatusType("AI_THINKING");
    startSafetyTimer();

    try {
      // 1. Send message to backend
      if (state?.init) {
        const response = await createChatMessage({
          sessionId: activeChatSessionId,
          turnId,
          role: "user",
          content: state.initMessage ?? "",
          executionMode,
        });
        setPendingTurnId(activeChatSessionId, response.turnId);
        setActivePendingTurnId(response.turnId);
      } else {
        const response = await createChatMessage({
          sessionId: activeChatSessionId,
          turnId,
          role: "user",
          content: message,
          executionMode,
        });
        setPendingTurnId(activeChatSessionId, response.turnId);
        setActivePendingTurnId(response.turnId);
      }
    } catch (error) {
      console.error("Failed to send message:", error);
      antdMessage.error("Failed to send the message. Please try again.");
      clearPendingState(activeChatSessionId);
      // Rethrow to keep message in input box
      throw error;
    }

    // 2. Synchronize history separately. 
    // Failure here won't trigger message re-send UI rollback.
    try {
      await getChatData();
    } catch (error) {
      console.warn("Reconciliation fetch failed after successful send:", error);
    }
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
        setSessionFiles(filesResp);
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
      // Refresh the safety timer on any activity
      if (isProcessingRef.current) markPendingActivity();
    };

    es.onerror = (error) => {
      console.error("SSE error, attempting to reconnect...", error);
    };

    es.addEventListener("message", (event) => {
      const message = JSON.parse(event.data) as SseMessage;
      
      // Refresh timer on typed messages too
      if (isProcessingRef.current) markPendingActivity();

      if (message.type === "AI_GENERATED_CONTENT") {
        if (message.payload?.message) {
          addMessage(message.payload.message);
          if (
            activePendingTurnIdRef.current &&
            message.payload.message.turnId === activePendingTurnIdRef.current &&
            (message.payload.message.role === "assistant" ||
              message.payload.message.role === "tool")
          ) {
            observedTurnMessageCountRef.current += 1;
          }
        }
      } else if (message.type === "AI_PLANNING") {
        const pendingTurnId = activePendingTurnIdRef.current;
        if (pendingTurnId && message.payload?.turnId === pendingTurnId) {
          setDisplayAgentStatus(true);
          setAgentStatusText(message.payload?.statusText ?? "Planning...");
          setAgentStatusType("AI_PLANNING");
        }
      } else if (message.type === "AI_THINKING") {
        const pendingTurnId = activePendingTurnIdRef.current;
        if (pendingTurnId && message.payload?.turnId === pendingTurnId) {
          setDisplayAgentStatus(true);
          setAgentStatusText(message.payload?.statusText ?? "Thinking...");
          setAgentStatusType("AI_THINKING");
        }
      } else if (message.type === "AI_EXECUTING") {
        const pendingTurnId = activePendingTurnIdRef.current;
        if (pendingTurnId && message.payload?.turnId === pendingTurnId) {
          setDisplayAgentStatus(true);
          setAgentStatusText(message.payload?.statusText ?? "Executing...");
          setAgentStatusType("AI_EXECUTING");
        }
      } else if (message.type === "AI_ERROR") {
        const retryContent = resolveRetryMessage(activePendingTurnIdRef.current);
        clearPendingState(chatSessionId);
        setPersistentErrorText(message.payload?.statusText ?? "Error");
        setRetryMessage(retryContent);
      } else if (message.type === "AI_DONE") {
        const doneTurnId = message.payload?.turnId;
        const pendingTurnId = activePendingTurnIdRef.current;
        if (pendingTurnId && doneTurnId === pendingTurnId) {
          clearPendingState(chatSessionId);
          reconcileFinalMessages(chatSessionId);
        }
      } else if (message.type === "TURN_ROLLBACK") {
        const rollbackTurnId = message.payload.turnId;
        if (rollbackTurnId) {
          setMessages((prev) =>
            prev.filter((msg) => msg.turnId !== rollbackTurnId || msg.role === "user"),
          );
        }
      } else {
        console.warn(`Unknown message type: ${message.type}`);
      }
    });

    return () => {
      es.close();
      clearSafetyTimer();
      clearCompensationPoll();
      clearFinalReconcileTimer();
    };
  }, [
    chatSessionId,
    clearPendingState,
    initializing,
    isAuthenticated,
    markPendingActivity,
    reconcileFinalMessages,
    resolveRetryMessage,
  ]);

  const handleRetryLastMessage = useCallback(() => {
    if (!retryMessage) {
      return;
    }
    void handleSendMessage(retryMessage).catch((error) => {
      console.error("Retry failed:", error);
    });
  }, [retryMessage]);

  if (!chatSessionId) {
    return (
      <EmptyAgentChatView
        loading={loading}
        executionMode={executionMode}
        onExecutionModeChange={handleExecutionModeChange}
      />
    );
  }

  return (
    <div className="relative flex h-full flex-col overflow-x-hidden bg-[#212121] text-white">
      <AgentChatHistory
        messages={messages}
        displayAgentStatus={displayAgentStatus}
        agentStatusText={agentStatusText}
        agentStatusType={agentStatusType}
        persistentErrorText={persistentErrorText}
        onRetryLastMessage={retryMessage ? handleRetryLastMessage : undefined}
        onDismissError={persistentErrorText ? clearPersistentError : undefined}
      />
      <div className="bg-[#212121] px-5 pb-6 pt-2 md:px-8">
        <AgentChatInput
          onSend={handleSendMessage}
          executionMode={executionMode}
          onExecutionModeChange={handleExecutionModeChange}
          onUploadFile={handleUploadFile}
          onRemoveFile={handleRemoveFile}
          attachments={sessionFiles}
          uploading={uploadingFile}
          disabled={displayAgentStatus}
        />
      </div>
    </div>
  );
};

export default AgentChatView;
