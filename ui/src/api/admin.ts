import { del, get, patch, post, put } from "./http.ts";
import type {
  CreateAdminUserRequest,
  CreateAdminUserResponse,
  CreateIntentNodeRequest,
  CreateIntentNodeResponse,
  CreateKnowledgeBaseRequest,
  CreateKnowledgeBaseResponse,
  GetAdminUsersResponse,
  GetAssistantKnowledgeBasesResponse,
  GetAssistantTemplateResponse,
  GetAssistantTemplatesResponse,
  GetIntentTreeResponse,
  GetIntentVersionsResponse,
  GetKnowledgeBaseResponse,
  GetKnowledgeBasesResponse,
  GetKnowledgeDocumentsResponse,
  InitializeAssistantFromTemplateRequest,
  InitializeAssistantFromTemplateResponse,
  GetOptionalToolsResponse,
  PublishIntentTreeResponse,
  ResetAdminUserPasswordResponse,
  SetAssistantKnowledgeBasesRequest,
  SetIntentNodeKnowledgeBasesRequest,
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
