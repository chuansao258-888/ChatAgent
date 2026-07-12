export type MessageType = "user" | "assistant" | "system" | "tool";
export type AgentExecutionMode = "REACT" | "DEEPTHINK";

export interface ToolCall {
  id: string;
  type: string;
  name: string;
  arguments: string;
}

export interface ToolResponse {
  id: string;
  name: string;
  responseData: string;
}

export interface CitationMetadata {
  sourceType: "SESSION_FILE" | "KNOWLEDGE_BASE";
  sourceId?: string;
  documentId?: string;
  documentName?: string;
  sectionPath?: string;
  chunkIndex?: number;
  snippet?: string;
  score?: number | null;
  scoreType?: "reranker" | "fallback" | "retrieval" | "filtered";
  isFallback?: boolean;
}

export interface AgentTraceMetadata {
  mode?: string;
  planning?: {
    goal?: string;
    stepCount?: number;
    steps?: Array<{
      id?: string;
      title?: string;
      /** COMPLETED | PARTIAL | FAILED | SKIPPED */
      status?: string;
    }>;
  };
  execution?: {
    toolsUsed?: string[];
    totalToolCalls?: number;
    evidenceCount?: number;
    truncatedEvidenceCount?: number;
    stepSummaries?: Array<{
      stepId?: string;
      conclusion?: string;
      toolCallCount?: number;
    }>;
  };
  reflection?: {
    rounds?: number;
    /** CONTINUE | REVISED | SKIPPED */
    status?: string;
    summary?: string;
  };
  verification?: {
    passed?: boolean;
    rounds?: number;
    issueCount?: number;
    issues?: Array<{
      /** UNSUPPORTED_CLAIM | STALE_DATA | CONTRADICTION | MISSING_SOURCE | TOOL_FAILURE */
      type?: string;
      claim?: string;
    }>;
  };
}

export interface ChatMessageVOMetadata {
  toolCalls?: ToolCall[];
  toolResponse?: ToolResponse;
  citations?: CitationMetadata[];
  executionMode?: AgentExecutionMode;
  /** true = 不在默认聊天历史中展示；DeepThink 内部 trace 消息 */
  internal?: boolean;
  /** DeepThink 阶段标签："PLAN" | "EXECUTE" | "REFLECT" | "VERIFY" */
  deepThinkPhase?: string;
  /** 计划步骤 ID，如 "S1"、"S2" */
  planStepId?: string;
  /** DeepThink 运行追踪摘要，仅附加在最终回答消息上 */
  agentTrace?: AgentTraceMetadata;
}

export interface ChatMessageVO {
  id: string;
  sessionId: string;
  turnId?: string;
  role: MessageType;
  content: string;
  metadata?: ChatMessageVOMetadata;
  seqNo?: number;
}

export type SseMessageType =
  | "AI_GENERATED_CONTENT"
  | "AI_PLANNING"
  | "AI_THINKING"
  | "AI_EXECUTING"
  | "AI_ERROR"
  | "AI_DONE"
  | "TURN_ROLLBACK";

export interface SseMessagePayload {
  /** 消息快照；status-only 事件（AI_PLANNING / AI_EXECUTING）不含此字段 */
  message?: ChatMessageVO;
  /** 状态文本；content 事件不含此字段 */
  statusText?: string;
  /** 完成标志；AI_DONE 为 true */
  done?: boolean;
  turnId?: string;
}

export interface SseMessageMetadata {
  /** 消息 ID；status-only 事件不含此字段 */
  chatMessageId?: string;
}

export interface SseMessage {
  type: SseMessageType;
  payload: SseMessagePayload;
  /** status-only 事件为 null */
  metadata: SseMessageMetadata | null;
}
