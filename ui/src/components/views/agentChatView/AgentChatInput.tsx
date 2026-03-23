import React, { useRef, useState } from "react";
import { CloseOutlined, PlusOutlined } from "@ant-design/icons";
import { Button } from "antd";
import { Sender } from "@ant-design/x";
import type { ChatSessionFileVO } from "../../../api/api.ts";

interface AgentChatInputProps {
  onSend: (message: string) => Promise<void> | void;
  onUploadFile: (file: File) => Promise<void> | void;
  onRemoveFile: (sessionFileId: string) => Promise<void> | void;
  attachments: ChatSessionFileVO[];
  uploading?: boolean;
}

const AgentChatInput: React.FC<AgentChatInputProps> = ({
  onSend,
  onUploadFile,
  onRemoveFile,
  attachments,
  uploading = false,
}) => {
  const [message, setMessage] = useState("");
  const fileInputRef = useRef<HTMLInputElement | null>(null);

  const handleSubmit = async () => {
    const trimmed = message.trim();
    if (!trimmed) {
      return;
    }
    await onSend(trimmed);
    setMessage("");
  };

  const handleSelectFile = async (
    event: React.ChangeEvent<HTMLInputElement>,
  ) => {
    const file = event.target.files?.[0];
    if (!file) {
      return;
    }
    await onUploadFile(file);
    event.target.value = "";
  };

  return (
    <div className="mx-auto max-w-3xl space-y-3">
      {attachments.length > 0 ? (
        <div className="flex flex-wrap gap-2">
          {attachments.map((file) => (
            <div
              key={file.id}
              className="inline-flex items-center gap-2 rounded-full border border-white/10 bg-white/6 px-3 py-1.5 text-sm text-slate-200"
            >
              <span className="max-w-[220px] truncate">
                {file.originalFilename || file.filename}
              </span>
              <span className="text-xs text-slate-400">{file.parseStatus}</span>
              <button
                type="button"
                onClick={() => {
                  void onRemoveFile(file.id);
                }}
                className="flex h-5 w-5 items-center justify-center rounded-full text-slate-400 transition hover:bg-white/10 hover:text-white"
                aria-label={`Remove ${file.originalFilename || file.filename}`}
              >
                <CloseOutlined />
              </button>
            </div>
          ))}
        </div>
      ) : null}

      <input
        ref={fileInputRef}
        type="file"
        className="hidden"
        onChange={(event) => {
          void handleSelectFile(event);
        }}
      />

      <Sender
        value={message}
        loading={uploading}
        placeholder="Ask anything"
        classNames={{
          root: "chat-active-sender",
          input: "chat-active-sender-input",
          suffix: "chat-active-sender-suffix",
        }}
        styles={{
          root: {
            background: "#2f2f2f",
            border: "1px solid rgba(255,255,255,0.08)",
            borderRadius: 28,
            boxShadow: "0 18px 48px rgba(0,0,0,0.14)",
          },
          content: {
            padding: "12px 14px",
            gap: 12,
            alignItems: "center",
          },
          input: {
            color: "#ffffff",
            fontSize: 16,
            lineHeight: 1.5,
          },
          prefix: {
            alignSelf: "center",
          },
          suffix: {
            alignSelf: "center",
          },
        }}
        onChange={setMessage}
        onSubmit={() => {
          void handleSubmit();
        }}
        prefix={
          <Button
            type="text"
            shape="circle"
            icon={<PlusOutlined />}
            className="!flex !h-9 !w-9 !items-center !justify-center !text-slate-300 hover:!bg-white/8 hover:!text-white"
            onClick={() => {
              fileInputRef.current?.click();
            }}
          />
        }
      />
    </div>
  );
};

export default AgentChatInput;
