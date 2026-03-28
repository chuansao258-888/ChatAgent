import { del, get, patch, post } from "./http.ts";
import type { ChatMessageVO, MessageType } from "../types";

export interface ChatOptions {
  temperature?: number;
  topP?: number;
  messageLength?: number;
}

export type ModelType = "deepseek-chat" | "glm-4.6";

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

export interface CreateChatSessionResponse {
  chatSessionId: string;
}

export async function createChatSession(
  request: CreateChatSessionRequest,
): Promise<CreateChatSessionResponse> {
  return post<CreateChatSessionResponse>("/chat-sessions", request);
}

export interface ChatSessionVO {
  id: string;
  agentId: string;
  title?: string;
}

export interface GetChatSessionsResponse {
  chatSessions: ChatSessionVO[];
}

export interface GetChatSessionResponse {
  chatSession: ChatSessionVO;
}

export interface UpdateChatSessionRequest {
  title?: string;
}

export async function getChatSessions(): Promise<GetChatSessionsResponse> {
  return get<GetChatSessionsResponse>("/chat-sessions");
}

export async function getChatSession(
  chatSessionId: string,
): Promise<GetChatSessionResponse> {
  return get<GetChatSessionResponse>(`/chat-sessions/${chatSessionId}`);
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

export interface GetChatMessagesResponse {
  chatMessages: ChatMessageVO[];
}

export interface CreateChatMessageRequest {
  sessionId: string;
  role: MessageType;
  content: string;
  metadata?: MetaData;
}

export interface CreateChatMessageResponse {
  chatMessageId: string;
}

export interface UpdateChatMessageRequest {
  content?: string;
  metadata?: MetaData;
}

export async function getChatMessagesBySessionId(
  sessionId: string,
): Promise<GetChatMessagesResponse> {
  return get<GetChatMessagesResponse>(`/chat-messages/session/${sessionId}`);
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

export interface GetChatSessionFilesResponse {
  files: ChatSessionFileVO[];
}

export interface UploadChatSessionFileResponse {
  sessionFileId: string;
  sessionId: string;
}

export async function getChatSessionFiles(
  sessionId: string,
): Promise<GetChatSessionFilesResponse> {
  return get<GetChatSessionFilesResponse>(`/chat-sessions/${sessionId}/files`);
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
