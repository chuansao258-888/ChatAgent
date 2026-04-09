export type MessageType = "user" | "assistant" | "system" | "tool";

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
  score?: number;
  scoreType?: "reranker" | "fallback" | "retrieval" | "filtered";
  isFallback?: boolean;
}

export interface ChatMessageVOMetadata {
  toolCalls?: ToolCall[];
  toolResponse?: ToolResponse;
  citations?: CitationMetadata[];
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
  message: ChatMessageVO;
  statusText: string;
  done: boolean;
  turnId?: string;
}

export interface SseMessageMetadata {
  chatMessageId: string;
}

export interface SseMessage {
  type: SseMessageType;
  payload: SseMessagePayload;
  metadata: SseMessageMetadata;
}
