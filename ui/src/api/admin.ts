import { del, get, patch, post, put } from "./http.ts";
import type {
  CreateMcpServerRequest,
  CreateAdminUserRequest,
  CreateAdminUserResponse,
  CreateIntentNodeRequest,
  CreateIntentNodeResponse,
  CreateKnowledgeBaseRequest,
  CreateKnowledgeBaseResponse,
  DeleteMcpServerResponse,
  DashboardGranularity,
  DashboardMcpAlertsVO,
  DashboardOverviewVO,
  DashboardPerformanceVO,
  DashboardTrendMetric,
  DashboardTrendsVO,
  DashboardWindow,
  GetAdminUsersResponse,
  GetAssistantKnowledgeBasesResponse,
  GetAssistantTemplateResponse,
  GetAssistantTemplatesResponse,
  GetIntentTreeResponse,
  GetIntentVersionsResponse,
  GetKnowledgeBaseResponse,
  GetKnowledgeBasesResponse,
  GetKnowledgeDocumentsResponse,
  GetMcpServersResponse,
  InitializeAssistantFromTemplateRequest,
  InitializeAssistantFromTemplateResponse,
  GetOptionalToolsResponse,
  PublishIntentTreeResponse,
  ResetAdminUserPasswordResponse,
  SyncMcpToolCatalogResponse,
  TestMcpServerResponse,
  SetAssistantKnowledgeBasesRequest,
  SetIntentNodeKnowledgeBasesRequest,
  UpdateMcpServerRequest,
  UpdateAdminUserRequest,
  UpdateAdminUserStatusRequest,
  UpdateIntentNodeRequest,
  UpdateKnowledgeBaseRequest,
  UploadKnowledgeDocumentResponse,
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

export async function getKnowledgeBases(): Promise<GetKnowledgeBasesResponse> {
  return get<GetKnowledgeBasesResponse>("/admin/knowledge-bases");
}

export async function getKnowledgeBase(
  knowledgeBaseId: string,
): Promise<GetKnowledgeBaseResponse> {
  return get<GetKnowledgeBaseResponse>(
    `/admin/knowledge-bases/${knowledgeBaseId}`,
  );
}

export async function createKnowledgeBase(
  request: CreateKnowledgeBaseRequest,
): Promise<CreateKnowledgeBaseResponse> {
  return post<CreateKnowledgeBaseResponse>("/admin/knowledge-bases", request);
}

export async function updateKnowledgeBase(
  knowledgeBaseId: string,
  request: UpdateKnowledgeBaseRequest,
): Promise<void> {
  return patch<void>(`/admin/knowledge-bases/${knowledgeBaseId}`, request);
}

export async function deleteKnowledgeBase(
  knowledgeBaseId: string,
): Promise<void> {
  return del<void>(`/admin/knowledge-bases/${knowledgeBaseId}`);
}

export async function getKnowledgeDocuments(
  knowledgeBaseId: string,
): Promise<GetKnowledgeDocumentsResponse> {
  return get<GetKnowledgeDocumentsResponse>(
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

export async function getAssistantKnowledgeBases(): Promise<GetAssistantKnowledgeBasesResponse> {
  return get<GetAssistantKnowledgeBasesResponse>(
    "/admin/assistant/knowledge-bases",
  );
}

export async function setAssistantKnowledgeBases(
  request: SetAssistantKnowledgeBasesRequest,
): Promise<void> {
  return put<void>("/admin/assistant/knowledge-bases", request);
}

export async function getAssistantTemplates(): Promise<GetAssistantTemplatesResponse> {
  return get<GetAssistantTemplatesResponse>("/admin/assistant/templates");
}

export async function getAssistantTemplate(
  templateId: string,
): Promise<GetAssistantTemplateResponse> {
  return get<GetAssistantTemplateResponse>(`/admin/assistant/templates/${templateId}`);
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

export async function publishIntentTreeSnapshot(): Promise<PublishIntentTreeResponse> {
  return post<PublishIntentTreeResponse>("/admin/assistant/intent-tree/publish");
}

export async function getIntentVersions(): Promise<GetIntentVersionsResponse> {
  return get<GetIntentVersionsResponse>("/admin/assistant/intent-tree/versions");
}

export async function switchActiveIntentVersion(version: number): Promise<void> {
  return put<void>(`/admin/assistant/intent-tree/versions/${version}/activate`);
}

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

export async function getMcpServers(): Promise<GetMcpServersResponse> {
  return get<GetMcpServersResponse>("/admin/mcp-servers");
}

export async function createMcpServer(
  request: CreateMcpServerRequest,
): Promise<string> {
  return post<string>("/admin/mcp-servers", request, { silent: true });
}

export async function updateMcpServer(
  serverId: string,
  request: UpdateMcpServerRequest,
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
