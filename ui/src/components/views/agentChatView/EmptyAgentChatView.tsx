import React, { useRef, useState } from "react";
import { PlusOutlined } from "@ant-design/icons";
import { Button, Typography, message as antdMessage } from "antd";
import { Sender } from "@ant-design/x";
import { useNavigate } from "react-router-dom";
import {
  createChatMessage,
  createChatSession,
  uploadChatSessionFile,
} from "../../../api/api.ts";
import { useAuth } from "../../../hooks/useAuth.ts";
import { useChatSessions } from "../../../hooks/useChatSessions.ts";
import { clearPendingTurnId, setPendingTurnId } from "./pendingTurnStorage.ts";

const { Title, Text } = Typography;

interface DefaultAgentChatViewProps {
  loading: boolean;
}

const EmptyAgentChatView: React.FC<DefaultAgentChatViewProps> = ({
  loading,
}) => {
  const [inputValue, setInputValue] = useState("");
  const [uploadingFile, setUploadingFile] = useState(false);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const fileInputRef = useRef<HTMLInputElement | null>(null);

  // Cache the created session ID to reuse it during retries if the first message fails
  const pendingSessionIdRef = useRef<string | null>(null);

  const navigate = useNavigate();
  const { refreshChatSessions } = useChatSessions();
  const { isAuthenticated, openAuthDialog } = useAuth();

  const ensureSessionForNewChat = async (titleSeed: string) => {
    if (!isAuthenticated) {
      openAuthDialog("login");
      return null;
    }

    // P1 Fix: Reuse the session if we already created one in a previous failed attempt
    if (pendingSessionIdRef.current) {
      return { chatSessionId: pendingSessionIdRef.current };
    }

    const response = await createChatSession({
      title: titleSeed.slice(0, 20),
    });

    pendingSessionIdRef.current = response.chatSessionId;
    await refreshChatSessions();
    return {
      chatSessionId: response.chatSessionId,
    };
  };

  const handleCreateConversation = async () => {
    const trimmedMessage = inputValue.trim();
    if (!trimmedMessage || isSubmitting) {
      return;
    }

    setIsSubmitting(true);
    let targetSessionId: string | null = null;
    try {
      const createdSession = await ensureSessionForNewChat(trimmedMessage);
      if (!createdSession) {
        return;
      }
      targetSessionId = createdSession.chatSessionId;

      const turnId = crypto.randomUUID();
      setPendingTurnId(createdSession.chatSessionId, turnId);

      const response = await createChatMessage({
        sessionId: createdSession.chatSessionId,
        turnId,
        content: trimmedMessage,
        role: "user",
      });
      setPendingTurnId(createdSession.chatSessionId, response.turnId);

      setInputValue("");
      navigate(`/chat/${createdSession.chatSessionId}`);
    } catch (error) {
      if (targetSessionId) {
        clearPendingTurnId(targetSessionId);
      }
      console.error("Failed to create conversation:", error);
      antdMessage.error("Failed to start the conversation. Please try again.");
    } finally {
      setIsSubmitting(false);
    }
  };

  const handleUploadFile = async (file: File) => {
    const createdSession = await ensureSessionForNewChat(file.name);
    if (!createdSession) {
      return;
    }

    setUploadingFile(true);
    try {
      await uploadChatSessionFile(createdSession.chatSessionId, file);
      antdMessage.success(`${file.name} attached to the new chat.`);
      navigate(`/chat/${createdSession.chatSessionId}`);
    } catch (error) {
      console.error("Failed to upload file to the new chat:", error);
      antdMessage.error("Failed to upload file. Please try again.");
    } finally {
      setUploadingFile(false);
      if (fileInputRef.current) {
        fileInputRef.current.value = "";
      }
    }
  };

  return (
      <div className="flex h-full flex-col bg-[#212121] text-white">
      <div className="flex items-center justify-between px-5 py-3">
        <div className="flex items-center gap-3">
          <div className="text-[1.75rem] font-semibold tracking-tight text-white">
            ChatAgent
          </div>
        </div>
        {isAuthenticated ? null : (
          <div className="flex items-center gap-3">
            <Button
              size="large"
              className="!rounded-full !border-white/12 !bg-white !px-5 !text-slate-950 hover:!border-white hover:!bg-white"
              onClick={() => {
                openAuthDialog("login");
              }}
            >
              Log in
            </Button>
            <Button
              size="large"
              className="!rounded-full !border-white/12 !bg-white/4 !px-5 !text-white hover:!border-white/20 hover:!bg-white/8"
              onClick={() => {
                openAuthDialog("register");
              }}
            >
              Sign up
            </Button>
          </div>
        )}
      </div>

      <div className="flex flex-1 items-center justify-center px-6">
        <div className="w-full max-w-4xl -translate-y-10 md:-translate-y-14">
          <div className="mb-10 text-center">
            <Title
              level={1}
              className="!mb-0 !text-[3rem] !font-medium !tracking-tight !text-white"
            >
              {isAuthenticated ? "Get step-by-step help" : "Start with a question"}
            </Title>
          </div>

          <div className="mx-auto w-full max-w-3xl">
            <input
              ref={fileInputRef}
              type="file"
              className="hidden"
              onChange={(event) => {
                const file = event.target.files?.[0];
                if (!file) {
                  return;
                }
                void handleUploadFile(file);
              }}
            />

            <Sender
              onSubmit={() => {
                void handleCreateConversation();
              }}
              value={inputValue}
              loading={loading || uploadingFile || isSubmitting}
              disabled={isSubmitting}
              placeholder={isSubmitting ? "Starting chat..." : "Ask anything"}
              classNames={{
                root: "chat-empty-sender",
                input: "chat-empty-sender-input",
                suffix: "chat-empty-sender-suffix",
              }}
              styles={{
                root: {
                  background: "#2f2f2f",
                  border: "1px solid rgba(255,255,255,0.06)",
                  borderRadius: "var(--radius-3xl)",
                  boxShadow: "var(--shadow-chat-sender)",
                },
                content: {
                  padding: "12px 14px",
                  gap: 12,
                  alignItems: "center",
                },
                input: {
                  color: "#ffffff",
                  fontSize: 18,
                  lineHeight: 1.45,
                },
                prefix: {
                  alignSelf: "center",
                },
                suffix: {
                  alignSelf: "center",
                },
              }}
              prefix={
                <button
                  type="button"
                  className="flex h-9 w-9 items-center justify-center rounded-full text-slate-300 transition hover:bg-white/8"
                  aria-label="Add attachment"
                  onClick={() => {
                    fileInputRef.current?.click();
                  }}
                >
                  <PlusOutlined />
                </button>
              }
              onChange={(value) => {
                setInputValue(value);
              }}
            />
            <div className="mt-6 text-center text-sm text-slate-400">
              <Text className="!text-slate-400">
                {isAuthenticated
                  ? "Your first message or file will create a new chat."
                  : "You can browse first and log in when you send or upload."}
              </Text>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};

export default EmptyAgentChatView;
