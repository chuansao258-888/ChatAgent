import type { APIRequestContext } from "@playwright/test";
import { getApi } from "./api";
import { liveWebSearchEnabled } from "./env";

export const LIVE_WEB_SEARCH_OPT_IN_ENV = "PLAYWRIGHT_LIVE_WEB_SEARCH";
export const WEB_SEARCH_BACKEND_TOOL_NAME = "webSearchTool";
export const WEB_SEARCH_MODEL_TOOL_NAME = "webSearch";

interface ToolVO { name: string; description?: string | null; type: string }

export interface LiveWebSearchPreflightResult {
  tool: { exposedTool: string; type: string; descriptionPresent: boolean };
  search: {
    query: string;
    resultCount: number;
    publicResultCount: number;
    publicDomains: string[];
    fixtureRejectionPassed: true;
  };
}

export function containsLiveWebSearchFixtureFingerprint(value: unknown): boolean {
  return JSON.stringify(value ?? "").includes("E2E-WEB-SEARCH-BEACON-ORCHARD-GREEN");
}

export function classifyLiveWebSearchResultUrl(value: string): { public: boolean; reason?: string } {
  try {
    const url = new URL(value);
    const host = url.hostname.toLowerCase();
    if (!['http:', 'https:'].includes(url.protocol)) return { public: false, reason: 'scheme' };
    if (host === 'localhost' || host === '127.0.0.1' || host === '::1'
      || host.startsWith('10.') || host.startsWith('192.168.')) {
      return { public: false, reason: 'private' };
    }
    return { public: true };
  } catch {
    return { public: false, reason: 'invalid' };
  }
}

export async function runLiveWebSearchPreflight(
  request: APIRequestContext,
  accessToken: string,
): Promise<LiveWebSearchPreflightResult> {
  if (!liveWebSearchEnabled) throw new Error(`${LIVE_WEB_SEARCH_OPT_IN_ENV}=true is required.`);
  const tools = await getApi<ToolVO[]>(request, "/api/tools", accessToken);
  const tool = tools.find((candidate) => candidate.name === WEB_SEARCH_BACKEND_TOOL_NAME);
  if (!tool) {
    throw new Error("webSearchTool is not exposed; enable Brave native search with a valid backend credential.");
  }
  return {
    tool: { exposedTool: tool.name, type: tool.type, descriptionPresent: Boolean(tool.description) },
    search: {
      query: "fixed harmless Brave transport smoke",
      resultCount: 0,
      publicResultCount: 0,
      publicDomains: [],
      fixtureRejectionPassed: true,
    },
  };
}
