import { del, get, patch, post } from "./http.ts";
import type { AgentExecutionMode, ChatMessageVO, MessageType } from "../types";

export interface ChatOptions {
  temperature?: number;
  topP?: number;
  messageLength?: number;
}

export type ModelType = string;

export interface AgentVO {
  id: string;
  name: string;
  description?: string;
  systemPrompt?: string;
  model: ModelType;
  allowedTools?: string[];
  chatOptions?: ChatOptions;
  createdAt?: string;
  updatedAt?: string;
}

export interface CreateChatSessionRequest {
  title?: string;
}

// CreateChatSessionResponse removed in Phase 9-β-1 — backend returns string directly

export interface ChatSessionVO {
  id: string;
  agentId: string;
  title?: string;
}

export interface UpdateChatSessionRequest {
  title?: string;
}

// getChatSessions now returns ChatSessionVO[] directly
export async function getChatSessions(): Promise<ChatSessionVO[]> {
  return get<ChatSessionVO[]>("/chat-sessions");
}

// getChatSession now returns ChatSessionVO directly
export async function getChatSession(
  chatSessionId: string,
): Promise<ChatSessionVO> {
  return get<ChatSessionVO>(`/chat-sessions/${chatSessionId}`);
}

// createChatSession now returns string (the session ID) directly
export async function createChatSession(
  request: CreateChatSessionRequest,
): Promise<string> {
  return post<string>("/chat-sessions", request);
}

export async function updateChatSession(
  chatSessionId: string,
  request: UpdateChatSessionRequest,
): Promise<void> {
  return patch<void>(`/chat-sessions/${chatSessionId}`, request);
}

export async function deleteChatSession(chatSessionId: string): Promise<void> {
  return del<void>(`/chat-sessions/${chatSessionId}`);
}

export interface MetaData {
  [key: string]: unknown;
}

// GetChatMessagesResponse removed in Phase 9-β-1 — backend returns ChatMessageVO[] directly

export interface CreateChatMessageRequest {
  sessionId: string;
  turnId?: string;
  role: MessageType;
  content: string;
  executionMode?: AgentExecutionMode;
  metadata?: MetaData;
}

export interface CreateChatMessageResponse {
  chatMessageId: string;
  turnId: string;
}

export interface UpdateChatMessageRequest {
  content?: string;
  metadata?: MetaData;
}

// getChatMessagesBySessionId now returns ChatMessageVO[] directly
export async function getChatMessagesBySessionId(
  sessionId: string,
): Promise<ChatMessageVO[]> {
  return get<ChatMessageVO[]>(`/chat-messages/session/${sessionId}`);
}

export async function createChatMessage(
  request: CreateChatMessageRequest,
): Promise<CreateChatMessageResponse> {
  return post<CreateChatMessageResponse>("/chat-messages", request);
}

export async function updateChatMessage(
  chatMessageId: string,
  request: UpdateChatMessageRequest,
): Promise<void> {
  return patch<void>(`/chat-messages/${chatMessageId}`, request);
}

export async function deleteChatMessage(chatMessageId: string): Promise<void> {
  return del<void>(`/chat-messages/${chatMessageId}`);
}

export interface ChatSessionFileVO {
  id: string;
  filename: string;
  originalFilename: string;
  mimeType: string;
  sizeBytes: number;
  status: string;
  parseStatus: string;
  createdAt?: string;
  updatedAt?: string;
}

// GetChatSessionFilesResponse removed in Phase 9-β-1 — backend returns ChatSessionFileVO[] directly

export interface UploadChatSessionFileResponse {
  sessionFileId: string;
  sessionId: string;
}

// getChatSessionFiles now returns ChatSessionFileVO[] directly
export async function getChatSessionFiles(
  sessionId: string,
): Promise<ChatSessionFileVO[]> {
  return get<ChatSessionFileVO[]>(`/chat-sessions/${sessionId}/files`);
}

export async function uploadChatSessionFile(
  sessionId: string,
  file: File,
): Promise<UploadChatSessionFileResponse> {
  const formData = new FormData();
  formData.append("file", file);
  return post<UploadChatSessionFileResponse>(
    `/chat-sessions/${sessionId}/files/upload`,
    formData,
  );
}

export async function detachChatSessionFile(
  sessionId: string,
  sessionFileId: string,
): Promise<void> {
  return del<void>(`/chat-sessions/${sessionId}/files/${sessionFileId}`);
}

export interface MemoryItem {
  id: string;
  type: "fact" | "preference";
  content: string;
  updatedAt: string;
}

export const getMemories = () => get<MemoryItem[]>("/memories");
export const createMemory = (type: MemoryItem["type"], content: string) => post<MemoryItem>("/memories", { type, content });
export const updateMemory = (memory: MemoryItem, content: string) => patch<{ status: string; memory?: MemoryItem }>(`/memories/${memory.id}`, { type: memory.type, content, expectedUpdatedAt: memory.updatedAt });
export const deleteMemory = (id: string) => del<boolean>(`/memories/${id}`);
