import { request as playwrightRequest } from "@playwright/test";
import { expect, test } from "../fixtures";
import { deleteApi, getApi, postApi } from "../helpers/api";
import { loginThroughUi, loginUser, type E2eUser } from "../helpers/auth";
import {
  startChatAndWaitForAssistant,
  waitForInputReady,
} from "../helpers/chat";
import {
  readAssistantAllowedTools,
  setAssistantAllowedTools,
  waitForToolCallEvidence,
  waitForTurnCompletion,
} from "../helpers/db";
import {
  apiBaseUrl,
  e2eRunId,
  normalStorageStatePath,
} from "../helpers/env";
import { readE2eUsers } from "../helpers/testUsers";

const SYSTEM_ASSISTANT_ID = "3f9f84f7-2df0-4a5f-9c85-9f2d9b7aaf10";
const MCP_ENDPOINT_URL = "http://localhost:8090/mcp";
const MCP_SLUG_PREFIX = "e2e_weather_";
const TURN_TIMEOUT_MS = 240_000;
const MCP_JOURNEY_TIMEOUT_MS = 900_000;
const WEB_SEARCH_BACKEND_TOOL_NAME = "webSearchTool";
const WEB_SEARCH_MODEL_TOOL_NAME = "webSearch";
const WEB_SEARCH_MARKER =
  process.env.E2E_WEB_SEARCH_MARKER ?? "E2E-WEB-SEARCH-BEACON-ORCHARD-GREEN";
const WEB_SEARCH_SOURCE_URL =
  process.env.E2E_WEB_SEARCH_SOURCE_URL ??
  "https://example.test/e2e/beacon-orchard-status";

interface McpServerVO {
  id: string;
  slug: string;
  name: string;
  status: string;
}

interface McpDiscoveredToolVO {
  remoteOriginalName: string;
  exposedModelName: string;
}

interface TestMcpServerResponse {
  success: boolean;
  errorCode?: string | null;
  errorMessage?: string | null;
  discoveredToolCount: number;
  discoveredTools: McpDiscoveredToolVO[];
  server: McpServerVO;
}

interface SyncMcpToolCatalogResponse {
  success: boolean;
  errorCode?: string | null;
  errorMessage?: string | null;
  activeToolCount: number;
  activeTools: McpDiscoveredToolVO[];
  server: McpServerVO;
}

interface ToolVO {
  name: string;
  description: string;
  type: string;
}

interface DashboardMcpServerMetricVO {
  serverId: string;
  serverSlug: string;
  totalCalls: number;
  successCount: number;
  failureCount: number;
  rateLimitedCount: number;
}

interface DashboardPerformanceVO {
  mcp?: {
    enabled: boolean;
    serverCount: number;
    openAlertCount: number;
    servers: DashboardMcpServerMetricVO[];
  };
}

function generatedSlug(): string {
  const suffix = e2eRunId
    .replace(/[^a-zA-Z0-9]/g, "")
    .slice(-8)
    .toLowerCase();
  return `${MCP_SLUG_PREFIX}${suffix}`;
}

function assertEnglishAnswer(content: string) {
  expect(content).not.toMatch(/[\u3400-\u9fff]/);
}

function uniqueTools(toolNames: string[]): string[] {
  return [...new Set(toolNames)];
}

function requireWebSearchTool(tools: ToolVO[]) {
  const webSearchTool = tools.find(
    (tool) => tool.name === WEB_SEARCH_BACKEND_TOOL_NAME,
  );
  if (!webSearchTool) {
    throw new Error(
      [
        "Phase 6 web-search prerequisite failed: webSearchTool is not exposed by /api/tools.",
        "Start the backend with CHATAGENT_WEB_SEARCH_ENABLED=true and a configured Brave credential.",
        "Use only the deterministic Brave fixture for local headed validation.",
      ].join(" "),
    );
  }
  return webSearchTool;
}

async function deleteGeneratedMcpServers(adminUser: E2eUser) {
  const context = await playwrightRequest.newContext({ baseURL: apiBaseUrl });
  try {
    const auth = await loginUser(context, adminUser);
    const servers = await getApi<McpServerVO[]>(
      context,
      "/api/admin/mcp-servers",
      auth.accessToken,
    );
    const generated = servers.filter((server) =>
      server.slug.startsWith(MCP_SLUG_PREFIX),
    );
    for (const server of generated) {
      await deleteApi(
        context,
        `/api/admin/mcp-servers/${server.id}?force=true`,
        auth.accessToken,
      );
    }
    const remaining = await getApi<McpServerVO[]>(
      context,
      "/api/admin/mcp-servers",
      auth.accessToken,
    );
    const remainingGenerated = remaining.filter((server) =>
      server.slug.startsWith(MCP_SLUG_PREFIX),
    );
    if (remainingGenerated.length > 0) {
      throw new Error(
        `Generated MCP servers were not fully cleaned: ${remainingGenerated
          .map((server) => server.slug)
          .join(", ")}`,
      );
    }
  } finally {
    await context.dispose();
  }
}

async function createAndSyncLocalWeatherMcp(adminUser: E2eUser) {
  const context = await playwrightRequest.newContext({ baseURL: apiBaseUrl });
  try {
    const auth = await loginUser(context, adminUser);
    const serverId = await postApi<string>(
      context,
      "/api/admin/mcp-servers",
      {
        slug: generatedSlug(),
        name: "E2E Weather MCP",
        description: "Generated local MCP server for headed E2E.",
        protocol: "HTTP",
        authType: "NONE",
        endpointUrl: MCP_ENDPOINT_URL,
      },
      auth.accessToken,
    );

    const testResult = await postApi<TestMcpServerResponse>(
      context,
      `/api/admin/mcp-servers/${serverId}/test`,
      undefined,
      auth.accessToken,
    );
    if (!testResult.success) {
      throw new Error(
        `Local MCP server test failed: ${testResult.errorCode ?? "UNKNOWN"} ${testResult.errorMessage ?? ""}`.trim(),
      );
    }

    const syncResult = await postApi<SyncMcpToolCatalogResponse>(
      context,
      `/api/admin/mcp-servers/${serverId}/sync`,
      undefined,
      auth.accessToken,
    );
    if (!syncResult.success) {
      throw new Error(
        `Local MCP server sync failed: ${syncResult.errorCode ?? "UNKNOWN"} ${syncResult.errorMessage ?? ""}`.trim(),
      );
    }

    const requiredRemoteNames = [
      "get_current_datetime",
      "convert_time",
    ];
    const activeTools = syncResult.activeTools;
    for (const requiredRemoteName of requiredRemoteNames) {
      expect(
        activeTools.some(
          (tool) => tool.remoteOriginalName === requiredRemoteName,
        ),
      ).toBe(true);
    }

    const optionalTools = await getApi<ToolVO[]>(
      context,
      "/api/tools",
      auth.accessToken,
    );
    for (const tool of activeTools) {
      expect(optionalTools.some((candidate) => candidate.name === tool.exposedModelName)).toBe(true);
    }

    return {
      serverId,
      serverSlug: syncResult.server.slug,
      activeTools,
      currentDateTimeTool: activeTools.find(
        (tool) => tool.remoteOriginalName === "get_current_datetime",
      )?.exposedModelName,
      convertTimeTool: activeTools.find(
        (tool) => tool.remoteOriginalName === "convert_time",
      )?.exposedModelName,
    };
  } finally {
    await context.dispose();
  }
}

test.describe("@tools @mcp local weather tools", () => {
  test.use({ storageState: normalStorageStatePath });

  test("syncs local MCP tools and triggers them from natural conversation", async ({
    page,
  }, testInfo) => {
    test.setTimeout(MCP_JOURNEY_TIMEOUT_MS);
    const users = await readE2eUsers();
    let originalAllowedTools: string[] | null = null;
    let cleanupError: Error | null = null;

    try {
      await deleteGeneratedMcpServers(users.admin);
      const synced = await createAndSyncLocalWeatherMcp(users.admin);
      await testInfo.attach("mcp-sync-evidence", {
        body: JSON.stringify(
          {
            serverId: synced.serverId,
            activeToolCount: synced.activeTools.length,
            activeTools: synced.activeTools.map((tool) => ({
              remoteOriginalName: tool.remoteOriginalName,
              exposedModelName: tool.exposedModelName,
            })),
          },
          null,
          2,
        ),
        contentType: "application/json",
      });

      if (!synced.currentDateTimeTool || !synced.convertTimeTool) {
        throw new Error("Synced MCP tools did not include the required time tools.");
      }

      const assistantTools = await readAssistantAllowedTools(SYSTEM_ASSISTANT_ID);
      originalAllowedTools = assistantTools.allowedTools;
      await setAssistantAllowedTools(SYSTEM_ASSISTANT_ID, [
        ...originalAllowedTools,
        ...synced.activeTools.map((tool) => tool.exposedModelName),
      ]);

      await loginThroughUi(page, users.normal, normalStorageStatePath);

      const currentTimePrompt =
        "Could you check what time it is in Singapore right now? Answer in English with the IANA timezone name and the local time.";
      const currentTimeAnswer = await startChatAndWaitForAssistant(
        page,
        currentTimePrompt,
        { mode: "REACT", timeoutMs: TURN_TIMEOUT_MS },
      );
      expect(currentTimeAnswer.user.turnId).toBeTruthy();
      await waitForTurnCompletion(
        currentTimeAnswer.sessionId,
        currentTimeAnswer.user.turnId!,
      );
      const currentTimeEvidence = await waitForToolCallEvidence(
        currentTimeAnswer.sessionId,
        currentTimeAnswer.user.turnId!,
        [synced.currentDateTimeTool],
      );
      await testInfo.attach("mcp-current-time-tool-evidence", {
        body: JSON.stringify(currentTimeEvidence, null, 2),
        contentType: "application/json",
      });
      expect(currentTimeAnswer.assistant.content).toMatch(/Asia\/Singapore/i);
      assertEnglishAnswer(currentTimeAnswer.assistant.content);
      await waitForInputReady(page);

      const conversionPrompt =
        "I need an exact timezone conversion: when it is 2026-06-19T09:30:00 in Singapore, what is the corresponding time in New York? Answer in English with both IANA timezone names.";
      const conversionAnswer = await startChatAndWaitForAssistant(
        page,
        conversionPrompt,
        { mode: "REACT", timeoutMs: TURN_TIMEOUT_MS },
      );
      expect(conversionAnswer.user.turnId).toBeTruthy();
      await waitForTurnCompletion(
        conversionAnswer.sessionId,
        conversionAnswer.user.turnId!,
      );
      const conversionEvidence = await waitForToolCallEvidence(
        conversionAnswer.sessionId,
        conversionAnswer.user.turnId!,
        [synced.convertTimeTool],
      );
      await testInfo.attach("mcp-convert-time-tool-evidence", {
        body: JSON.stringify(conversionEvidence, null, 2),
        contentType: "application/json",
      });
      expect(conversionAnswer.assistant.content).toMatch(/America\/New_York|New York/i);
      expect(conversionAnswer.assistant.content).toMatch(/2026-06-18|June 18, 2026/i);
      expect(conversionAnswer.assistant.content).toMatch(/21:30|9:30\s*PM/i);
      assertEnglishAnswer(conversionAnswer.assistant.content);

      const adminContext = await playwrightRequest.newContext({ baseURL: apiBaseUrl });
      try {
        const auth = await loginUser(adminContext, users.admin);
        const performance = await getApi<DashboardPerformanceVO>(
          adminContext,
          "/api/admin/dashboard/performance?window=24h",
          auth.accessToken,
        );
        const metric = performance.mcp?.servers.find(
          (server) =>
            server.serverId === synced.serverId ||
            server.serverSlug === synced.serverSlug,
        );
        expect(metric).toBeTruthy();
        expect(metric!.totalCalls).toBeGreaterThanOrEqual(2);
        expect(metric!.successCount).toBeGreaterThanOrEqual(2);
        await testInfo.attach("mcp-admin-metrics-evidence", {
          body: JSON.stringify(
            {
              mcpEnabled: performance.mcp?.enabled,
              serverCount: performance.mcp?.serverCount,
              openAlertCount: performance.mcp?.openAlertCount,
              server: metric
                ? {
                    serverId: metric.serverId,
                    serverSlug: metric.serverSlug,
                    totalCalls: metric.totalCalls,
                    successCount: metric.successCount,
                    failureCount: metric.failureCount,
                    rateLimitedCount: metric.rateLimitedCount,
                  }
                : null,
            },
            null,
            2,
          ),
          contentType: "application/json",
        });
      } finally {
        await adminContext.dispose();
      }
    } finally {
      const cleanupErrors: string[] = [];
      if (originalAllowedTools) {
        await setAssistantAllowedTools(
          SYSTEM_ASSISTANT_ID,
          originalAllowedTools,
        ).catch((error: unknown) => {
          cleanupErrors.push(
            `restore assistant allowed tools: ${error instanceof Error ? error.message : String(error)}`,
          );
        });
      }
      await deleteGeneratedMcpServers(users.admin).catch((error: unknown) => {
        cleanupErrors.push(
          `delete generated MCP servers: ${error instanceof Error ? error.message : String(error)}`,
        );
      });
      if (cleanupErrors.length > 0) {
        cleanupError = new Error(
          `MCP E2E cleanup failed: ${cleanupErrors.join("; ")}`,
        );
      }
    }
    if (cleanupError) {
      throw cleanupError;
    }
  });
});

test.describe("@tools native web search", () => {
  test.use({ storageState: normalStorageStatePath });

  test("uses configured web search from a natural current-information question", async ({
    page,
  }, testInfo) => {
    test.setTimeout(420_000);
    const users = await readE2eUsers();
    let originalAllowedTools: string[] | null = null;

    try {
      const context = await playwrightRequest.newContext({ baseURL: apiBaseUrl });
      try {
        const auth = await loginUser(context, users.admin);
        const optionalTools = await getApi<ToolVO[]>(
          context,
          "/api/tools",
          auth.accessToken,
        );
        const webSearchTool = requireWebSearchTool(optionalTools);
        await testInfo.attach("web-search-prerequisite", {
          body: JSON.stringify(
            {
              exposedTool: webSearchTool.name,
              type: webSearchTool.type,
              descriptionPresent: Boolean(webSearchTool.description),
            },
            null,
            2,
          ),
          contentType: "application/json",
        });
      } finally {
        await context.dispose();
      }

      const assistantTools = await readAssistantAllowedTools(SYSTEM_ASSISTANT_ID);
      originalAllowedTools = assistantTools.allowedTools;
      await setAssistantAllowedTools(
        SYSTEM_ASSISTANT_ID,
        uniqueTools([...originalAllowedTools, WEB_SEARCH_BACKEND_TOOL_NAME]),
      );

      await loginThroughUi(page, users.normal, normalStorageStatePath);

      const prompt =
        "What exact latest public status marker is reported for the Beacon Orchard release from Example Labs? " +
        "Please quote the marker exactly and include the source URL.";
      const answer = await startChatAndWaitForAssistant(page, prompt, {
        mode: "REACT",
        timeoutMs: TURN_TIMEOUT_MS,
      });
      expect(answer.user.turnId).toBeTruthy();
      await waitForTurnCompletion(answer.sessionId, answer.user.turnId!);
      const evidence = await waitForToolCallEvidence(
        answer.sessionId,
        answer.user.turnId!,
        [WEB_SEARCH_MODEL_TOOL_NAME],
      );
      await testInfo.attach("web-search-tool-evidence", {
        body: JSON.stringify(evidence, null, 2),
        contentType: "application/json",
      });
      expect(answer.assistant.content).toContain(WEB_SEARCH_MARKER);
      expect(answer.assistant.content).toContain(WEB_SEARCH_SOURCE_URL);
      assertEnglishAnswer(answer.assistant.content);
    } finally {
      if (originalAllowedTools) {
        await setAssistantAllowedTools(
          SYSTEM_ASSISTANT_ID,
          originalAllowedTools,
        );
      }
    }
  });
});
