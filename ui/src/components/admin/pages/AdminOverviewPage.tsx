import {
  AlertOutlined,
  DatabaseOutlined,
  LineChartOutlined,
  MessageOutlined,
  ReloadOutlined,
  RiseOutlined,
  TeamOutlined,
  ThunderboltOutlined,
  UserOutlined,
} from "@ant-design/icons";
import { message } from "antd";
import { useCallback, useEffect, useState } from "react";
import type { ReactNode } from "react";
import {
  Area,
  AreaChart,
  CartesianGrid,
  Legend,
  Line,
  LineChart,
  ReferenceLine,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import {
  getDashboardOverview,
  getDashboardPerformance,
  getDashboardTrends,
} from "../../../api/admin.ts";
import type {
  DashboardGranularity,
  DashboardOverviewKpiVO,
  DashboardOverviewVO,
  DashboardPerformanceVO,
  DashboardTrendsVO,
  DashboardWindow,
} from "../../../types/admin.ts";
import AdminPageHeader from "../AdminPageHeader.tsx";

/* ─── types ─── */

type TrendBundle = Record<
  "sessions" | "messages" | "activeUsers" | "avgLatency" | "quality",
  DashboardTrendsVO
>;

type InsightTone = "good" | "warn" | "risk";

interface InsightItem {
  title: string;
  body: string;
  tone: InsightTone;
}

interface ChartRow {
  ts: number;
  [key: string]: string | number;
}

/* ─── constants ─── */

const WINDOW_OPTIONS: DashboardWindow[] = ["24h", "7d", "30d"];

const KPI_DEFS: Array<{
  key: keyof DashboardOverviewVO["kpis"];
  title: string;
  icon: ReactNode;
  accent: string;
  accentBg: string;
}> = [
  { key: "activeUsers", title: "Active Users", icon: <TeamOutlined />, accent: "#52c41a", accentBg: "rgba(82,196,26,0.12)" },
  { key: "sessions24h", title: "Sessions", icon: <RiseOutlined />, accent: "#faad14", accentBg: "rgba(250,173,20,0.12)" },
  { key: "messages24h", title: "Messages", icon: <ThunderboltOutlined />, accent: "#ff7a45", accentBg: "rgba(255,122,69,0.12)" },
  { key: "totalUsers", title: "Total Users", icon: <UserOutlined />, accent: "#40a9ff", accentBg: "rgba(64,169,255,0.12)" },
  { key: "totalSessions", title: "Total Sessions", icon: <DatabaseOutlined />, accent: "#36cfc9", accentBg: "rgba(54,207,201,0.12)" },
  { key: "totalMessages", title: "Total Messages", icon: <MessageOutlined />, accent: "#b37feb", accentBg: "rgba(179,127,235,0.12)" },
];


/* ─── main component ─── */

export default function AdminOverviewPage() {
  const [window, setWindow] = useState<DashboardWindow>("24h");
  const [loading, setLoading] = useState(true);
  const [overview, setOverview] = useState<DashboardOverviewVO | null>(null);
  const [performance, setPerformance] = useState<DashboardPerformanceVO | null>(null);
  const [trends, setTrends] = useState<TrendBundle | null>(null);

  const loadDashboard = useCallback(async (selectedWindow: DashboardWindow) => {
    setLoading(true);
    try {
      const [ov, pf, s, m, a, l, q] = await Promise.all([
        getDashboardOverview(selectedWindow),
        getDashboardPerformance(selectedWindow),
        getDashboardTrends({ metric: "sessions", window: selectedWindow }),
        getDashboardTrends({ metric: "messages", window: selectedWindow }),
        getDashboardTrends({ metric: "activeUsers", window: selectedWindow }),
        getDashboardTrends({ metric: "avgLatency", window: selectedWindow }),
        getDashboardTrends({ metric: "quality", window: selectedWindow }),
      ]);
      setOverview(ov);
      setPerformance(pf);
      setTrends({ sessions: s, messages: m, activeUsers: a, avgLatency: l, quality: q });
    } catch (err) {
      console.error("Failed to load dashboard:", err);
      message.error("Unable to load dashboard metrics right now.");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadDashboard(window);
  }, [window, loadDashboard]);

  if (loading || !overview || !performance || !trends) {
    return (
      <div className="flex h-full min-h-[420px] items-center justify-center">
        <LoadingSpinner />
      </div>
    );
  }

  const health = resolveHealth(performance.errorRate);
  const workloadData = mergeTrendSeries(trends.sessions, trends.messages);
  const activeUsersData = mergeTrendSeries(trends.activeUsers);
  const latencyData = mergeTrendSeries(trends.avgLatency);
  const qualityData = mergeTrendSeries(trends.quality);
  const insights = buildInsights(window, overview, performance);

  return (
    <div className="space-y-5">
      {/* ── Header ── */}
      <AdminPageHeader
        eyebrow="Admin / Dashboard"
        title="Dashboard"
        description="Monitor platform activity, delivery health, latency pressure, and knowledge performance from one admin overview."
        meta={(
          <div className="flex items-center gap-2 text-sm text-white/40">
            <span
              className="inline-block h-2 w-2 rounded-full"
              style={{ backgroundColor: health.dotColor }}
            />
            Updated {formatDateTime(overview.updatedAt)}
          </div>
        )}
        actions={(
          <>
            <WindowPills value={window} onChange={setWindow} />
            <button
              type="button"
              onClick={() => void loadDashboard(window)}
              className="flex h-9 w-9 items-center justify-center rounded-lg border border-white/[0.08] bg-white/[0.04] text-white/50 transition-colors hover:bg-white/[0.08] hover:text-white/70"
              title="Refresh"
            >
              <ReloadOutlined spin={loading} />
            </button>
          </>
        )}
      />

      {/* ── Health Status ── */}
      <DashCard className={`border ${health.border} ${health.background}`}>
        <div className="flex flex-col gap-4 md:flex-row md:items-center md:justify-between">
          <div className="flex items-center gap-3.5">
            <span
              className="flex h-9 w-9 items-center justify-center rounded-xl text-base"
              style={{ backgroundColor: `${health.color}18`, color: health.color }}
            >
              <AlertOutlined />
            </span>
            <div>
              <div className="flex items-center gap-2.5">
                <span
                  className="rounded-full px-2.5 py-0.5 text-xs font-semibold"
                  style={{ backgroundColor: `${health.color}20`, color: health.color }}
                >
                  {health.label}
                </span>
                <span className="text-sm text-white/50">
                  Error rate {formatPercent(performance.errorRate)} in {labelForWindow(window)}
                </span>
              </div>
              <p className="mt-1.5 text-sm leading-relaxed text-white/60">
                {health.description}
              </p>
            </div>
          </div>
          <div className="flex gap-3">
            <MetricChip label="Avg" value={formatLatency(performance.avgLatencyMs)} />
            <MetricChip label="P95" value={formatLatency(performance.p95LatencyMs)} />
            <MetricChip label="KB Miss" value={formatPercent(performance.noDocRate)} />
          </div>
        </div>
      </DashCard>

      {/* ── KPI Grid ── */}
      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
        {KPI_DEFS.map((def) => (
          <KpiCard key={def.key} def={def} metric={overview.kpis[def.key]} />
        ))}
      </div>

      {/* ── Main content grid ── */}
      <div className="grid gap-5 xl:grid-cols-[1fr_320px]">
        {/* Left — charts */}
        <div className="min-w-0 space-y-5">
          {/* Workload overview */}
          <DashCard>
            <ChartHeader
              title="Workload"
              subtitle="Sessions and message volume"
              icon={<LineChartOutlined />}
            />
            <ChartContainer data={workloadData}>
              <ResponsiveContainer width="100%" height={280}>
                <AreaChart data={workloadData}>
                  <defs>
                    <linearGradient id="gSessions" x1="0" y1="0" x2="0" y2="1">
                      <stop offset="5%" stopColor="#73d13d" stopOpacity={0.35} />
                      <stop offset="95%" stopColor="#73d13d" stopOpacity={0.02} />
                    </linearGradient>
                    <linearGradient id="gMessages" x1="0" y1="0" x2="0" y2="1">
                      <stop offset="5%" stopColor="#36cfc9" stopOpacity={0.25} />
                      <stop offset="95%" stopColor="#36cfc9" stopOpacity={0.02} />
                    </linearGradient>
                  </defs>
                  <CartesianGrid stroke="rgba(255,255,255,0.05)" vertical={false} />
                  <XAxis
                    dataKey="ts"
                    tickFormatter={(v) => formatAxisLabel(v as number, trends.sessions.granularity)}
                    tick={AXIS_TICK}
                    axisLine={false}
                    tickLine={false}
                  />
                  <YAxis tick={AXIS_TICK} axisLine={false} tickLine={false} />
                  <Tooltip content={renderTooltip()} />
                  <Legend wrapperStyle={LEGEND_STYLE} />
                  <Area type="monotone" dataKey="Sessions" stroke="#73d13d" fill="url(#gSessions)" strokeWidth={2} />
                  <Area type="monotone" dataKey="Messages" stroke="#36cfc9" fill="url(#gMessages)" strokeWidth={2} />
                </AreaChart>
              </ResponsiveContainer>
            </ChartContainer>
          </DashCard>

          {/* 2-col trend grid */}
          <div className="grid gap-5 lg:grid-cols-2">
            {/* Active users */}
            <DashCard>
              <ChartHeader
                title="Active Users"
                subtitle="Distinct users in conversations"
                icon={<TeamOutlined />}
              />
              <ChartContainer data={activeUsersData}>
                <ResponsiveContainer width="100%" height={240}>
                  <AreaChart data={activeUsersData}>
                    <defs>
                      <linearGradient id="gActive" x1="0" y1="0" x2="0" y2="1">
                        <stop offset="5%" stopColor="#40a9ff" stopOpacity={0.3} />
                        <stop offset="95%" stopColor="#40a9ff" stopOpacity={0.02} />
                      </linearGradient>
                    </defs>
                    <CartesianGrid stroke="rgba(255,255,255,0.05)" vertical={false} />
                    <XAxis
                      dataKey="ts"
                      tickFormatter={(v) => formatAxisLabel(v as number, trends.activeUsers.granularity)}
                      tick={AXIS_TICK}
                      axisLine={false}
                      tickLine={false}
                    />
                    <YAxis tick={AXIS_TICK} axisLine={false} tickLine={false} />
                    <Tooltip content={renderTooltip()} />
                    <Area type="monotone" dataKey="Active users" stroke="#40a9ff" fill="url(#gActive)" strokeWidth={2} />
                  </AreaChart>
                </ResponsiveContainer>
              </ChartContainer>
            </DashCard>

            {/* Quality */}
            <DashCard>
              <ChartHeader
                title="Quality"
                subtitle="Error pressure and knowledge miss"
                icon={<AlertOutlined />}
              />
              <ChartContainer data={qualityData}>
                <ResponsiveContainer width="100%" height={240}>
                  <LineChart data={qualityData}>
                    <CartesianGrid stroke="rgba(255,255,255,0.05)" vertical={false} />
                    <XAxis
                      dataKey="ts"
                      tickFormatter={(v) => formatAxisLabel(v as number, trends.quality.granularity)}
                      tick={AXIS_TICK}
                      axisLine={false}
                      tickLine={false}
                    />
                    <YAxis unit="%" tick={AXIS_TICK} axisLine={false} tickLine={false} />
                    <Tooltip content={renderTooltip({ unit: "%" })} />
                    <Legend wrapperStyle={LEGEND_STYLE} />
                    <Line type="monotone" dataKey="Error rate" stroke="#ff4d4f" strokeWidth={2} dot={false} />
                    <Line type="monotone" dataKey="Knowledge miss rate" stroke="#faad14" strokeWidth={2} dot={false} />
                  </LineChart>
                </ResponsiveContainer>
              </ChartContainer>
            </DashCard>
          </div>

          {/* Latency */}
          <DashCard>
            <ChartHeader
              title="Response Time"
              subtitle="Mean and tail latency with guardrails at 2 s and 5 s"
              icon={<ThunderboltOutlined />}
            />
            <ChartContainer data={latencyData}>
              <ResponsiveContainer width="100%" height={300}>
                <LineChart data={latencyData}>
                  <CartesianGrid stroke="rgba(255,255,255,0.05)" vertical={false} />
                  <XAxis
                    dataKey="ts"
                    tickFormatter={(v) => formatAxisLabel(v as number, trends.avgLatency.granularity)}
                    tick={AXIS_TICK}
                    axisLine={false}
                    tickLine={false}
                  />
                  <YAxis
                    tickFormatter={(v) => `${Math.round((v as number) / 1000)}s`}
                    tick={AXIS_TICK}
                    axisLine={false}
                    tickLine={false}
                  />
                  <Tooltip content={renderTooltip({ formatter: formatLatency })} />
                  <Legend wrapperStyle={LEGEND_STYLE} />
                  <ReferenceLine y={2000} stroke="rgba(255,255,255,0.18)" strokeDasharray="4 4" label={{ value: "2 s", fill: "rgba(255,255,255,0.35)", fontSize: 11 }} />
                  <ReferenceLine y={5000} stroke="rgba(255,122,69,0.45)" strokeDasharray="4 4" label={{ value: "5 s", fill: "rgba(255,122,69,0.65)", fontSize: 11 }} />
                  <Line type="monotone" dataKey="Avg latency" stroke="#f6bd16" strokeWidth={2} dot={false} />
                  <Line type="monotone" dataKey="P95 latency" stroke="#ff7a45" strokeWidth={2} dot={false} />
                </LineChart>
              </ResponsiveContainer>
            </ChartContainer>
          </DashCard>
        </div>

        {/* Right — sidebar */}
        <div className="space-y-5 xl:sticky xl:top-4 xl:self-start">
          {/* AI Performance */}
          <DashCard>
            <ChartHeader
              title="AI Performance"
              subtitle="Success rate for the selected window"
              icon={<LineChartOutlined />}
            />
            <div className="mt-5 flex justify-center">
              <SvgRing percent={performance.successRate} size={148} strokeWidth={10} />
            </div>

            {/* Metric rows */}
            <div className="mt-5 space-y-2.5">
              <MetricRow label="Error rate" value={formatPercent(performance.errorRate)} color="#ff4d4f" />
              <MetricRow label="Knowledge miss" value={formatPercent(performance.noDocRate)} color="#faad14" />
              <MetricRow label="Slow turns (>20 s)" value={formatPercent(performance.slowRate)} color="#ff7a45" />
              <MetricRow label="Avg latency" value={formatLatency(performance.avgLatencyMs)} color="#f6bd16" />
              <MetricRow label="P95 latency" value={formatLatency(performance.p95LatencyMs)} color="#ff7a45" />
            </div>

            {/* Quality snapshot bars */}
            <div className="mt-5 border-t border-white/[0.06] pt-4">
              <p className="mb-3 text-xs font-medium uppercase tracking-widest text-white/35">
                Quality Snapshot
              </p>
              <div className="space-y-3">
                <MiniBar label="Success" value={performance.successRate} max={100} color="#52c41a" />
                <MiniBar label="Error" value={performance.errorRate} max={Math.max(performance.errorRate, 10)} color="#ff4d4f" />
                <MiniBar label="KB Miss" value={performance.noDocRate} max={Math.max(performance.noDocRate, 20)} color="#faad14" />
                <MiniBar label="Slow" value={performance.slowRate} max={Math.max(performance.slowRate, 15)} color="#ff7a45" />
              </div>
            </div>
          </DashCard>

          {/* Insights */}
          <DashCard>
            <ChartHeader
              title="Operator Insights"
              subtitle="Generated from current state"
              icon={<AlertOutlined />}
            />
            <div className="mt-4 space-y-3">
              {insights.map((item) => (
                <InsightCard key={item.title} item={item} />
              ))}
            </div>
          </DashCard>

          {/* Recommended checks */}
          <DashCard>
            <ChartHeader
              title="Recommended Checks"
              subtitle="Quick paths when health drifts"
              icon={<DatabaseOutlined />}
            />
            <div className="mt-4 space-y-2">
              <CheckItem text="If error rate rises, check model quota and upstream provider latency." />
              <CheckItem text="If knowledge miss remains high, review intents and bound knowledge bases." />
              <CheckItem text="If P95 spikes without volume growth, inspect long-running tool calls." />
            </div>
          </DashCard>
        </div>
      </div>
    </div>
  );
}

/* ─── shared chart config ─── */

const AXIS_TICK = { fill: "rgba(255,255,255,0.35)", fontSize: 11 };
const LEGEND_STYLE = { color: "rgba(255,255,255,0.55)", fontSize: 12 };

/* ─── sub-components ─── */

function DashCard({ children, className = "" }: { children: ReactNode; className?: string }) {
  return (
    <div
      className={[
        "rounded-2xl border border-white/[0.06] bg-white/[0.04] p-5",
        "shadow-admin-card transition-colors",
        className,
      ].join(" ")}
    >
      {children}
    </div>
  );
}

function WindowPills({
  value,
  onChange,
}: {
  value: DashboardWindow;
  onChange: (v: DashboardWindow) => void;
}) {
  return (
    <div className="flex rounded-lg bg-white/[0.06] p-1">
      {WINDOW_OPTIONS.map((opt) => (
        <button
          key={opt}
          type="button"
          onClick={() => onChange(opt)}
          className={[
            "rounded-md px-3.5 py-1.5 text-sm font-medium transition-all",
            value === opt
              ? "bg-white/[0.12] text-white shadow-sm"
              : "text-white/45 hover:text-white/65",
          ].join(" ")}
        >
          {opt}
        </button>
      ))}
    </div>
  );
}

function KpiCard({
  def,
  metric,
}: {
  def: (typeof KPI_DEFS)[number];
  metric: DashboardOverviewKpiVO;
}) {
  const isUp = metric.delta > 0;

  return (
    <DashCard>
      <div className="flex items-start justify-between">
        <div className="min-w-0 flex-1">
          <p className="text-xs font-medium uppercase tracking-widest text-white/35">
            {def.title}
          </p>
          <p className="mt-2.5 text-2xl font-bold tracking-tight text-white">
            {formatCompactNumber(metric.value)}
          </p>
        </div>
        <span
          className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl text-base"
          style={{ backgroundColor: def.accentBg, color: def.accent }}
        >
          {def.icon}
        </span>
      </div>
      <div className="mt-3.5 flex items-center gap-2 text-sm">
        {metric.delta !== 0 ? (
          <span className={isUp ? "text-[#73d13d]" : "text-[#ff7875]"}>
            {isUp ? "\u2191" : "\u2193"} {formatCompactNumber(Math.abs(metric.delta))}
          </span>
        ) : (
          <span className="text-white/35">—</span>
        )}
        {metric.deltaPct != null && (
          <span className="text-white/35">
            {metric.deltaPct > 0 ? "+" : ""}{metric.deltaPct.toFixed(1)}%
          </span>
        )}
        {metric.deltaPct == null && (
          <span className="text-white/30 text-xs">vs previous window</span>
        )}
      </div>
    </DashCard>
  );
}

function SvgRing({
  percent,
  size = 140,
  strokeWidth = 10,
}: {
  percent: number;
  size?: number;
  strokeWidth?: number;
}) {
  const center = size / 2;
  const radius = (size - strokeWidth) / 2;
  const circumference = 2 * Math.PI * radius;
  const clamped = Math.max(0, Math.min(percent, 100));
  const offset = circumference * (1 - clamped / 100);
  const color = clamped >= 95 ? "#52c41a" : clamped >= 80 ? "#faad14" : "#ff4d4f";

  return (
    <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`}>
      <circle
        cx={center}
        cy={center}
        r={radius}
        fill="none"
        stroke="rgba(255,255,255,0.06)"
        strokeWidth={strokeWidth}
      />
      <circle
        cx={center}
        cy={center}
        r={radius}
        fill="none"
        stroke={color}
        strokeWidth={strokeWidth}
        strokeDasharray={circumference}
        strokeDashoffset={offset}
        strokeLinecap="round"
        transform={`rotate(-90 ${center} ${center})`}
        style={{ transition: "stroke-dashoffset 0.6s ease" }}
      />
      <text
        x={center}
        y={center - 8}
        textAnchor="middle"
        dominantBaseline="central"
        fill="white"
        fontSize="26"
        fontWeight="700"
      >
        {clamped.toFixed(1)}%
      </text>
      <text
        x={center}
        y={center + 16}
        textAnchor="middle"
        dominantBaseline="central"
        fill="rgba(255,255,255,0.4)"
        fontSize="11"
      >
        Success Rate
      </text>
    </svg>
  );
}

function MetricRow({
  label,
  value,
  color,
}: {
  label: string;
  value: string;
  color: string;
}) {
  return (
    <div className="flex items-center justify-between rounded-lg border border-white/[0.05] bg-white/[0.025] px-3.5 py-2.5">
      <div className="flex items-center gap-2.5">
        <span
          className="inline-block h-2 w-2 rounded-full"
          style={{ backgroundColor: color }}
        />
        <span className="text-sm text-white/50">{label}</span>
      </div>
      <span className="text-sm font-semibold text-white">{value}</span>
    </div>
  );
}

function MiniBar({
  label,
  value,
  max,
  color,
}: {
  label: string;
  value: number;
  max: number;
  color: string;
}) {
  const pct = max > 0 ? Math.min((value / max) * 100, 100) : 0;

  return (
    <div>
      <div className="flex items-center justify-between text-xs">
        <span className="text-white/40">{label}</span>
        <span className="font-medium text-white/70">{value.toFixed(1)}%</span>
      </div>
      <div className="mt-1.5 h-1.5 overflow-hidden rounded-full bg-white/[0.06]">
        <div
          className="h-full rounded-full transition-all duration-500"
          style={{ width: `${pct}%`, backgroundColor: color }}
        />
      </div>
    </div>
  );
}

function MetricChip({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-lg border border-white/[0.08] bg-black/20 px-3.5 py-2.5">
      <p className="text-[10px] font-medium uppercase tracking-widest text-white/35">{label}</p>
      <p className="mt-1 text-base font-semibold text-white">{value}</p>
    </div>
  );
}

function ChartHeader({
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
        <h3 className="text-[15px] font-semibold text-white">{title}</h3>
        <p className="mt-1 text-xs text-white/40">{subtitle}</p>
      </div>
      <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-white/[0.04] text-sm text-white/40">
        {icon}
      </span>
    </div>
  );
}

function ChartContainer({ data, children }: { data: ChartRow[]; children: ReactNode }) {
  if (data.length === 0) {
    return (
      <div className="mt-5 flex h-[220px] flex-col items-center justify-center gap-2 text-white/25">
        <LineChartOutlined style={{ fontSize: 28 }} />
        <span className="text-sm">No data for this window</span>
      </div>
    );
  }
  return <div className="mt-5">{children}</div>;
}

function InsightCard({ item }: { item: InsightItem }) {
  const style =
    item.tone === "risk"
      ? { badge: "#ff4d4f", bg: "rgba(255,77,79,0.06)", border: "rgba(255,77,79,0.18)" }
      : item.tone === "warn"
        ? { badge: "#faad14", bg: "rgba(250,173,20,0.06)", border: "rgba(250,173,20,0.18)" }
        : { badge: "#52c41a", bg: "rgba(82,196,26,0.06)", border: "rgba(82,196,26,0.18)" };

  const label = item.tone === "risk" ? "Anomaly" : item.tone === "warn" ? "Trend" : "OK";

  return (
    <div
      className="rounded-xl border px-4 py-3.5"
      style={{ backgroundColor: style.bg, borderColor: style.border }}
    >
      <div className="flex items-center gap-2.5">
        <span
          className="rounded-md px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wider"
          style={{ backgroundColor: `${style.badge}20`, color: style.badge }}
        >
          {label}
        </span>
        <span className="text-sm font-medium text-white">{item.title}</span>
      </div>
      <p className="mt-2 text-sm leading-relaxed text-white/50">{item.body}</p>
    </div>
  );
}

function CheckItem({ text }: { text: string }) {
  return (
    <div className="flex gap-2.5 rounded-lg border border-white/[0.04] bg-white/[0.02] px-3.5 py-3 text-sm leading-relaxed text-white/50">
      <span className="mt-0.5 shrink-0 text-white/25">›</span>
      <span>{text}</span>
    </div>
  );
}

function LoadingSpinner() {
  return (
    <div className="flex flex-col items-center gap-3">
      <div className="h-8 w-8 animate-spin rounded-full border-2 border-white/10 border-t-white/50" />
      <span className="text-sm text-white/35">Loading dashboard...</span>
    </div>
  );
}

/* ─── tooltip renderer ─── */

interface DashboardTooltipEntry {
  name?: string | number;
  value?: string | number | readonly (string | number)[];
  color?: string;
}

interface DashboardTooltipProps {
  active?: boolean;
  payload?: readonly DashboardTooltipEntry[];
  label?: string | number;
}

function renderTooltip({
  unit,
  formatter,
}: { unit?: string; formatter?: (v: number) => string } = {}) {
  return function DashboardTooltip({ active, payload, label }: DashboardTooltipProps) {
    if (!active || !payload?.length || label == null) return null;

    return (
      <div className="rounded-xl border border-white/[0.08] bg-[#1a1a1a] px-4 py-3 shadow-admin-card-dark">
        <p className="text-[10px] font-medium uppercase tracking-widest text-white/30">
          {formatDateTime(Number(label))}
        </p>
        <div className="mt-2.5 space-y-1.5">
          {payload.map((entry, i: number) => (
            <div key={`${String(entry.name)}-${i}`} className="flex items-center justify-between gap-5 text-sm">
              <div className="flex items-center gap-2 text-white/50">
                <span className="inline-block h-2 w-2 rounded-full" style={{ backgroundColor: entry.color }} />
                <span>{entry.name}</span>
              </div>
              <span className="font-semibold text-white">
                {formatter
                  ? formatter(Number(entry.value ?? 0))
                  : `${Number(entry.value ?? 0).toFixed(unit ? 1 : 0)}${unit ?? ""}`}
              </span>
            </div>
          ))}
        </div>
      </div>
    );
  };
}

/* ─── data helpers ─── */

function mergeTrendSeries(...trends: DashboardTrendsVO[]): ChartRow[] {
  const rowsByTs = new Map<number, ChartRow>();

  for (const trend of trends) {
    for (const series of trend.series) {
      for (const point of series.data) {
        const existing = rowsByTs.get(point.ts) ?? { ts: point.ts };
        existing[series.name] = point.value;
        rowsByTs.set(point.ts, existing);
      }
    }
  }

  return Array.from(rowsByTs.values()).sort((a, b) => Number(a.ts) - Number(b.ts));
}

function buildInsights(
  window: DashboardWindow,
  overview: DashboardOverviewVO,
  performance: DashboardPerformanceVO,
): InsightItem[] {
  const items: InsightItem[] = [];

  if (performance.errorRate >= 5) {
    items.push({
      title: "Elevated error pressure",
      body: `Error rate reached ${formatPercent(performance.errorRate)} in ${labelForWindow(window)}. Start with provider quota, upstream timeouts, and recent deployments.`,
      tone: "risk",
    });
  } else if (performance.errorRate >= 2) {
    items.push({
      title: "Error rate worth watching",
      body: `Error rate is at ${formatPercent(performance.errorRate)}. A quick check on retries and failed tool calls is recommended.`,
      tone: "warn",
    });
  } else {
    items.push({
      title: "System health is stable",
      body: `Error rate is ${formatPercent(performance.errorRate)} and success rate is ${formatPercent(performance.successRate)}.`,
      tone: "good",
    });
  }

  if (performance.noDocRate >= 15) {
    items.push({
      title: "Knowledge coverage gap",
      body: `Knowledge miss rate is ${formatPercent(performance.noDocRate)}. Review intent routing and bound knowledge bases.`,
      tone: "warn",
    });
  } else {
    items.push({
      title: "Retrieval coverage is acceptable",
      body: `Knowledge miss rate is ${formatPercent(performance.noDocRate)}. Continue monitoring new topics.`,
      tone: "good",
    });
  }

  if (overview.kpis.activeUsers.delta > 0 && overview.kpis.sessions24h.delta > 0) {
    items.push({
      title: "Usage momentum improving",
      body: "Active users and session volume both grew versus the previous window.",
      tone: "good",
    });
  } else if (performance.p95LatencyMs >= 10000) {
    items.push({
      title: "Tail latency needs attention",
      body: `P95 latency is ${formatLatency(performance.p95LatencyMs)}. Investigate long-running retrieval or tool execution.`,
      tone: "warn",
    });
  } else {
    items.push({
      title: "Traffic is steady",
      body: "Usage is not accelerating sharply — a good window to improve quality and latency.",
      tone: "good",
    });
  }

  return items.slice(0, 3);
}

function resolveHealth(errorRate: number) {
  if (errorRate >= 5) {
    return {
      label: "Risk",
      description: "Enough failed turns that users may notice degraded reliability.",
      border: "border-[#ff4d4f]/25",
      background: "bg-[linear-gradient(135deg,rgba(255,77,79,0.10),transparent)]",
      color: "#ff4d4f",
      dotColor: "#ff4d4f",
    };
  }
  if (errorRate >= 2) {
    return {
      label: "Watch",
      description: "Responses are still largely successful, but there is enough friction to investigate.",
      border: "border-[#faad14]/25",
      background: "bg-[linear-gradient(135deg,rgba(250,173,20,0.10),transparent)]",
      color: "#faad14",
      dotColor: "#faad14",
    };
  }
  return {
    label: "Healthy",
    description: "Core delivery and retrieval quality are within a comfortable band.",
    border: "border-[#52c41a]/25",
    background: "bg-[linear-gradient(135deg,rgba(82,196,26,0.08),transparent)]",
    color: "#52c41a",
    dotColor: "#52c41a",
  };
}

/* ─── formatters ─── */

function formatCompactNumber(value: number): string {
  return new Intl.NumberFormat("en-US", {
    notation: value >= 1000 ? "compact" : "standard",
    maximumFractionDigits: value >= 1000 ? 1 : 0,
  }).format(value);
}

function formatPercent(value: number): string {
  return `${value.toFixed(1)}%`;
}

function formatLatency(value: number): string {
  return value >= 1000 ? `${(value / 1000).toFixed(1)}s` : `${Math.round(value)}ms`;
}

function formatAxisLabel(value: number, granularity: string): string {
  const d = new Date(value);
  return (granularity as DashboardGranularity) === "hour"
    ? d.toLocaleTimeString("en-US", { hour: "numeric", hour12: false })
    : d.toLocaleDateString("en-US", { month: "short", day: "numeric" });
}

function formatDateTime(value: number): string {
  return new Date(value).toLocaleString("en-US", {
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
}

function labelForWindow(window: DashboardWindow): string {
  switch (window) {
    case "24h": return "the last 24 hours";
    case "7d": return "the last 7 days";
    case "30d": return "the last 30 days";
    default: return window;
  }
}
