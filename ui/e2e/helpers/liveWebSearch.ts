import type { APIRequestContext } from "@playwright/test";
import { getApi } from "./api";
import { liveWebSearchEnabled } from "./env";

export const LIVE_WEB_SEARCH_OPT_IN_ENV = "PLAYWRIGHT_LIVE_WEB_SEARCH";
export const WEB_SEARCH_SEARXNG_BASE_URL_ENV =
  "CHATAGENT_WEB_SEARCH_SEARXNG_BASE_URL";
export const WEB_SEARCH_BACKEND_TOOL_NAME = "webSearchTool";
export const WEB_SEARCH_MODEL_TOOL_NAME = "webSearch";
export const LIVE_WEB_SEARCH_PREFLIGHT_QUERY =
  "OpenAI official site current public information";

interface ToolVO {
  name: string;
  description?: string | null;
  type: string;
}

interface SearxngResult {
  title?: unknown;
  url?: unknown;
  content?: unknown;
  engine?: unknown;
}

interface SearxngPayload {
  results?: unknown;
}

export interface LiveWebSearchToolSummary {
  exposedTool: string;
  type: string;
  descriptionPresent: boolean;
}

export interface LiveWebSearchPreflightSummary {
  query: string;
  resultCount: number;
  publicResultCount: number;
  publicDomains: string[];
  fixtureRejectionPassed: true;
}

export interface LiveWebSearchPreflightResult {
  tool: LiveWebSearchToolSummary;
  search: LiveWebSearchPreflightSummary;
}

export interface PublicUrlCheck {
  ok: boolean;
  domain?: string;
  reason?: string;
}

const FIXTURE_TEXT_MARKERS = [
  "E2E-WEB-SEARCH-BEACON-ORCHARD-GREEN",
  "engine\":\"local-fixture",
  "engine: \"local-fixture",
  "local-fixture",
  "example.test",
];

const RESERVED_EXACT_HOSTS = new Set([
  "example.com",
  "example.net",
  "example.org",
  "example.test",
]);

const RESERVED_SUFFIXES = [
  ".example",
  ".invalid",
  ".localhost",
  ".test",
];

function uniqueStrings(values: string[]): string[] {
  return [...new Set(values.filter(Boolean))];
}

function normalizeHostname(hostname: string): string {
  return hostname.toLowerCase().replace(/^\[|\]$/g, "");
}

function isIpv4Address(hostname: string): boolean {
  return /^\d{1,3}(?:\.\d{1,3}){3}$/.test(hostname);
}

function isPrivateOrLoopbackIpv4(hostname: string): boolean {
  if (!isIpv4Address(hostname)) {
    return false;
  }
  const parts = hostname.split(".").map((part) => Number.parseInt(part, 10));
  if (parts.some((part) => Number.isNaN(part) || part < 0 || part > 255)) {
    return true;
  }
  const [first, second] = parts;
  return (
    first === 0 ||
    first === 10 ||
    first === 127 ||
    (first === 100 && second >= 64 && second <= 127) ||
    (first === 172 && second >= 16 && second <= 31) ||
    (first === 192 && second === 0) ||
    (first === 192 && second === 168) ||
    (first === 169 && second === 254) ||
    (first === 198 && (second === 18 || second === 19)) ||
    first >= 224
  );
}

function isPrivateOrLoopbackIpv6(hostname: string): boolean {
  if (!hostname.includes(":")) {
    return false;
  }
  return (
    hostname === "::" ||
    hostname === "::1" ||
    hostname === "0:0:0:0:0:0:0:1" ||
    hostname.startsWith("::ffff:") ||
    hostname.startsWith("fc") ||
    hostname.startsWith("fd") ||
    /^fe[89ab][0-9a-f]:/.test(hostname)
  );
}

function isReservedHost(hostname: string): boolean {
  return (
    RESERVED_EXACT_HOSTS.has(hostname) ||
    RESERVED_SUFFIXES.some((suffix) => hostname.endsWith(suffix))
  );
}

export function classifyLiveWebSearchResultUrl(value: string): PublicUrlCheck {
  let url: URL;
  try {
    url = new URL(value);
  } catch {
    return { ok: false, reason: "invalid-url" };
  }

  if (url.protocol !== "http:" && url.protocol !== "https:") {
    return { ok: false, reason: "non-http-url" };
  }

  const hostname = normalizeHostname(url.hostname);
  if (hostname === "localhost" || hostname.endsWith(".localhost")) {
    return { ok: false, reason: "localhost-url" };
  }
  if (isReservedHost(hostname)) {
    return { ok: false, reason: "reserved-test-domain" };
  }
  if (
    isPrivateOrLoopbackIpv4(hostname) ||
    isPrivateOrLoopbackIpv6(hostname)
  ) {
    return { ok: false, reason: "private-or-loopback-url" };
  }

  return { ok: true, domain: hostname };
}

export function buildSearxngSearchUrl(baseUrl: string, query: string): string {
  const url = new URL(baseUrl);
  const trimmedPath = url.pathname.replace(/\/+$/, "");
  url.pathname = trimmedPath.endsWith("/search")
    ? trimmedPath
    : `${trimmedPath}/search`;
  url.search = "";
  url.searchParams.set("q", query);
  url.searchParams.set("format", "json");
  url.searchParams.set("language", "auto");
  url.searchParams.set("categories", "general");
  url.searchParams.set("safesearch", "1");
  return url.toString();
}

export function containsLiveWebSearchFixtureFingerprint(value: unknown): boolean {
  const serialized = JSON.stringify(value) ?? "";
  return FIXTURE_TEXT_MARKERS.some((marker) =>
    serialized.toLowerCase().includes(marker.toLowerCase()),
  );
}

function searxngResults(payload: unknown): SearxngResult[] {
  const candidate = payload as SearxngPayload;
  if (!candidate || !Array.isArray(candidate.results)) {
    throw new Error("SearXNG live preflight expected a JSON object with a results array.");
  }
  return candidate.results as SearxngResult[];
}

export function summarizeSearxngPreflightPayload(
  payload: unknown,
  query = LIVE_WEB_SEARCH_PREFLIGHT_QUERY,
): LiveWebSearchPreflightSummary {
  if (containsLiveWebSearchFixtureFingerprint(payload)) {
    throw new Error(
      "SearXNG live preflight rejected fixture fingerprints in the provider response.",
    );
  }

  const results = searxngResults(payload);
  if (results.length === 0) {
    throw new Error("SearXNG live preflight returned no results.");
  }

  const publicDomains: string[] = [];
  for (const result of results) {
    if (typeof result.url !== "string" || result.url.trim().length === 0) {
      throw new Error("SearXNG live preflight returned a result without a source URL.");
    }
    const check = classifyLiveWebSearchResultUrl(result.url);
    if (!check.ok) {
      throw new Error(
        `SearXNG live preflight rejected non-public result URL (${check.reason}).`,
      );
    }
    publicDomains.push(check.domain ?? "");
  }

  const domains = uniqueStrings(publicDomains);
  if (domains.length === 0) {
    throw new Error("SearXNG live preflight found no public source domains.");
  }

  return {
    query,
    resultCount: results.length,
    publicResultCount: publicDomains.length,
    publicDomains: domains,
    fixtureRejectionPassed: true,
  };
}

export function requireLiveWebSearchOptIn(): void {
  if (!liveWebSearchEnabled) {
    throw new Error(
      `Live web-search acceptance requires ${LIVE_WEB_SEARCH_OPT_IN_ENV}=true.`,
    );
  }
}

export async function requireBackendWebSearchTool(
  request: APIRequestContext,
  accessToken: string,
): Promise<LiveWebSearchToolSummary> {
  const tools = await getApi<ToolVO[]>(request, "/api/tools", accessToken);
  const webSearchTool = tools.find(
    (tool) => tool.name === WEB_SEARCH_BACKEND_TOOL_NAME,
  );
  if (!webSearchTool) {
    throw new Error(
      [
        "Live web-search prerequisite failed: webSearchTool is not exposed by /api/tools.",
        "Start the backend with CHATAGENT_WEB_SEARCH_ENABLED=true and a reachable real SearXNG-compatible endpoint.",
        `Set ${LIVE_WEB_SEARCH_OPT_IN_ENV}=true only for the live acceptance run.`,
      ].join(" "),
    );
  }

  return {
    exposedTool: webSearchTool.name,
    type: webSearchTool.type,
    descriptionPresent: Boolean(webSearchTool.description),
  };
}

function readSearxngBaseUrl(): string {
  const baseUrl = process.env[WEB_SEARCH_SEARXNG_BASE_URL_ENV]?.trim();
  if (!baseUrl) {
    throw new Error(
      `Live web-search preflight requires ${WEB_SEARCH_SEARXNG_BASE_URL_ENV} to point at a real SearXNG-compatible endpoint.`,
    );
  }
  return baseUrl;
}

async function parseJsonResponse(response: Awaited<ReturnType<APIRequestContext["get"]>>) {
  try {
    return await response.json();
  } catch {
    throw new Error("SearXNG live preflight expected a JSON response.");
  }
}

export async function runLiveWebSearchPreflight(
  request: APIRequestContext,
  accessToken: string,
): Promise<LiveWebSearchPreflightResult> {
  requireLiveWebSearchOptIn();
  const tool = await requireBackendWebSearchTool(request, accessToken);
  const searchUrl = buildSearxngSearchUrl(
    readSearxngBaseUrl(),
    LIVE_WEB_SEARCH_PREFLIGHT_QUERY,
  );
  const response = await request.get(searchUrl, { timeout: 30_000 });
  if (!response.ok()) {
    throw new Error(
      `SearXNG live preflight failed with HTTP ${response.status()}; check ${WEB_SEARCH_SEARXNG_BASE_URL_ENV}.`,
    );
  }
  const payload = await parseJsonResponse(response);
  return {
    tool,
    search: summarizeSearxngPreflightPayload(payload),
  };
}
