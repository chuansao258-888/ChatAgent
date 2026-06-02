import { del, get, patch, post, put } from "./http.ts";
import type {
  CreateAdminUserRequest,
  CreateAdminUserResponse,
  CreateIntentNodeRequest,
  CreateIntentNodeResponse,
  DeleteMcpServerResponse,
  DashboardGranularity,
  DashboardMcpAlertsVO,
  DashboardOverviewVO,
  DashboardPerformanceVO,
  DashboardTrendMetric,
  DashboardTrendsVO,
  DashboardWindow,
  GetAdminUsersResponse,
  GetIntentTreeResponse,
  InitializeAssistantFromTemplateRequest,
  InitializeAssistantFromTemplateResponse,
  GetOptionalToolsResponse,
  ResetAdminUserPasswordResponse,
  SyncMcpToolCatalogResponse,
  TestMcpServerResponse,
  SetAssistantKnowledgeBasesRequest,
  SetIntentNodeKnowledgeBasesRequest,
  UpdateAdminUserRequest,
  UpdateAdminUserStatusRequest,
  UpdateIntentNodeRequest,
  UploadKnowledgeDocumentResponse,
  // VO types returned directly (Phase 9-β-1 eliminated thin Response wrappers)
  KnowledgeBaseVO,
  KnowledgeDocumentVO,
  AssistantTemplateVO,
  IntentVersionVO,
  McpServerVO,
  // Upsert request types (Phase 9-β-2 merged Create/Update pairs)
  UpsertMcpServerRequest,
} from "../types/admin.ts";

export async function getAdminUsers(params?: {
  page?: number;
  size?: number;
  keyword?: string;
  status?: string;
}): Promise<GetAdminUsersResponse> {
  return get<GetAdminUsersResponse>("/admin/users", params);
}

export async function createAdminUser(
  request: CreateAdminUserRequest,
): Promise<CreateAdminUserResponse> {
  return post<CreateAdminUserResponse>("/admin/users", request, { silent: true });
}

export async function updateAdminUser(
  userId: string,
  request: UpdateAdminUserRequest,
): Promise<void> {
  return put<void>(`/admin/users/${userId}`, request);
}

export async function updateAdminUserStatus(
  userId: string,
  request: UpdateAdminUserStatusRequest,
): Promise<void> {
  return put<void>(`/admin/users/${userId}/status`, request);
}

export async function resetAdminUserPassword(
  userId: string,
): Promise<ResetAdminUserPasswordResponse> {
  return put<ResetAdminUserPasswordResponse>(`/admin/users/${userId}/password/reset`);
}

export async function deleteAdminUser(userId: string): Promise<void> {
  return del<void>(`/admin/users/${userId}`);
}

// --- KnowledgeBase (was wrapped, now returns array / single VO / string) ---

export async function getKnowledgeBases(): Promise<KnowledgeBaseVO[]> {
  return get<KnowledgeBaseVO[]>("/admin/knowledge-bases");
}

export async function getKnowledgeBase(
  knowledgeBaseId: string,
): Promise<KnowledgeBaseVO> {
  return get<KnowledgeBaseVO>(`/admin/knowledge-bases/${knowledgeBaseId}`);
}

export async function createKnowledgeBase(
  request: { name: string; description?: string },
): Promise<string> {
  return post<string>("/admin/knowledge-bases", request);
}

export async function updateKnowledgeBase(
  knowledgeBaseId: string,
  request: { name?: string; description?: string },
): Promise<void> {
  return patch<void>(`/admin/knowledge-bases/${knowledgeBaseId}`, request);
}

export async function deleteKnowledgeBase(
  knowledgeBaseId: string,
): Promise<void> {
  return del<void>(`/admin/knowledge-bases/${knowledgeBaseId}`);
}

// --- KnowledgeDocument (was wrapped, now returns array) ---

export async function getKnowledgeDocuments(
  knowledgeBaseId: string,
): Promise<KnowledgeDocumentVO[]> {
  return get<KnowledgeDocumentVO[]>(
    `/admin/knowledge-bases/${knowledgeBaseId}/documents`,
  );
}

export async function uploadKnowledgeDocument(
  knowledgeBaseId: string,
  file: File,
): Promise<UploadKnowledgeDocumentResponse> {
  const formData = new FormData();
  formData.append("file", file);
  return post<UploadKnowledgeDocumentResponse>(
    `/admin/knowledge-bases/${knowledgeBaseId}/documents/upload`,
    formData,
  );
}

export async function replaceKnowledgeDocument(
  knowledgeBaseId: string,
  documentId: string,
  file: File,
): Promise<UploadKnowledgeDocumentResponse> {
  const formData = new FormData();
  formData.append("file", file);
  return post<UploadKnowledgeDocumentResponse>(
    `/admin/knowledge-bases/${knowledgeBaseId}/documents/${documentId}/replace`,
    formData,
  );
}

export async function deleteKnowledgeDocument(
  knowledgeBaseId: string,
  documentId: string,
): Promise<void> {
  return del<void>(
    `/admin/knowledge-bases/${knowledgeBaseId}/documents/${documentId}`,
  );
}

// --- AssistantKnowledgeBase (was wrapped, now returns array) ---

export async function getAssistantKnowledgeBases(): Promise<KnowledgeBaseVO[]> {
  return get<KnowledgeBaseVO[]>("/admin/assistant/knowledge-bases");
}

export async function setAssistantKnowledgeBases(
  request: SetAssistantKnowledgeBasesRequest,
): Promise<void> {
  return put<void>("/admin/assistant/knowledge-bases", request);
}

// --- AssistantTemplate (was wrapped, now returns array / single VO / string) ---

export async function getAssistantTemplates(): Promise<AssistantTemplateVO[]> {
  return get<AssistantTemplateVO[]>("/admin/assistant/templates");
}

export async function getAssistantTemplate(
  templateId: string,
): Promise<AssistantTemplateVO> {
  return get<AssistantTemplateVO>(`/admin/assistant/templates/${templateId}`);
}

export async function createAssistantTemplate(
  request: {
    code: string;
    name: string;
    description?: string;
    systemPrompt: string;
    model: string;
    allowedTools?: string[];
    chatOptions?: Record<string, unknown>;
    intentTree: unknown[];
  },
): Promise<string> {
  return post<string>("/admin/assistant/templates", request);
}

export async function updateAssistantTemplate(
  templateId: string,
  request: {
    code?: string;
    name?: string;
    description?: string;
    systemPrompt?: string;
    model?: string;
    allowedTools?: string[];
    chatOptions?: Record<string, unknown>;
    intentTree?: unknown[];
  },
): Promise<void> {
  return patch<void>(`/admin/assistant/templates/${templateId}`, request);
}

export async function deleteAssistantTemplate(
  templateId: string,
): Promise<void> {
  return del<void>(`/admin/assistant/templates/${templateId}`);
}

export async function initializeAssistantFromTemplate(
  templateId: string,
  request: InitializeAssistantFromTemplateRequest,
): Promise<InitializeAssistantFromTemplateResponse> {
  return post<InitializeAssistantFromTemplateResponse>(
    `/admin/assistant/templates/${templateId}/initialize`,
    request,
  );
}

export async function getOptionalTools(): Promise<GetOptionalToolsResponse> {
  const tools = await get<GetOptionalToolsResponse["tools"]>("/tools");
  return { tools };
}

// --- IntentTree ---

export async function getIntentTree(): Promise<GetIntentTreeResponse> {
  return get<GetIntentTreeResponse>("/admin/assistant/intent-tree");
}

export async function createIntentNode(
  request: CreateIntentNodeRequest,
): Promise<CreateIntentNodeResponse> {
  return post<CreateIntentNodeResponse>("/admin/assistant/intent-tree/nodes", request);
}

export async function updateIntentNode(
  nodeId: string,
  request: UpdateIntentNodeRequest,
): Promise<void> {
  return patch<void>(`/admin/assistant/intent-tree/nodes/${nodeId}`, request);
}

export async function deleteIntentNode(nodeId: string): Promise<void> {
  return del<void>(`/admin/assistant/intent-tree/nodes/${nodeId}`);
}

export async function setIntentNodeKnowledgeBases(
  nodeId: string,
  request: SetIntentNodeKnowledgeBasesRequest,
): Promise<void> {
  return put<void>(
    `/admin/assistant/intent-tree/nodes/${nodeId}/knowledge-bases`,
    request,
  );
}

// publishIntentTreeSnapshot now returns Integer directly
export async function publishIntentTreeSnapshot(): Promise<number> {
  return post<number>("/admin/assistant/intent-tree/publish");
}

// getIntentVersions now returns List<IntentVersionVO> directly
export async function getIntentVersions(): Promise<IntentVersionVO[]> {
  return get<IntentVersionVO[]>("/admin/assistant/intent-tree/versions");
}

export async function switchActiveIntentVersion(version: number): Promise<void> {
  return put<void>(`/admin/assistant/intent-tree/versions/${version}/activate`);
}

// --- Dashboard (unchanged) ---

export async function getDashboardOverview(
  window: DashboardWindow = "24h",
): Promise<DashboardOverviewVO> {
  return get<DashboardOverviewVO>("/admin/dashboard/overview", { window });
}

export async function getDashboardPerformance(
  window: DashboardWindow = "24h",
): Promise<DashboardPerformanceVO> {
  return get<DashboardPerformanceVO>("/admin/dashboard/performance", { window });
}

export async function getDashboardTrends(params: {
  metric: DashboardTrendMetric;
  window?: DashboardWindow;
  granularity?: DashboardGranularity;
}): Promise<DashboardTrendsVO> {
  return get<DashboardTrendsVO>("/admin/dashboard/trends", params);
}

export async function getDashboardMcpAlerts(
  limit = 8,
): Promise<DashboardMcpAlertsVO> {
  return get<DashboardMcpAlertsVO>("/admin/dashboard/mcp-alerts", { limit });
}

// --- MCP Servers (getMcpServers was wrapped, now returns array) ---

export async function getMcpServers(): Promise<McpServerVO[]> {
  return get<McpServerVO[]>("/admin/mcp-servers");
}

export async function createMcpServer(
  request: UpsertMcpServerRequest,
): Promise<string> {
  return post<string>("/admin/mcp-servers", request, { silent: true });
}

export async function updateMcpServer(
  serverId: string,
  request: UpsertMcpServerRequest,
): Promise<void> {
  return patch<void>(`/admin/mcp-servers/${serverId}`, request, { silent: true });
}

export async function deleteMcpServer(
  serverId: string,
  force = false,
): Promise<DeleteMcpServerResponse> {
  return del<DeleteMcpServerResponse>(`/admin/mcp-servers/${serverId}`, { force });
}

export async function testMcpServer(
  serverId: string,
): Promise<TestMcpServerResponse> {
  return post<TestMcpServerResponse>(`/admin/mcp-servers/${serverId}/test`);
}

export async function syncMcpServer(
  serverId: string,
): Promise<SyncMcpToolCatalogResponse> {
  return post<SyncMcpToolCatalogResponse>(`/admin/mcp-servers/${serverId}/sync`);
}
