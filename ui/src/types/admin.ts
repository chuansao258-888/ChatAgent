export interface KnowledgeBaseVO {
  id: string;
  name: string;
  description?: string;
  visibility: string;
  status: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface KnowledgeDocumentVO {
  id: string;
  knowledgeBaseId: string;
  filename: string;
  originalFilename: string;
  mimeType: string;
  sizeBytes: number;
  parseStatus: string;
  deleted: boolean;
  createdAt?: string;
  updatedAt?: string;
}

export interface KnowledgeDocumentStatusSseMessage {
  type: "DOCUMENT_STATUS_UPDATED";
  payload: {
    document: KnowledgeDocumentVO;
  };
}

export interface GetKnowledgeBasesResponse {
  knowledgeBases: KnowledgeBaseVO[];
}

export interface GetKnowledgeBaseResponse {
  knowledgeBase: KnowledgeBaseVO;
}

export interface CreateKnowledgeBaseRequest {
  name: string;
  description?: string;
}

export interface CreateKnowledgeBaseResponse {
  knowledgeBaseId: string;
}

export interface UpdateKnowledgeBaseRequest {
  name?: string;
  description?: string;
}

export interface GetKnowledgeDocumentsResponse {
  documents: KnowledgeDocumentVO[];
}

export interface UploadKnowledgeDocumentResponse {
  knowledgeBaseId: string;
  documentId: string;
}

export interface GetAssistantKnowledgeBasesResponse {
  knowledgeBases: KnowledgeBaseVO[];
}

export interface SetAssistantKnowledgeBasesRequest {
  knowledgeBaseIds: string[];
}

export type IntentNodeLevel = "DOMAIN" | "CATEGORY" | "TOPIC";
export type IntentNodeStatus = "DRAFT" | "PUBLISHED";
export type IntentKind = "KB" | "TOOL" | "SYSTEM" | "CLARIFY";
export type ScopePolicy = "STRICT" | "FALLBACK_ALLOWED";
export type ToolType = "FIXED" | "OPTIONAL";

export interface ToolVO {
  name: string;
  description: string;
  type: ToolType;
}

export interface GetOptionalToolsResponse {
  tools: ToolVO[];
}

export interface IntentVersionVO {
  version: number;
  active: boolean;
}

export interface IntentNodeVO {
  id: string;
  parentId?: string | null;
  version: number;
  status: IntentNodeStatus;
  nodeLevel: IntentNodeLevel;
  name: string;
  description?: string | null;
  examples: string[];
  intentKind?: IntentKind | null;
  scopePolicy?: ScopePolicy | null;
  allowedTools: string[];
  systemPromptOverride?: string | null;
  enabled: boolean;
  sortOrder: number;
  knowledgeBaseIds: string[];
}

export interface GetIntentTreeResponse {
  activeVersion?: number | null;
  versions: IntentVersionVO[];
  nodes: IntentNodeVO[];
}

export interface GetIntentVersionsResponse {
  versions: IntentVersionVO[];
}

export interface CreateIntentNodeRequest {
  parentId?: string;
  nodeLevel: IntentNodeLevel;
  name: string;
  description?: string;
  examples?: string[];
  intentKind?: IntentKind;
  scopePolicy?: ScopePolicy;
  allowedTools?: string[];
  systemPromptOverride?: string;
  enabled?: boolean;
  sortOrder?: number;
}

export interface UpdateIntentNodeRequest {
  parentId?: string | null;
  nodeLevel?: IntentNodeLevel;
  name?: string;
  description?: string | null;
  examples?: string[];
  intentKind?: IntentKind;
  scopePolicy?: ScopePolicy;
  allowedTools?: string[];
  systemPromptOverride?: string | null;
  enabled?: boolean;
  sortOrder?: number;
}

export interface CreateIntentNodeResponse {
  nodeId: string;
}

export interface SetIntentNodeKnowledgeBasesRequest {
  knowledgeBaseIds: string[];
}

export interface PublishIntentTreeResponse {
  version: number;
}

export interface AssistantTemplateNodeVO {
  code: string;
  parentCode?: string | null;
  nodeLevel: IntentNodeLevel;
  name: string;
  description?: string | null;
  examples: string[];
  intentKind?: IntentKind | null;
  scopePolicy?: ScopePolicy | null;
  allowedTools: string[];
  systemPromptOverride?: string | null;
  bindSelectedKnowledgeBases?: boolean;
  enabled: boolean;
  sortOrder: number;
}

export interface AssistantTemplateVO {
  id: string;
  code: string;
  name: string;
  description?: string | null;
  systemPrompt: string;
  model: string;
  allowedTools: string[];
  chatOptions: {
    temperature?: number;
    topP?: number;
    messageLength?: number;
    tokenBudget?: number;
  };
  intentTree: AssistantTemplateNodeVO[];
  builtIn: boolean;
  createdAt?: string;
  updatedAt?: string;
}

export interface GetAssistantTemplatesResponse {
  templates: AssistantTemplateVO[];
}

export interface GetAssistantTemplateResponse {
  template: AssistantTemplateVO;
}

export interface InitializeAssistantFromTemplateRequest {
  knowledgeBaseIds: string[];
}

export interface InitializeAssistantFromTemplateResponse {
  templateId: string;
  activeIntentVersion: number;
}

export type AdminUserRole = "admin" | "user";
export type AdminUserStatus = "ACTIVE" | "DISABLED";

export interface AdminUserVO {
  id: string;
  username: string;
  role: AdminUserRole | string;
  avatar?: string | null;
  status: AdminUserStatus | string;
  createdAt?: string;
  updatedAt?: string;
}

export interface GetAdminUsersResponse {
  users: AdminUserVO[];
  page: number;
  size: number;
  total: number;
}

export interface CreateAdminUserRequest {
  username: string;
  role: AdminUserRole | string;
  avatar?: string;
}

export interface CreateAdminUserResponse {
  userId: string;
  username: string;
  initialPassword: string;
}

export interface UpdateAdminUserRequest {
  role?: AdminUserRole | string;
  avatar?: string;
}

export interface UpdateAdminUserStatusRequest {
  status: AdminUserStatus | string;
}

export interface ResetAdminUserPasswordResponse {
  userId: string;
  newPassword: string;
}
