import {
  AlertOutlined,
  ApiOutlined,
  BranchesOutlined,
  CheckCircleOutlined,
  DeleteOutlined,
  DisconnectOutlined,
  EditOutlined,
  LinkOutlined,
  PlusOutlined,
  ReloadOutlined,
  SafetyOutlined,
  SyncOutlined,
  ThunderboltOutlined,
} from "@ant-design/icons";
import { Modal, message } from "antd";
import { useCallback, useEffect, useMemo, useState } from "react";
import type { ReactNode } from "react";
import McpServerCreateDrawer from "../McpServerCreateDrawer.tsx";
import {
  deleteMcpServer,
  getDashboardMcpAlerts,
  getDashboardPerformance,
  getMcpServers,
  syncMcpServer,
  testMcpServer,
} from "../../../api/admin.ts";
import type {
  DashboardMcpAlertVO,
  DashboardMcpPerformanceVO,
  DashboardMcpServerMetricVO,
  DashboardPerformanceVO,
  DashboardWindow,
  DeleteMcpServerResponse,
  McpToolReferenceVO,
  McpServerVO,
} from "../../../types/admin.ts";
import AdminPageHeader from "../AdminPageHeader.tsx";

type ServerAction = "test" | "sync";

interface ServerViewModel {
  id: string;
  slug: string;
  name: string;
  endpointUrl: string;
  protocol: string;
  authType: string;
  status: string;
  description?: string | null;
  lastErrorCode?: string | null;
  lastErrorMessage?: string | null;
  unresolvedReferenceCount: number;
  consecutiveFailures: number;
  lastTestedAt?: string | null;
  lastSyncAt?: string | null;
  totalCalls: number;
  successCount: number;
  failureCount: number;
  rateLimitedCount: number;
  avgLatencyMs: number;
  qps: number;
  errorRate: number;
  circuitState: number;
}

const WINDOW_OPTIONS: DashboardWindow[] = ["24h", "7d", "30d"];

const EMPTY_MCP_PERFORMANCE: DashboardMcpPerformanceVO = {
  enabled: false,
  rolloutMode: "NONE",
  allowedAgentCount: 0,
  openAlertCount: 0,
  serverCount: 0,
  servers: [],
};

export default function McpOperationsPage() {
  const [window, setWindow] = useState<DashboardWindow>("24h");
  const [loading, setLoading] = useState(true);
  const [createOpen, setCreateOpen] = useState(false);
  const [editingServer, setEditingServer] = useState<McpServerVO | null>(null);
  const [deletePreview, setDeletePreview] = useState<{
    server: ServerViewModel;
    response: DeleteMcpServerResponse;
  } | null>(null);
  const [performance, setPerformance] = useState<DashboardPerformanceVO | null>(null);
  const [alerts, setAlerts] = useState<DashboardMcpAlertVO[]>([]);
  const [openAlertCount, setOpenAlertCount] = useState(0);
  const [servers, setServers] = useState<McpServerVO[]>([]);
  const [pendingActions, setPendingActions] = useState<Record<string, ServerAction | undefined>>({});
  const [deletingServerId, setDeletingServerId] = useState<string | null>(null);

  const loadPage = useCallback(async (selectedWindow: DashboardWindow) => {
    setLoading(true);
    try {
      const [performanceData, alertData, serverData] = await Promise.all([
        getDashboardPerformance(selectedWindow),
        getDashboardMcpAlerts(8),
        getMcpServers(),
      ]);
      setPerformance(performanceData);
      setAlerts(alertData.alerts);
      setOpenAlertCount(alertData.openAlertCount);
      setServers(serverData.servers);
    } catch (error) {
      console.error("Failed to load MCP operations page:", error);
      message.error("Unable to load MCP operations right now.");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadPage(window);
  }, [window, loadPage]);

  const mcp = performance?.mcp ?? EMPTY_MCP_PERFORMANCE;

  const serverCards = useMemo(() => mergeServerData(servers, mcp.servers), [servers, mcp.servers]);
  const summary = useMemo(() => {
    const activeCount = serverCards.filter((server) => server.status === "ACTIVE").length;
    const degradedCount = serverCards.filter((server) => server.status === "FAILED" || server.status === "STALE").length;
    const totalCalls = serverCards.reduce((sum, server) => sum + server.totalCalls, 0);
    const highestError = [...serverCards].sort((left, right) => right.errorRate - left.errorRate)[0];
    return {
      activeCount,
      degradedCount,
      totalCalls,
      hottestServer: highestError?.errorRate ? highestError : null,
    };
  }, [serverCards]);

  const runServerAction = useCallback(
    async (serverId: string, action: ServerAction) => {
      setPendingActions((current) => ({ ...current, [serverId]: action }));
      try {
        let completed = false;
        if (action === "test") {
          const result = await testMcpServer(serverId);
          if (result.success) {
            message.success(`Probe succeeded. ${result.discoveredToolCount} tool(s) discovered.`);
            completed = true;
          } else {
            message.warning(result.errorMessage || result.errorCode || "Probe completed with warnings.");
          }
        } else {
          const result = await syncMcpServer(serverId);
          if (result.success) {
            message.success(
              `Catalog synced. ${result.activeToolCount} active, ${result.createdCount} new, ${result.updatedCount} updated.`,
            );
            completed = true;
          } else {
            message.warning(result.errorMessage || result.errorCode || "Sync completed with warnings.");
          }
        }
        try {
          await loadPage(window);
        } catch (refreshError) {
          console.error(`Action ${action} succeeded but MCP page refresh failed:`, refreshError);
          if (completed) {
            message.warning("The action completed, but the page refresh did not finish cleanly.");
          } else {
            throw refreshError;
          }
        }
      } catch (error) {
        console.error(`Failed to ${action} MCP server ${serverId}:`, error);
        message.error(action === "test" ? "Probe failed." : "Catalog sync failed.");
      } finally {
        setPendingActions((current) => ({ ...current, [serverId]: undefined }));
      }
    },
    [loadPage, window],
  );

  const attemptDelete = useCallback(
    async (server: ServerViewModel, force = false) => {
      setDeletingServerId(server.id);
      try {
        const result = await deleteMcpServer(server.id, force);
        if (result.deleted) {
          const cleanedReferenceCount =
            Math.max(result.activeReferenceCount - result.unresolvedReferenceCount, 0);
          message.success(
            !force
              ? "MCP server deleted."
              : result.unresolvedReferenceCount > 0
                ? `MCP server deleted. ${cleanedReferenceCount} reference(s) cleaned automatically and ${result.unresolvedReferenceCount} unresolved reference(s) flagged.`
                : cleanedReferenceCount > 0
                  ? `MCP server deleted. ${cleanedReferenceCount} dependent reference(s) were cleaned automatically.`
                  : "MCP server deleted.",
          );
          setDeletePreview(null);
          await loadPage(window);
          return;
        }

        setDeletePreview({ server, response: result });
        message.warning(
          `Delete blocked by ${result.activeReferenceCount} active reference(s). Review and confirm force delete if intended.`,
        );
      } catch (error) {
        console.error(`Failed to delete MCP server ${server.id}:`, error);
        message.error("Unable to delete the MCP server.");
      } finally {
        setDeletingServerId(null);
      }
    },
    [loadPage, window],
  );

  if (loading && !performance) {
    return (
      <div className="flex h-full min-h-[420px] items-center justify-center">
        <LoadingSpinner label="Loading MCP operations..." />
      </div>
    );
  }

  return (
    <div className="space-y-5">
      <McpServerCreateDrawer
        open={createOpen}
        server={editingServer}
        onClose={() => {
          setCreateOpen(false);
          setEditingServer(null);
        }}
        onSaved={async () => {
          await loadPage(window);
        }}
      />

      <Modal
        open={deletePreview != null}
        onCancel={() => {
          if (!deletingServerId) {
            setDeletePreview(null);
          }
        }}
        onOk={() => {
          if (deletePreview) {
            void attemptDelete(deletePreview.server, true);
          }
        }}
        okText="Force delete"
        cancelText="Cancel"
        okButtonProps={{ danger: true, loading: deletingServerId != null }}
        cancelButtonProps={{ disabled: deletingServerId != null }}
        title={deletePreview ? `Force delete ${deletePreview.server.name}?` : "Force delete MCP server"}
        destroyOnHidden
      >
        {deletePreview ? (
          <div className="space-y-4">
            <p className="text-sm leading-6 text-white/70">
              This MCP server still has {deletePreview.response.activeReferenceCount} active reference(s).
              A force delete will remove the server and catalog rows, clean dependent intent-tree bindings
              where possible, and flag any remaining references as unresolved for later cleanup.
            </p>
            <div className="space-y-2 rounded-xl border border-white/[0.08] bg-white/[0.03] p-3">
              {deletePreview.response.references.map((reference) => (
                <ReferenceRow key={`${reference.referenceType}-${reference.referenceId}-${reference.referencePath}`} reference={reference} />
              ))}
            </div>
          </div>
        ) : null}
      </Modal>

      <AdminPageHeader
        eyebrow="Admin / MCP Operations"
        title="MCP Ops"
        description="Watch rollout state, inspect live server pressure, and run safe probe or sync actions for remote MCP servers without changing the main admin overview."
        actions={(
          <>
            <button
              type="button"
              onClick={() => {
                setEditingServer(null);
                setCreateOpen(true);
              }}
              className="inline-flex h-10 items-center gap-2 rounded-xl border border-[#78d6a3]/30 bg-[#78d6a3]/12 px-4 text-sm font-medium text-[#9be3bb] transition hover:bg-[#78d6a3]/18"
            >
              <PlusOutlined />
              <span>Add server</span>
            </button>
            <WindowPills value={window} onChange={setWindow} />
            <button
              type="button"
              onClick={() => void loadPage(window)}
              className="flex h-10 w-10 items-center justify-center rounded-xl border border-white/[0.08] bg-white/[0.04] text-white/50 transition hover:bg-white/[0.08] hover:text-white/75"
              title="Refresh MCP page"
            >
              <ReloadOutlined spin={loading} />
            </button>
          </>
        )}
      />

      {!mcp.enabled && (
        <Panel className="border border-[#ff7875]/25 bg-[linear-gradient(135deg,rgba(255,120,117,0.12),transparent)]">
          <div className="flex items-start gap-3">
            <span className="mt-0.5 rounded-xl bg-[#ff7875]/15 p-2 text-[#ff9c96]">
              <DisconnectOutlined />
            </span>
            <div>
              <h2 className="text-sm font-semibold text-white">Global MCP outbound switch is off</h2>
              <p className="mt-1 text-sm leading-6 text-white/55">
                Runtime MCP callbacks are currently blocked by the global feature flag. Probe and
                sync views still show persisted state, but agents will not call out to remote MCP
                servers until the switch is re-enabled.
              </p>
            </div>
          </div>
        </Panel>
      )}

      <div className="grid gap-4 xl:grid-cols-4">
        <SummaryCard
          title="Rollout"
          value={labelRolloutMode(mcp.rolloutMode)}
          note={`${mcp.allowedAgentCount} allowlisted agent(s)`}
          icon={<BranchesOutlined />}
          accent="#78d6a3"
        />
        <SummaryCard
          title="Server posture"
          value={`${summary.activeCount}/${Math.max(serverCards.length, mcp.serverCount)} active`}
          note={`${summary.degradedCount} degraded`}
          icon={<SafetyOutlined />}
          accent={summary.degradedCount > 0 ? "#ffb65c" : "#52c41a"}
        />
        <SummaryCard
          title="Open alerts"
          value={String(openAlertCount)}
          note={alerts[0]?.summary ?? "No active MCP admin alerts"}
          icon={<AlertOutlined />}
          accent={openAlertCount > 0 ? "#ff7875" : "#5dd39e"}
        />
        <SummaryCard
          title="Observed calls"
          value={formatCompactNumber(summary.totalCalls)}
          note={
            summary.hottestServer
              ? `${summary.hottestServer.name} at ${summary.hottestServer.errorRate.toFixed(1)}% error`
              : "No runtime traffic captured yet"
          }
          icon={<ThunderboltOutlined />}
          accent="#40a9ff"
        />
      </div>

      <div className="grid gap-5 xl:grid-cols-[minmax(0,1fr)_340px]">
        <div className="space-y-5">
          <Panel>
            <div className="flex flex-col gap-3 border-b border-white/[0.06] pb-4 md:flex-row md:items-center md:justify-between">
              <div>
                <SectionTitle
                  title="Server fleet"
                  subtitle="Merged view of persisted server catalog, runtime snapshots, and direct admin actions."
                  icon={<ApiOutlined />}
                />
              </div>
              <div className="flex flex-wrap gap-2 text-xs text-white/40">
                <InlineTag label="Window" value={window} />
                <InlineTag label="Servers" value={String(serverCards.length || mcp.serverCount)} />
                <InlineTag label="Flag" value={mcp.enabled ? "ENABLED" : "DISABLED"} />
              </div>
            </div>

            {serverCards.length === 0 ? (
              <EmptyState
                title="No MCP servers yet"
                body="The backend route is live, but no remote MCP server is registered in the admin catalog yet."
                icon={<ApiOutlined />}
              />
            ) : (
              <div className="mt-5 grid gap-4 lg:grid-cols-2">
                {serverCards.map((server) => {
                  const action = pendingActions[server.id];
                  const deleting = deletingServerId === server.id;
                  const state = statusTone(server.status);
                  return (
                    <div
                      key={server.id}
                      className="rounded-2xl border border-white/[0.06] bg-black/15 p-4 shadow-admin-card"
                    >
                      <div className="flex items-start justify-between gap-3">
                        <div className="min-w-0">
                          <div className="flex items-center gap-2">
                            <span
                              className="rounded-full px-2.5 py-0.5 text-[11px] font-semibold uppercase tracking-wider"
                              style={{ backgroundColor: `${state.color}20`, color: state.color }}
                            >
                              {server.status}
                            </span>
                            <span className="text-xs uppercase tracking-[0.22em] text-white/30">
                              {server.slug}
                            </span>
                          </div>
                          <h3 className="mt-3 truncate text-lg font-semibold text-white">
                            {server.name}
                          </h3>
                          <p className="mt-1 text-sm leading-6 text-white/45">
                            {server.description || "No description set for this MCP endpoint."}
                          </p>
                        </div>
                        <div className="flex shrink-0 gap-2">
                          <button
                            type="button"
                            onClick={() => {
                              const source = servers.find((item) => item.id === server.id);
                              if (!source) {
                                message.warning("Server details are not available for editing yet.");
                                return;
                              }
                              setEditingServer(source);
                              setCreateOpen(true);
                            }}
                            disabled={action != null}
                            className="rounded-full border border-white/[0.10] px-3 py-1.5 text-xs font-medium text-white/75 transition hover:bg-white/[0.06] disabled:cursor-not-allowed disabled:opacity-50"
                          >
                            <span className="inline-flex items-center gap-1.5">
                              <EditOutlined />
                              <span>Edit</span>
                            </span>
                          </button>
                          <button
                            type="button"
                            onClick={() => void runServerAction(server.id, "test")}
                            disabled={action != null || deleting}
                            className="rounded-full border border-white/[0.10] px-3 py-1.5 text-xs font-medium text-white/75 transition hover:bg-white/[0.06] disabled:cursor-not-allowed disabled:opacity-50"
                          >
                            {action === "test" ? "Testing..." : "Test"}
                          </button>
                          <button
                            type="button"
                            onClick={() => void runServerAction(server.id, "sync")}
                            disabled={action != null || deleting}
                            className="rounded-full border border-[#78d6a3]/30 bg-[#78d6a3]/10 px-3 py-1.5 text-xs font-medium text-[#9be3bb] transition hover:bg-[#78d6a3]/16 disabled:cursor-not-allowed disabled:opacity-50"
                          >
                            {action === "sync" ? "Syncing..." : "Sync"}
                          </button>
                          <button
                            type="button"
                            onClick={() => void attemptDelete(server)}
                            disabled={action != null || deleting}
                            className="rounded-full border border-[#ff7875]/28 bg-[#ff7875]/8 px-3 py-1.5 text-xs font-medium text-[#ffb1ad] transition hover:bg-[#ff7875]/14 disabled:cursor-not-allowed disabled:opacity-50"
                          >
                            <span className="inline-flex items-center gap-1.5">
                              <DeleteOutlined />
                              <span>{deleting ? "Deleting..." : "Delete"}</span>
                            </span>
                          </button>
                        </div>
                      </div>

                      <div className="mt-4 flex flex-wrap gap-2">
                        <InlineTag label="Protocol" value={server.protocol} />
                        <InlineTag label="Auth" value={server.authType} />
                        <InlineTag label="Circuit" value={labelCircuitState(server.circuitState)} />
                        <InlineTag label="Refs" value={String(server.unresolvedReferenceCount)} />
                      </div>

                      <div className="mt-4 grid gap-3 sm:grid-cols-2">
                        <MetricTile label="Calls" value={formatCompactNumber(server.totalCalls)} tone="#5dd39e" />
                        <MetricTile label="Error rate" value={`${server.errorRate.toFixed(1)}%`} tone="#ff8f70" />
                        <MetricTile label="Avg latency" value={formatLatency(server.avgLatencyMs)} tone="#40a9ff" />
                        <MetricTile label="QPS" value={server.qps.toFixed(2)} tone="#c7a8ff" />
                      </div>

                      <div className="mt-4 space-y-2 rounded-xl border border-white/[0.05] bg-white/[0.02] p-3">
                        <MetaRow icon={<LinkOutlined />} label="Endpoint" value={server.endpointUrl} mono />
                        <MetaRow
                          icon={<CheckCircleOutlined />}
                          label="Last probe"
                          value={formatDateTime(server.lastTestedAt)}
                        />
                        <MetaRow
                          icon={<SyncOutlined />}
                          label="Last sync"
                          value={formatDateTime(server.lastSyncAt)}
                        />
                        <MetaRow
                          icon={<AlertOutlined />}
                          label="Last error"
                          value={server.lastErrorCode || server.lastErrorMessage || "None"}
                          subtle={!(server.lastErrorCode || server.lastErrorMessage)}
                        />
                      </div>
                    </div>
                  );
                })}
              </div>
            )}
          </Panel>
        </div>

        <div className="space-y-5 xl:sticky xl:top-4 xl:self-start">
          <Panel>
            <SectionTitle
              title="Alert feed"
              subtitle="Open admin alerts backed by the persisted MCP alert table."
              icon={<AlertOutlined />}
            />
            {alerts.length === 0 ? (
              <EmptyState
                title="No open MCP alerts"
                body="Repeated failures, schema drift, and unresolved delete references will appear here."
                icon={<CheckCircleOutlined />}
                compact
              />
            ) : (
              <div className="mt-4 space-y-3">
                {alerts.map((alert) => (
                  <AlertCard key={alert.id} alert={alert} />
                ))}
              </div>
            )}
          </Panel>

          <Panel>
            <SectionTitle
              title="Guardrails"
              subtitle="What this page is watching for you."
              icon={<SafetyOutlined />}
            />
            <div className="mt-4 space-y-3">
              <GuideRow
                title="Probe before trust"
                body="Use Test after changing credentials or endpoint URLs. It exercises initialize and tools/list without touching existing dashboard widgets."
              />
              <GuideRow
                title="Sync after drift"
                body="If schema drift alerts open, Sync is the recovery path that re-enables the runtime catalog with the latest remote tool set."
              />
              <GuideRow
                title="Watch rollout gates"
                body="A healthy server can still stay invisible to agents if the global switch is off or the rollout allowlist excludes that agent."
              />
            </div>
          </Panel>
        </div>
      </div>
    </div>
  );
}

function mergeServerData(
  servers: McpServerVO[],
  metrics: DashboardMcpServerMetricVO[],
): ServerViewModel[] {
  const metricsById = new Map(metrics.map((metric) => [metric.serverId, metric]));
  const serverIds = new Set<string>([
    ...servers.map((server) => server.id),
    ...metrics.map((metric) => metric.serverId),
  ]);

  return Array.from(serverIds)
    .map((serverId) => {
      const server = servers.find((item) => item.id === serverId);
      const metric = metricsById.get(serverId);
      return {
        id: serverId,
        slug: server?.slug ?? metric?.serverSlug ?? serverId,
        name: server?.name ?? metric?.serverName ?? "Unknown MCP server",
        endpointUrl: server?.endpointUrl ?? "Not available in runtime snapshot",
        protocol: String(server?.protocol ?? "UNKNOWN"),
        authType: String(server?.authType ?? "UNKNOWN"),
        status: String(metric?.status ?? server?.status ?? "UNKNOWN"),
        description: server?.description,
        lastErrorCode: metric?.lastErrorCode ?? server?.lastErrorCode,
        lastErrorMessage: server?.lastErrorMessage,
        unresolvedReferenceCount: metric?.unresolvedReferenceCount ?? server?.unresolvedReferenceCount ?? 0,
        consecutiveFailures: server?.consecutiveFailures ?? 0,
        lastTestedAt: metric?.lastTestedAt ?? server?.lastTestedAt,
        lastSyncAt: metric?.lastSyncAt ?? server?.lastSyncAt,
        totalCalls: metric?.totalCalls ?? 0,
        successCount: metric?.successCount ?? 0,
        failureCount: metric?.failureCount ?? 0,
        rateLimitedCount: metric?.rateLimitedCount ?? 0,
        avgLatencyMs: metric?.avgLatencyMs ?? 0,
        qps: metric?.qps ?? 0,
        errorRate: metric?.errorRate ?? 0,
        circuitState: metric?.circuitState ?? 0,
      };
    })
    .sort((left, right) => {
      const statusWeight = weightStatus(left.status) - weightStatus(right.status);
      if (statusWeight !== 0) {
        return statusWeight;
      }
      return left.name.localeCompare(right.name);
    });
}

function weightStatus(status: string): number {
  switch (status) {
    case "FAILED":
      return -3;
    case "STALE":
      return -2;
    case "DISABLED":
      return -1;
    case "ACTIVE":
      return 0;
    default:
      return 1;
  }
}

function statusTone(status: string) {
  switch (status) {
    case "ACTIVE":
      return { color: "#5dd39e" };
    case "FAILED":
      return { color: "#ff7875" };
    case "STALE":
      return { color: "#ffb65c" };
    case "DISABLED":
      return { color: "#8c8c8c" };
    default:
      return { color: "#bfbfbf" };
  }
}

function labelRolloutMode(mode: string): string {
  if (mode === "AGENT_ALLOWLIST") {
    return "Agent allowlist";
  }
  if (mode === "ALL") {
    return "All agents";
  }
  if (mode === "NONE") {
    return "Disabled";
  }
  return mode;
}

function labelCircuitState(state: number): string {
  if (state >= 2) {
    return "OPEN";
  }
  if (state >= 1) {
    return "HALF_OPEN";
  }
  return "CLOSED";
}

function formatLatency(value: number): string {
  return value >= 1000 ? `${(value / 1000).toFixed(1)}s` : `${Math.round(value)}ms`;
}

function formatCompactNumber(value: number): string {
  return new Intl.NumberFormat("en-US", {
    notation: value >= 1000 ? "compact" : "standard",
    maximumFractionDigits: value >= 1000 ? 1 : 0,
  }).format(value);
}

function formatDateTime(value?: string | null): string {
  if (!value) {
    return "Not yet";
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  return date.toLocaleString("en-US", {
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
}

function tryFormatAlertDetails(detailsJson?: string | null): string | null {
  if (!detailsJson) {
    return null;
  }
  try {
    const parsed = JSON.parse(detailsJson) as Record<string, unknown>;
    const parts = Object.entries(parsed)
      .slice(0, 2)
      .map(([key, value]) => `${key}: ${Array.isArray(value) ? value.join(", ") : String(value)}`);
    return parts.join(" | ");
  } catch {
    return detailsJson;
  }
}

function WindowPills({
  value,
  onChange,
}: {
  value: DashboardWindow;
  onChange: (value: DashboardWindow) => void;
}) {
  return (
    <div className="flex rounded-xl bg-white/[0.06] p-1">
      {WINDOW_OPTIONS.map((option) => (
        <button
          key={option}
          type="button"
          onClick={() => onChange(option)}
          className={[
            "rounded-lg px-3.5 py-1.5 text-sm font-medium transition-all",
            value === option
              ? "bg-white/[0.12] text-white shadow-sm"
              : "text-white/45 hover:text-white/70",
          ].join(" ")}
        >
          {option}
        </button>
      ))}
    </div>
  );
}

function Panel({ children, className = "" }: { children: ReactNode; className?: string }) {
  return (
    <div
      className={[
        "rounded-2xl border border-white/[0.06] bg-white/[0.04] p-5 shadow-admin-card",
        className,
      ].join(" ")}
    >
      {children}
    </div>
  );
}

function SummaryCard({
  title,
  value,
  note,
  icon,
  accent,
}: {
  title: string;
  value: string;
  note: string;
  icon: ReactNode;
  accent: string;
}) {
  return (
    <Panel>
      <div className="flex items-start justify-between gap-3">
        <div>
          <p className="text-xs font-medium uppercase tracking-[0.22em] text-white/35">{title}</p>
          <p className="mt-3 text-2xl font-bold tracking-tight text-white">{value}</p>
          <p className="mt-2 text-sm leading-6 text-white/45">{note}</p>
        </div>
        <span
          className="flex h-10 w-10 items-center justify-center rounded-xl text-base"
          style={{ backgroundColor: `${accent}18`, color: accent }}
        >
          {icon}
        </span>
      </div>
    </Panel>
  );
}

function SectionTitle({
  title,
  subtitle,
  icon,
}: {
  title: string;
  subtitle: string;
  icon: ReactNode;
}) {
  return (
    <div className="flex items-start justify-between gap-3">
      <div>
        <h2 className="text-[15px] font-semibold text-white">{title}</h2>
        <p className="mt-1 text-xs text-white/40">{subtitle}</p>
      </div>
      <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-white/[0.05] text-sm text-white/40">
        {icon}
      </span>
    </div>
  );
}

function InlineTag({ label, value }: { label: string; value: string }) {
  return (
    <span className="rounded-full border border-white/[0.08] bg-white/[0.03] px-2.5 py-1">
      <span className="text-white/28">{label}</span>
      <span className="ml-1.5 text-white/65">{value}</span>
    </span>
  );
}

function MetricTile({
  label,
  value,
  tone,
}: {
  label: string;
  value: string;
  tone: string;
}) {
  return (
    <div className="rounded-xl border border-white/[0.05] bg-white/[0.03] px-3.5 py-3">
      <p className="text-[10px] font-medium uppercase tracking-[0.22em] text-white/30">{label}</p>
      <p className="mt-2 text-lg font-semibold text-white">{value}</p>
      <div className="mt-2 h-1.5 rounded-full bg-white/[0.05]">
        <div className="h-full rounded-full" style={{ width: "100%", backgroundColor: `${tone}55` }} />
      </div>
    </div>
  );
}

function MetaRow({
  icon,
  label,
  value,
  mono = false,
  subtle = false,
}: {
  icon: ReactNode;
  label: string;
  value: string;
  mono?: boolean;
  subtle?: boolean;
}) {
  return (
    <div className="flex items-start gap-2.5 text-sm">
      <span className="mt-0.5 text-white/30">{icon}</span>
      <div className="min-w-0 flex-1">
        <p className="text-[11px] uppercase tracking-[0.18em] text-white/28">{label}</p>
        <p
          className={[
            "mt-1 break-all text-white/60",
            mono ? "font-mono text-[12px]" : "",
            subtle ? "text-white/35" : "",
          ].join(" ")}
        >
          {value}
        </p>
      </div>
    </div>
  );
}

function AlertCard({ alert }: { alert: DashboardMcpAlertVO }) {
  const tone = alert.severity === "ERROR"
    ? { color: "#ff7875", background: "rgba(255,120,117,0.10)" }
    : { color: "#ffb65c", background: "rgba(255,182,92,0.10)" };
  const details = tryFormatAlertDetails(alert.detailsJson);

  return (
    <div className="rounded-xl border border-white/[0.06] bg-black/15 p-4">
      <div className="flex items-start justify-between gap-3">
        <div>
          <span
            className="rounded-full px-2.5 py-0.5 text-[11px] font-semibold uppercase tracking-wider"
            style={{ backgroundColor: tone.background, color: tone.color }}
          >
            {alert.alertType}
          </span>
          <p className="mt-3 text-sm font-medium leading-6 text-white">{alert.summary}</p>
        </div>
        <span className="text-xs text-white/30">{alert.severity}</span>
      </div>
      <div className="mt-3 space-y-1.5 text-sm text-white/45">
        <p>Server: {alert.serverSlug || alert.serverId || "Unknown"}</p>
        {alert.toolName ? <p>Tool: {alert.toolName}</p> : null}
        {details ? <p className="leading-6 text-white/35">{details}</p> : null}
      </div>
      <p className="mt-3 text-[11px] uppercase tracking-[0.16em] text-white/22">
        Updated {formatDateTime(alert.updatedAt || alert.createdAt)}
      </p>
    </div>
  );
}

function GuideRow({ title, body }: { title: string; body: string }) {
  return (
    <div className="rounded-xl border border-white/[0.05] bg-white/[0.02] px-4 py-3.5">
      <p className="text-sm font-medium text-white">{title}</p>
      <p className="mt-1.5 text-sm leading-6 text-white/48">{body}</p>
    </div>
  );
}

function ReferenceRow({ reference }: { reference: McpToolReferenceVO }) {
  return (
    <div className="rounded-lg border border-white/[0.05] bg-black/10 px-3 py-2.5">
      <div className="flex flex-wrap items-center gap-2">
        <span className="rounded-full bg-white/[0.08] px-2 py-0.5 text-[10px] font-semibold uppercase tracking-[0.18em] text-white/55">
          {reference.referenceType}
        </span>
        <span className="text-sm font-medium text-white">{reference.referenceName}</span>
      </div>
      <p className="mt-1.5 break-all font-mono text-[12px] text-white/35">{reference.referencePath}</p>
    </div>
  );
}

function EmptyState({
  title,
  body,
  icon,
  compact = false,
}: {
  title: string;
  body: string;
  icon: ReactNode;
  compact?: boolean;
}) {
  return (
    <div
      className={[
        "flex flex-col items-center justify-center rounded-2xl border border-dashed border-white/[0.08] bg-white/[0.02] text-center",
        compact ? "mt-4 px-4 py-8" : "mt-5 px-5 py-12",
      ].join(" ")}
    >
      <span className="rounded-2xl bg-white/[0.04] p-3 text-lg text-white/35">{icon}</span>
      <h3 className="mt-4 text-base font-semibold text-white">{title}</h3>
      <p className="mt-2 max-w-md text-sm leading-6 text-white/45">{body}</p>
    </div>
  );
}

function LoadingSpinner({ label }: { label: string }) {
  return (
    <div className="flex flex-col items-center gap-3">
      <div className="h-8 w-8 animate-spin rounded-full border-2 border-white/10 border-t-white/50" />
      <span className="text-sm text-white/35">{label}</span>
    </div>
  );
}
