import { get, post, patch, del, BASE_URL } from "./http.ts";
/**
 * 获取所有 agents
 */
export async function getAgents() {
    return get("/agents");
}
/**
 * 创建 agent
 */
export async function createAgent(request) {
    return post("/agents", request);
}
/**
 * 删除 agent
 */
export async function deleteAgent(agentId) {
    return del(`/agents/${agentId}`);
}
/**
 * 更新 agent
 */
export async function updateAgent(agentId, request) {
    return patch(`/agents/${agentId}`, request);
}
export async function createChatSession(request) {
    return post("/chat-sessions", request);
}
/**
 * 获取所有聊天会话
 */
export async function getChatSessions() {
    return get("/chat-sessions");
}
/**
 * 获取单个聊天会话
 */
export async function getChatSession(chatSessionId) {
    return get(`/chat-sessions/${chatSessionId}`);
}
/**
 * 根据 agentId 获取聊天会话
 */
export async function getChatSessionsByAgentId(agentId) {
    return get(`/chat-sessions/agent/${agentId}`);
}
/**
 * 更新聊天会话
 */
export async function updateChatSession(chatSessionId, request) {
    return patch(`/chat-sessions/${chatSessionId}`, request);
}
/**
 * 删除聊天会话
 */
export async function deleteChatSession(chatSessionId) {
    return del(`/chat-sessions/${chatSessionId}`);
}
/**
 * 根据 sessionId 获取聊天消息
 */
export async function getChatMessagesBySessionId(sessionId) {
    return get(`/chat-messages/session/${sessionId}`);
}
/**
 * 创建聊天消息
 */
export async function createChatMessage(request) {
    return post("/chat-messages", request);
}
/**
 * 更新聊天消息
 */
export async function updateChatMessage(chatMessageId, request) {
    return patch(`/chat-messages/${chatMessageId}`, request);
}
/**
 * 删除聊天消息
 */
export async function deleteChatMessage(chatMessageId) {
    return del(`/chat-messages/${chatMessageId}`);
}
/**
 * 获取所有知识库
 */
export async function getKnowledgeBases() {
    return get("/knowledge-bases");
}
/**
 * 创建知识库
 */
export async function createKnowledgeBase(request) {
    return post("/knowledge-bases", request);
}
/**
 * 删除知识库
 */
export async function deleteKnowledgeBase(knowledgeBaseId) {
    return del(`/knowledge-bases/${knowledgeBaseId}`);
}
/**
 * 更新知识库
 */
export async function updateKnowledgeBase(knowledgeBaseId, request) {
    return patch(`/knowledge-bases/${knowledgeBaseId}`, request);
}
/**
 * 根据知识库 ID 获取文档列表
 */
export async function getDocumentsByKbId(kbId) {
    return get(`/documents/kb/${kbId}`);
}
/**
 * 上传文档
 */
export async function uploadDocument(kbId, file) {
    const formData = new FormData();
    formData.append("kbId", kbId);
    formData.append("file", file);
    const response = await fetch(`${BASE_URL}/documents/upload`, {
        method: "POST",
        body: formData,
    });
    if (!response.ok) {
        throw new Error(`HTTP error! status: ${response.status}`);
    }
    const apiResponse = await response.json();
    if (apiResponse.code !== 200) {
        throw new Error(apiResponse.message || "上传失败");
    }
    return apiResponse.data;
}
/**
 * 删除文档
 */
export async function deleteDocument(documentId) {
    return del(`/documents/${documentId}`);
}
/**
 * 获取可选工具列表
 */
export async function getOptionalTools() {
    const tools = await get("/tools");
    return { tools };
}
