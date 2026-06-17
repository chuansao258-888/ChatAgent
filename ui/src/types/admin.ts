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

export interface UploadKnowledgeDocumentResponse {
  knowledgeBaseId: string;
  documentId: string;
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

export interface CreateIntentNodeResponse {
  nodeId: string;
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

export interface SetIntentNodeKnowledgeBasesRequest {
  knowledgeBaseIds: string[];
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

export type DashboardWindow = "24h" | "7d" | "30d";
export type DashboardTrendMetric =
  | "sessions"
  | "messages"
  | "activeUsers"
  | "avgLatency"
  | "quality";
export type DashboardGranularity = "hour" | "day";

export interface DashboardOverviewKpiVO {
  value: number;
  delta: number;
  deltaPct?: number | null;
}

export interface DashboardOverviewVO {
  window: DashboardWindow | string;
  compareWindow: string;
  updatedAt: number;
  kpis: {
    totalUsers: DashboardOverviewKpiVO;
    activeUsers: DashboardOverviewKpiVO;
    totalSessions: DashboardOverviewKpiVO;
    sessions24h: DashboardOverviewKpiVO;
    totalMessages: DashboardOverviewKpiVO;
    messages24h: DashboardOverviewKpiVO;
  };
}

export interface DashboardPerformanceVO {
  window: DashboardWindow | string;
  avgLatencyMs: number;
  p95LatencyMs: number;
  successRate: number;
  errorRate: number;
  noDocRate: number;
  slowRate: number;
  mcp?: DashboardMcpPerformanceVO;
}

export interface DashboardTrendPointVO {
  ts: number;
  value: number;
}

export interface DashboardTrendSeriesVO {
  name: string;
  data: DashboardTrendPointVO[];
}

export interface DashboardTrendsVO {
  metric: DashboardTrendMetric | string;
  window: DashboardWindow | string;
  granularity: DashboardGranularity | string;
  series: DashboardTrendSeriesVO[];
}

export type McpServerStatus = "ACTIVE" | "DISABLED" | "FAILED" | "STALE";
export type McpProtocol = "HTTP" | "SSE";
export type McpAuthType = "NONE" | "API_KEY" | "BEARER_TOKEN" | "OAUTH2_CLIENT";
export type McpAlertSeverity = "WARNING" | "ERROR";
export type McpAlertType = "SERVER_FAILED" | "SCHEMA_DRIFT" | "UNRESOLVED_REFERENCE";

export interface DashboardMcpServerMetricVO {
  serverId: string;
  serverSlug: string;
  serverName: string;
  status: McpServerStatus | string;
  unresolvedReferenceCount?: number | null;
  totalCalls: number;
  successCount: number;
  failureCount: number;
  rateLimitedCount: number;
  avgLatencyMs: number;
  qps: number;
  errorRate: number;
  circuitState: number;
  lastErrorCode?: string | null;
  lastTestedAt?: string | null;
  lastSyncAt?: string | null;
}

export interface DashboardMcpPerformanceVO {
  enabled: boolean;
  rolloutMode: string;
  allowedAgentCount: number;
  openAlertCount: number;
  serverCount: number;
  servers: DashboardMcpServerMetricVO[];
}

export interface DashboardMcpAlertVO {
  id: string;
  serverId?: string | null;
  serverSlug?: string | null;
  toolName?: string | null;
  alertType: McpAlertType | string;
  severity: McpAlertSeverity | string;
  summary: string;
  detailsJson?: string | null;
  createdAt?: string | null;
  updatedAt?: string | null;
}

export interface DashboardMcpAlertsVO {
  openAlertCount: number;
  alerts: DashboardMcpAlertVO[];
}

export interface McpDiscoveredToolVO {
  remoteOriginalName: string;
  exposedModelName: string;
  toolDescription?: string | null;
  schemaHash?: string | null;
}

export interface McpServerVO {
  id: string;
  slug: string;
  name: string;
  description?: string | null;
  protocol: McpProtocol | string;
  authType: McpAuthType | string;
  endpointUrl: string;
  status: McpServerStatus | string;
  consecutiveFailures?: number | null;
  lastTestedAt?: string | null;
  lastInitializedAt?: string | null;
  lastSyncAt?: string | null;
  lastErrorCode?: string | null;
  lastErrorMessage?: string | null;
  unresolvedReferenceCount?: number | null;
  createdAt?: string | null;
  updatedAt?: string | null;
}

// Phase 9-β-2: merged Create/Update into Upsert
export interface UpsertMcpServerRequest {
  slug: string;
  name: string;
  description?: string;
  protocol: McpProtocol | string;
  authType?: McpAuthType | string;
  endpointUrl: string;
  credentials?: string;
}

export type McpReferenceType =
  | "AGENT"
  | "INTENT_NODE"
  | "ASSISTANT_TEMPLATE"
  | "ASSISTANT_TEMPLATE_INTENT_NODE";

export interface McpToolReferenceVO {
  referenceType: McpReferenceType | string;
  referenceId: string;
  referenceName: string;
  referencePath: string;
}

export interface TestMcpServerResponse {
  success: boolean;
  errorCode?: string | null;
  errorMessage?: string | null;
  negotiatedProtocolVersion?: string | null;
  remoteServerName?: string | null;
  remoteServerVersion?: string | null;
  discoveredToolCount: number;
  discoveredTools: McpDiscoveredToolVO[];
  testedAt?: string | null;
  server: McpServerVO;
}

export interface SyncMcpToolCatalogResponse {
  success: boolean;
  errorCode?: string | null;
  errorMessage?: string | null;
  negotiatedProtocolVersion?: string | null;
  remoteServerName?: string | null;
  remoteServerVersion?: string | null;
  createdCount: number;
  updatedCount: number;
  staleCount: number;
  activeToolCount: number;
  activeTools: McpDiscoveredToolVO[];
  syncedAt?: string | null;
  server: McpServerVO;
}

export interface DeleteMcpServerResponse {
  deleted: boolean;
  softDeleted: boolean;
  activeReferenceCount: number;
  unresolvedReferenceCount: number;
  references: McpToolReferenceVO[];
}
