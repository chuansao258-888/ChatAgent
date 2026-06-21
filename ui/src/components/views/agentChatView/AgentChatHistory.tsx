import React, { useCallback, useEffect, useRef, useState } from "react";
import XMarkdown from "@ant-design/x-markdown";
import Latex from "@ant-design/x-markdown/plugins/Latex";
import {
  CheckCircleOutlined,
  DownOutlined,
  RightOutlined,
  RobotOutlined,
  ToolOutlined,
} from "@ant-design/icons";
import type {
  CitationMetadata,
  ChatMessageVO,
  SseMessageType,
  ToolCall,
  ToolResponse,
} from "../../../types";
import CitationInlineTag from "./CitationInlineTag.tsx";
import CitationSourcePanel from "./CitationSourcePanel.tsx";
import DeepThinkTracePanel from "./DeepThinkTracePanel.tsx";

interface AgentChatHistoryProps {
  messages: ChatMessageVO[];
  displayAgentStatus?: boolean;
  agentStatusText?: string;
  agentStatusType?: SseMessageType;
  persistentErrorText?: string;
  onRetryLastMessage?: (() => void) | undefined;
  onDismissError?: (() => void) | undefined;
}

type MarkdownComponentProps = React.HTMLAttributes<HTMLElement> & {
  children?: React.ReactNode;
  className?: string;
  block?: boolean;
  streamStatus?: "loading" | "done";
  domNode: unknown;
};

const markdownConfig = {
  extensions: Latex(),
};

const flattenTextContent = (node: React.ReactNode): string => {
  if (typeof node === "string" || typeof node === "number") {
    return String(node);
  }
  if (Array.isArray(node)) {
    return node.map(flattenTextContent).join("");
  }
  if (React.isValidElement<{ children?: React.ReactNode }>(node)) {
    return flattenTextContent(node.props.children);
  }
  return "";
};

const getCodeLanguage = (className?: string): string | undefined => {
  const match = className?.match(/language-([A-Za-z0-9#+._-]+)/);
  return match?.[1];
};

const tryFormatJsonCodeBlock = (content: string): string | null => {
  const trimmed = content.trim();
  if (!trimmed || (!trimmed.startsWith("{") && !trimmed.startsWith("["))) {
    return null;
  }

  try {
    const parsed = JSON.parse(trimmed);
    return `\`\`\`json\n${JSON.stringify(parsed, null, 2)}\n\`\`\``;
  } catch {
    return null;
  }
};

const toRenderableMarkdown = (content: string): string =>
  tryFormatJsonCodeBlock(content) ?? content;

const injectCitationLinks = (
  content: string,
  citations: CitationMetadata[],
): string => {
  if (!citations.length) {
    return toRenderableMarkdown(content);
  }

  const fencedBlockPattern = /(```[\s\S]*?```)/g;
  return toRenderableMarkdown(content)
    .split(fencedBlockPattern)
    .map((segment) => {
      if (segment.startsWith("```")) {
        return segment;
      }
      return segment.replace(/\[(\d+)\]/g, (match, rawNumber: string) => {
        const index = Number(rawNumber);
        if (!Number.isInteger(index) || index < 1 || index > citations.length) {
          return match;
        }
        return `[\\[${index}\\]](#citation-${index})`;
      });
    })
    .join("");
};

const createMarkdownComponents = (
  citations: CitationMetadata[],
  onNavigateCitation?: (index: number) => void,
) => ({
  pre: ({ children }: MarkdownComponentProps) => <>{children}</>,
  code: ({ children, className, block }: MarkdownComponentProps) => {
    const codeText = flattenTextContent(children).replace(/\n$/, "");
    const language = getCodeLanguage(className);

    if (block) {
      return (
        <pre className="chat-markdown-code-block">
          <code className={language ? `language-${language}` : undefined}>
            {codeText}
          </code>
        </pre>
      );
    }

    return <code>{codeText}</code>;
  },
  a: ({
    href,
    children,
  }: React.AnchorHTMLAttributes<HTMLAnchorElement> & {
    children?: React.ReactNode;
  }) => {
    if (href?.startsWith("#citation-")) {
      const citationIndex = Number(href.replace("#citation-", ""));
      const citation = citations[citationIndex - 1];
      if (!citation) {
        return <>{children}</>;
      }
      return (
        <CitationInlineTag
          citation={citation}
          index={citationIndex}
          onNavigate={
            onNavigateCitation
              ? () => onNavigateCitation(citationIndex)
              : undefined
          }
        />
      );
    }

    return (
      <a href={href} target="_blank" rel="noreferrer">
        {children}
      </a>
    );
  },
});

const MarkdownContent: React.FC<{
  content: string;
  hasNextChunk?: boolean;
  citations?: CitationMetadata[];
  onNavigateCitation?: (index: number) => void;
}> = ({
  content,
  hasNextChunk = false,
  citations = [],
  onNavigateCitation,
}) => (
  <div className="chat-markdown">
    <XMarkdown
      components={createMarkdownComponents(citations, onNavigateCitation)}
      config={markdownConfig}
      openLinksInNewTab
      streaming={{ enableAnimation: false, hasNextChunk }}
    >
      {injectCitationLinks(content, citations)}
    </XMarkdown>
  </div>
);

const ToolCallDisplay: React.FC<{ toolCall: ToolCall }> = ({ toolCall }) => {
  let parsedArgs: Record<string, unknown> = {};

  try {
    parsedArgs = JSON.parse(toolCall.arguments) as Record<string, unknown>;
  } catch {
    parsedArgs = {};
  }

  const argCount = Object.keys(parsedArgs).length;
  const argPreview =
    argCount > 0
      ? `${Object.keys(parsedArgs).slice(0, 2).join(", ")}${argCount > 2 ? "..." : ""}`
      : `${toolCall.arguments.slice(0, 50)}${toolCall.arguments.length > 50 ? "..." : ""}`;

  return (
    <div className="inline-flex items-center gap-2 rounded-full border border-white/10 bg-white/[0.03] px-3 py-1.5 text-xs text-slate-300">
      <ToolOutlined className="text-sky-400" />
      <span className="font-mono text-sky-300">{toolCall.name}</span>
      {argPreview ? (
        <>
          <span className="text-slate-500">·</span>
          <span className="max-w-[220px] truncate text-slate-400">
            {argPreview}
          </span>
        </>
      ) : null}
    </div>
  );
};

const ToolResponseDisplay: React.FC<{ toolResponse: ToolResponse }> = ({
  toolResponse,
}) => {
  const [expanded, setExpanded] = useState(false);

  let parsedData: unknown = null;
  let isJson = false;
  let dataPreview = "";
  let renderContent = toolResponse.responseData;

  try {
    parsedData = JSON.parse(toolResponse.responseData);
    isJson = true;
    const jsonStr = JSON.stringify(parsedData);
    dataPreview = jsonStr.length > 100 ? `${jsonStr.slice(0, 100)}...` : jsonStr;
    renderContent = `\`\`\`json\n${JSON.stringify(parsedData, null, 2)}\n\`\`\``;
  } catch {
    dataPreview =
      toolResponse.responseData.length > 100
        ? `${toolResponse.responseData.slice(0, 100)}...`
        : toolResponse.responseData;
  }

  return (
    <div className="my-2 text-xs">
      <div
        className="flex cursor-pointer items-center gap-2 text-slate-400 transition-colors hover:text-slate-200"
        onClick={() => setExpanded((value) => !value)}
      >
        {expanded ? (
          <DownOutlined className="text-slate-500" />
        ) : (
          <RightOutlined className="text-slate-500" />
        )}
        <CheckCircleOutlined className="text-emerald-400" />
        <span className="font-mono text-emerald-300">{toolResponse.name}</span>
        <span className="text-slate-500">·</span>
        <span className="truncate text-slate-400">{dataPreview}</span>
      </div>
      {expanded ? (
        <div className="ml-5 mt-2 rounded-inset border border-white/8 bg-[#262626] p-3">
          <div className={`${isJson ? "" : "text-sm"} text-slate-200`}>
            <MarkdownContent content={renderContent} />
          </div>
        </div>
      ) : null}
    </div>
  );
};

const AgentChatHistory: React.FC<AgentChatHistoryProps> = ({
  messages,
  displayAgentStatus = false,
  agentStatusText = "",
  agentStatusType,
  persistentErrorText = "",
  onRetryLastMessage,
  onDismissError,
}) => {
  const scrollContainerRef = useRef<HTMLDivElement>(null);
  const [isNearBottom, setIsNearBottom] = useState(true);
  const previousLengthRef = useRef(messages.length);
  const scrollThreshold = 20;

  const checkIfNearBottom = useCallback(() => {
    const container = scrollContainerRef.current;
    if (!container) {
      return false;
    }

    const { scrollTop, clientHeight, scrollHeight } = container;
    return scrollHeight - scrollTop - clientHeight <= scrollThreshold;
  }, []);

  const scrollToBottom = useCallback(() => {
    const container = scrollContainerRef.current;
    if (!container) {
      return;
    }

    requestAnimationFrame(() => {
      if (container) {
        container.scrollTop = container.scrollHeight;
      }
    });
  }, []);

  useEffect(() => {
    const container = scrollContainerRef.current;
    if (!container) {
      return;
    }

    const update = () => {
      setIsNearBottom(checkIfNearBottom());
    };

    const timer = setTimeout(update, 0);
    container.addEventListener("scroll", update, { passive: true });

    return () => {
      clearTimeout(timer);
      container.removeEventListener("scroll", update);
    };
  }, [checkIfNearBottom]);

  useEffect(() => {
    const hasNewMessage = messages.length > previousLengthRef.current;
    previousLengthRef.current = messages.length;

    if (hasNewMessage && isNearBottom) {
      scrollToBottom();
    }
  }, [isNearBottom, messages, scrollToBottom]);

  useEffect(() => {
    if (displayAgentStatus && isNearBottom) {
      scrollToBottom();
    }
  }, [displayAgentStatus, isNearBottom, scrollToBottom]);

  const getStatusLabel = () => {
    switch (agentStatusType) {
      case "AI_PLANNING":
        return "Planning";
      case "AI_THINKING":
        return "Thinking";
      case "AI_EXECUTING":
        return "Working";
      case "AI_ERROR":
        return "Error";
      default:
        return "Processing";
    }
  };

  const navigateToCitation = useCallback(
    (messageId: string, index: number) => {
      const element = document.getElementById(
        `citation-source-${messageId}-${index}`,
      );
      if (!element) {
        return;
      }
      element.scrollIntoView({ behavior: "smooth", block: "nearest" });
    },
    [],
  );

  return (
    <div ref={scrollContainerRef} className="flex-1 overflow-y-auto">
      <div className="mx-auto flex w-full max-w-5xl flex-col gap-6 px-6 py-8 md:px-10">
        {messages
          .filter((message) => !message.metadata?.internal)
          .map((message) => (
          <div key={message.id} data-chat-message-id={message.id}>
            {message.role === "assistant" ? (
              <div className="flex justify-start">
                <div className="min-w-0 w-full max-w-3xl text-[15px] leading-7 text-[#ececec] md:text-base">
                  {message.metadata?.toolCalls &&
                  message.metadata.toolCalls.length > 0 ? (
                    <div className="mb-3 flex flex-wrap gap-2">
                      {message.metadata.toolCalls.map((toolCall) => (
                        <ToolCallDisplay key={toolCall.id} toolCall={toolCall} />
                      ))}
                    </div>
                  ) : null}
                  {message.content ? (
                    <MarkdownContent
                      content={message.content}
                      hasNextChunk
                      citations={message.metadata?.citations ?? []}
                      onNavigateCitation={(index) =>
                        navigateToCitation(message.id, index)
                      }
                    />
                  ) : null}
                  {message.metadata?.citations &&
                  message.metadata.citations.length > 0 ? (
                    <CitationSourcePanel
                      messageId={message.id}
                      citations={message.metadata.citations}
                    />
                  ) : null}
                  {message.metadata?.agentTrace ? (
                    <DeepThinkTracePanel trace={message.metadata.agentTrace} />
                  ) : null}
                </div>
              </div>
            ) : null}

            {message.role === "tool" && message.metadata?.toolResponse ? (
              <div className="flex justify-start">
                <div className="min-w-0 w-full max-w-3xl">
                  <ToolResponseDisplay toolResponse={message.metadata.toolResponse} />
                </div>
              </div>
            ) : null}

            {message.role === "user" ? (
              <div className="flex justify-end">
                <div className="max-w-[min(80%,38rem)] whitespace-pre-wrap break-words rounded-section bg-[#2f2f2f] px-5 py-3 text-[15px] leading-7 text-white shadow-chat-bubble [overflow-wrap:anywhere]">
                  {message.content}
                </div>
              </div>
            ) : null}

            {message.role === "system" ? (
              <div className="flex justify-center">
                <div className="flex items-center gap-2 rounded-full border border-white/8 bg-white/[0.04] px-3 py-1.5 text-xs text-slate-300">
                  <RobotOutlined />
                  <span>{message.content}</span>
                </div>
              </div>
            ) : null}
          </div>
        ))}

        {displayAgentStatus ? (
          <div className="flex justify-start">
            <div className="max-w-3xl rounded-inset border border-sky-400/15 bg-sky-400/[0.04] px-4 py-3 text-sm text-slate-200 shadow-chat-bubble">
              <span className="mr-2 font-semibold text-sky-300">
                {getStatusLabel()}
              </span>
              <span className="text-slate-500">·</span>
              <span className="ml-2 text-slate-300">{agentStatusText}</span>
            </div>
          </div>
        ) : null}

        {persistentErrorText ? (
          <div className="flex justify-start">
            <div className="flex max-w-3xl items-center gap-3 rounded-inset border border-rose-400/20 bg-rose-400/[0.05] px-4 py-3 text-sm text-slate-200 shadow-chat-bubble">
              <span className="font-semibold text-rose-300">Error</span>
              <span className="text-slate-500">·</span>
              <span className="flex-1 text-slate-300">{persistentErrorText}</span>
              {onRetryLastMessage ? (
                <button
                  type="button"
                  onClick={onRetryLastMessage}
                  className="rounded-full border border-rose-300/20 px-3 py-1 text-xs font-medium text-rose-200 transition hover:border-rose-200/40 hover:bg-rose-200/10"
                >
                  Retry
                </button>
              ) : null}
              {onDismissError ? (
                <button
                  type="button"
                  onClick={onDismissError}
                  className="rounded-full border border-white/10 px-3 py-1 text-xs font-medium text-slate-300 transition hover:border-white/20 hover:bg-white/8 hover:text-white"
                >
                  Dismiss
                </button>
              ) : null}
            </div>
          </div>
        ) : null}
      </div>
    </div>
  );
};

export default AgentChatHistory;
