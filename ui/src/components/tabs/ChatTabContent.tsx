import React from "react";
import {
  DeleteOutlined,
  MessageOutlined,
  PlusOutlined,
} from "@ant-design/icons";
import { Button, Popconfirm, Typography } from "antd";
import { useMatch, useNavigate } from "react-router-dom";
import { useChatSessions } from "../../hooks/useChatSessions.ts";

const { Text } = Typography;

const ChatTabContent: React.FC = () => {
  const navigate = useNavigate();
  const activeChatMatch = useMatch("/chat/:chatSessionId");
  const { chatSessions, loading, deleteChatSession } = useChatSessions();

  const handleCreateNewChat = () => {
    navigate("/chat");
  };

  const handleSelectChatSession = (chatSessionId: string) => {
    navigate(`/chat/${chatSessionId}`);
  };

  const handleDeleteChatSession = async (chatSessionId: string) => {
    await deleteChatSession(chatSessionId);
    if (activeChatMatch?.params.chatSessionId === chatSessionId) {
      navigate("/chat", { replace: true });
    }
  };

  const getDisplayTitle = (session: { title?: string }) => {
    if (session.title) {
      return session.title;
    }
    return "New chat";
  };

  return (
    <div className="flex h-full flex-col">
      <button
        type="button"
        onClick={handleCreateNewChat}
        className="mb-4 flex items-center gap-3 rounded-input border border-white/12 bg-white/[0.07] px-4 py-3 text-left text-white/90 transition hover:border-white/18 hover:bg-white/[0.10]"
      >
        <PlusOutlined />
        <span className="font-medium">New chat</span>
      </button>

      <div className="scrollbar-hide flex-1 min-h-0 overflow-y-auto rounded-panel border border-white/6 bg-white/[0.025] p-3">
        {loading ? (
          <div className="flex h-full min-h-[280px] flex-col items-center justify-center text-white/42">
            <p className="text-sm">Loading chats...</p>
          </div>
        ) : chatSessions.length === 0 ? (
          <div className="flex h-full min-h-[280px] flex-col items-center justify-center rounded-section border border-dashed border-white/8 bg-black/10 px-6 text-center text-white/40">
            <MessageOutlined className="mb-4 text-4xl" />
            <p className="text-sm font-medium text-white/68">No chats yet</p>
            <p className="mt-2 text-xs leading-6 text-white/36">
              Start a new conversation to see it here.
            </p>
          </div>
        ) : (
          <div className="space-y-2">
            {chatSessions.map((session) => {
              const isActive = activeChatMatch?.params.chatSessionId === session.id;

              return (
                <div
                  key={session.id}
                  onClick={() => handleSelectChatSession(session.id)}
                  className={`group relative w-full cursor-pointer rounded-input border px-4 py-3 transition ${
                    isActive
                      ? "border-white/14 bg-white/[0.085]"
                      : "border-transparent bg-white/[0.035] hover:border-white/8 hover:bg-white/[0.06]"
                  }`}
                >
                  <div className="flex items-start gap-3">
                    <div className="mt-0.5 flex h-9 w-9 shrink-0 items-center justify-center rounded-tab bg-white/[0.08] text-base text-white/70">
                      <MessageOutlined />
                    </div>

                    <div className="min-w-0 flex-1">
                      <div className="truncate text-sm font-medium text-white">
                        {getDisplayTitle(session)}
                      </div>
                      <Text className="!mt-1 !block !text-xs !text-white/36">
                        Open chat
                      </Text>
                    </div>

                    <div
                      onClick={(event) => {
                        event.stopPropagation();
                      }}
                    >
                      <Popconfirm
                        title="Delete this chat?"
                        description="This cannot be undone."
                        onConfirm={() => handleDeleteChatSession(session.id)}
                        okText="Delete"
                        cancelText="Cancel"
                      >
                        <Button
                          type="text"
                          size="small"
                          icon={<DeleteOutlined />}
                          className="!flex !h-8 !w-8 !items-center !justify-center !rounded-full !text-white/38 opacity-0 transition-opacity hover:!bg-white/[0.06] hover:!text-white group-hover:opacity-100"
                          onClick={(event) => {
                            event.stopPropagation();
                          }}
                          danger
                        />
                      </Popconfirm>
                    </div>
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </div>
    </div>
  );
};

export default ChatTabContent;
