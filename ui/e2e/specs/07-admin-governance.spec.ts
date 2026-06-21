import {
  request as playwrightRequest,
  type APIRequestContext,
  type TestInfo,
} from "@playwright/test";
import { expect, test } from "../fixtures";
import { deleteApi, getApi, putApi } from "../helpers/api";
import { loginThroughUi, loginUser, type E2eUser } from "../helpers/auth";
import { adminStorageStatePath, apiBaseUrl, e2eRunId } from "../helpers/env";
import { readE2eUsers } from "../helpers/testUsers";

const MCP_ENDPOINT_URL = "http://localhost:8090/mcp";
const ADMIN_USER_PREFIX = "chatagent.admin.phase8.";
const KB_NAME_PREFIX = "E2E Admin KB";
const MCP_SLUG_PREFIX = "e2e_admin_ops_";
const AVATAR_DATA_URI =
  "data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw==";
const UPDATED_AVATAR_DATA_URI =
  "data:image/gif;base64,R0lGODlhAQABAIABAAAAAP///yH5BAEAAAEALAAAAAABAAEAAAICTAEAOw==";

interface AdminApi {
  context: APIRequestContext;
  token: string;
}

interface AdminUserVO {
  id: string;
  username: string;
  role: string;
  status: string;
  avatar?: string | null;
}

interface GetAdminUsersResponse {
  users: AdminUserVO[];
  page: number;
  size: number;
  total: number;
}

interface KnowledgeBaseVO {
  id: string;
  name: string;
  description?: string | null;
  visibility: string;
  status: string;
}

interface McpServerVO {
  id: string;
  slug: string;
  name: string;
  endpointUrl: string;
  status: string;
}

interface RoutingCandidateState {
  id: string;
  springClientKey: string;
  effectiveEnabled: boolean;
  registered: boolean;
  circuitState: string;
  effectiveThinkingStrategy?: string | null;
}

interface RoutingState {
  defaultModel: string;
  deepThinkingModel?: string | null;
  firstPacketTimeoutSeconds: number;
  registeredModels: string[];
  candidates: RoutingCandidateState[];
}

interface MqOutboxRetryResponse {
  pendingCount: number;
  claimedCount: number;
  sentCount: number;
  failedCount: number;
  retryAgentQueueDepth: number;
  retryIngestQueueDepth: number;
  dlqQueueDepth: number;
  records: Array<{
    eventType?: string | null;
    status?: string | null;
    retryCount: number;
    nextRetryAt?: string | null;
    createdAt?: string | null;
  }>;
}

function suffix(): string {
  return e2eRunId.replace(/[^a-zA-Z0-9]/g, "").slice(-10).toLowerCase();
}

function generatedUsername(): string {
  return `${ADMIN_USER_PREFIX}${suffix()}@example.com`;
}

function generatedKbName(): string {
  return `${KB_NAME_PREFIX} ${suffix().toUpperCase()}`;
}

function generatedMcpSlug(): string {
  return `${MCP_SLUG_PREFIX}${suffix()}`;
}

async function createAdminApi(adminUser: E2eUser): Promise<AdminApi> {
  const context = await playwrightRequest.newContext({ baseURL: apiBaseUrl });
  const auth = await loginUser(context, adminUser);
  return { context, token: auth.accessToken };
}

async function listUsers(api: AdminApi, keyword: string): Promise<AdminUserVO[]> {
  const response = await getApi<GetAdminUsersResponse>(
    api.context,
    `/api/admin/users?keyword=${encodeURIComponent(keyword)}&size=20`,
    api.token,
  );
  return response.users;
}

async function deleteUserIfPresent(api: AdminApi, username: string): Promise<void> {
  const users = await listUsers(api, username);
  for (const user of users.filter((candidate) => candidate.username === username)) {
    await deleteApi<void>(api.context, `/api/admin/users/${user.id}`, api.token).catch(() => undefined);
  }
}

async function deleteGeneratedAdminUsers(api: AdminApi): Promise<void> {
  const users = await listUsers(api, ADMIN_USER_PREFIX);
  for (const user of users.filter((candidate) =>
    candidate.username.startsWith(ADMIN_USER_PREFIX),
  )) {
    await deleteApi<void>(api.context, `/api/admin/users/${user.id}`, api.token).catch(() => undefined);
  }
}

async function listKnowledgeBases(api: AdminApi): Promise<KnowledgeBaseVO[]> {
  return getApi<KnowledgeBaseVO[]>(
    api.context,
    "/api/admin/knowledge-bases",
    api.token,
  );
}

async function getKnowledgeBase(
  api: AdminApi,
  knowledgeBaseId: string,
): Promise<KnowledgeBaseVO> {
  return getApi<KnowledgeBaseVO>(
    api.context,
    `/api/admin/knowledge-bases/${knowledgeBaseId}`,
    api.token,
  );
}

async function deleteKnowledgeBaseIfPresent(
  api: AdminApi,
  knowledgeBaseId: string | null,
): Promise<void> {
  if (!knowledgeBaseId) {
    return;
  }
  await deleteApi<void>(
    api.context,
    `/api/admin/knowledge-bases/${knowledgeBaseId}`,
    api.token,
  ).catch(() => undefined);
}

async function getAssistantBindingIds(api: AdminApi): Promise<string[]> {
  const bindings = await getApi<KnowledgeBaseVO[]>(
    api.context,
    "/api/admin/assistant/knowledge-bases",
    api.token,
  );
  return bindings.map((knowledgeBase) => knowledgeBase.id);
}

async function setAssistantBindingIds(api: AdminApi, knowledgeBaseIds: string[]) {
  await putApi<void>(
    api.context,
    "/api/admin/assistant/knowledge-bases",
    { knowledgeBaseIds },
    api.token,
  );
}

async function listMcpServers(api: AdminApi): Promise<McpServerVO[]> {
  return getApi<McpServerVO[]>(api.context, "/api/admin/mcp-servers", api.token);
}

async function deleteGeneratedMcpServers(api: AdminApi): Promise<void> {
  const servers = await listMcpServers(api);
  for (const server of servers.filter((candidate) =>
    candidate.slug.startsWith(MCP_SLUG_PREFIX),
  )) {
    await deleteApi(
      api.context,
      `/api/admin/mcp-servers/${server.id}?force=true`,
      api.token,
    ).catch(() => undefined);
  }
}

async function findMcpServerBySlug(
  api: AdminApi,
  slug: string,
): Promise<McpServerVO | undefined> {
  const servers = await listMcpServers(api);
  return servers.find((server) => server.slug === slug);
}

async function assertLocalMcpEndpointReachable(request: APIRequestContext) {
  const response = await request.post(MCP_ENDPOINT_URL, {
    headers: { Accept: "application/json, text/event-stream" },
    data: {
      jsonrpc: "2.0",
      id: 1,
      method: "initialize",
      params: {
        protocolVersion: "2025-03-26",
        capabilities: {},
        clientInfo: { name: "chatagent-phase8-e2e", version: "1.0.0" },
      },
    },
  });
  if (!response.ok()) {
    throw new Error(
      `Phase 8 MCP Ops prerequisite failed: ${MCP_ENDPOINT_URL} returned HTTP ${response.status()}. Start MCP/weather-server/start-http.ps1 before running @admin.`,
    );
  }
}

async function attachRoutingEvidence(api: AdminApi, testInfo: TestInfo) {
  const state = await getApi<RoutingState>(
    api.context,
    "/api/admin/chat-routing/state",
    api.token,
  );
  expect(state.defaultModel).toBe("glm-5.2");
  expect(state.registeredModels).toEqual(
    expect.arrayContaining(["glm-5.2", "deepseek-v4-flash"]),
  );
  expect(
    state.candidates.some(
      (candidate) =>
        candidate.id === "deepseek-v4-flash" &&
        candidate.effectiveEnabled &&
        candidate.registered,
    ),
  ).toBe(true);
  await testInfo.attach("admin-chat-routing-state", {
    body: JSON.stringify(
      {
        defaultModel: state.defaultModel,
        deepThinkingModel: state.deepThinkingModel,
        firstPacketTimeoutSeconds: state.firstPacketTimeoutSeconds,
        registeredModels: state.registeredModels,
        candidates: state.candidates.map((candidate) => ({
          id: candidate.id,
          springClientKey: candidate.springClientKey,
          effectiveEnabled: candidate.effectiveEnabled,
          registered: candidate.registered,
          circuitState: candidate.circuitState,
          effectiveThinkingStrategy: candidate.effectiveThinkingStrategy,
        })),
      },
      null,
      2,
    ),
    contentType: "application/json",
  });
}

async function attachMqEvidence(api: AdminApi, testInfo: TestInfo) {
  const state = await getApi<MqOutboxRetryResponse>(
    api.context,
    "/api/admin/mq/outbox/retry?limit=5",
    api.token,
  );
  await testInfo.attach("admin-mq-outbox-retry-state", {
    body: JSON.stringify(
      {
        pendingCount: state.pendingCount,
        claimedCount: state.claimedCount,
        sentCount: state.sentCount,
        failedCount: state.failedCount,
        retryAgentQueueDepth: state.retryAgentQueueDepth,
        retryIngestQueueDepth: state.retryIngestQueueDepth,
        dlqQueueDepth: state.dlqQueueDepth,
        records: state.records.map((record) => ({
          eventType: record.eventType,
          status: record.status,
          retryCount: record.retryCount,
          hasNextRetryAt: Boolean(record.nextRetryAt),
          hasCreatedAt: Boolean(record.createdAt),
        })),
      },
      null,
      2,
    ),
    contentType: "application/json",
  });
}

test.describe("@admin dashboard and operations evidence", () => {
  test.use({ storageState: adminStorageStatePath });

  test("loads dashboard filters and records MQ/routing admin API state", async ({
    page,
  }, testInfo) => {
    const users = await readE2eUsers();
    const adminApi = await createAdminApi(users.admin);
    try {
      await loginThroughUi(page, users.admin, adminStorageStatePath);
      await page.goto("/admin");

      await expect(page.getByText("Admin / Dashboard")).toBeVisible();
      await expect(page.getByRole("heading", { name: "Dashboard" })).toBeVisible();
      await expect(page.getByText("Workload")).toBeVisible();
      await expect(page.getByText("Operator Insights")).toBeVisible();

      await page.getByRole("button", { name: "7d" }).click();
      await expect(page.getByText("Recommended Checks")).toBeVisible();
      await page.getByRole("button", { name: "30d" }).click();
      await expect(page.getByText("AI Performance")).toBeVisible();
      await page.getByTitle("Refresh").click();
      await expect(page.getByText("Quality").first()).toBeVisible();

      await attachRoutingEvidence(adminApi, testInfo);
      await attachMqEvidence(adminApi, testInfo);
    } finally {
      await adminApi.context.dispose();
    }
  });
});

test.describe("@admin user governance", () => {
  test.use({ storageState: adminStorageStatePath });

  test("creates, edits, disables, resets, and deletes a user through the visible UI", async ({
    page,
  }, testInfo) => {
    test.setTimeout(180_000);
    const users = await readE2eUsers();
    const adminApi = await createAdminApi(users.admin);
    const username = generatedUsername();
    let userId: string | null = null;
    try {
      await deleteGeneratedAdminUsers(adminApi);
      await deleteUserIfPresent(adminApi, username);
      await loginThroughUi(page, users.admin, adminStorageStatePath);
      await page.goto("/admin/users");

      await expect(page.getByText("Admin / Users")).toBeVisible();
      await expect(page.getByRole("heading", { name: "User management" })).toBeVisible();

      await page.getByRole("button", { name: "New user" }).click();
      const createDialog = page.getByRole("dialog", { name: "Create user" });
      await expect(createDialog).toBeVisible();
      await createDialog.getByLabel("Username").fill(username);
      await createDialog.getByLabel("Avatar URL").fill(AVATAR_DATA_URI);
      await createDialog.getByRole("button", { name: "Create" }).click();
      await expect(page.getByText("User created.")).toBeVisible();
      const temporaryPasswordDialog = page.getByRole("dialog", {
        name: `Temporary password for ${username}`,
      });
      await expect(temporaryPasswordDialog).toBeVisible();
      await temporaryPasswordDialog.getByRole("button", { name: "Close" }).click();

      const createdUsers = await listUsers(adminApi, username);
      const createdUser = createdUsers.find((user) => user.username === username);
      expect(createdUser).toBeTruthy();
      userId = createdUser!.id;

      await page.getByPlaceholder("Search by username").fill(username);
      await page.getByPlaceholder("Search by username").press("Enter");
      const row = page.locator("tr", { hasText: username }).first();
      await expect(row).toBeVisible();

      await row.getByRole("button", { name: "Edit" }).click();
      const editDialog = page.getByRole("dialog", { name: "Edit user" });
      await expect(editDialog).toBeVisible();
      await editDialog.getByLabel("Avatar URL").fill(UPDATED_AVATAR_DATA_URI);
      await editDialog.getByRole("button", { name: "Save" }).click();
      await expect(page.getByText("User updated.")).toBeVisible();
      const userAfterEdit = (await listUsers(adminApi, username)).find(
        (user) => user.username === username,
      );
      expect(userAfterEdit?.avatar).toBe(UPDATED_AVATAR_DATA_URI);

      await row.getByRole("switch").click();
      await expect(page.getByText("User disabled.")).toBeVisible();
      await expect(row.getByText("Disabled").first()).toBeVisible();
      const userAfterDisable = (await listUsers(adminApi, username)).find(
        (user) => user.username === username,
      );
      expect(userAfterDisable?.status).toBe("DISABLED");

      await row.getByRole("button", { name: "Reset password" }).click();
      await page.getByRole("button", { name: "Reset" }).last().click();
      await expect(page.getByText("Password reset.")).toBeVisible();
      const newPasswordDialog = page.getByRole("dialog", {
        name: `New password for ${username}`,
      });
      await expect(newPasswordDialog).toBeVisible();
      await newPasswordDialog.getByRole("button", { name: "Close" }).click();

      await row.getByRole("button", { name: "Delete" }).click();
      await page.getByRole("button", { name: "Delete" }).last().click();
      await expect(page.getByText("User deleted.")).toBeVisible();
      await expect(page.locator("tr", { hasText: username })).toHaveCount(0);

      const remaining = await listUsers(adminApi, username);
      expect(remaining.some((user) => user.username === username)).toBe(false);
      userId = null;
      await testInfo.attach("admin-user-governance-evidence", {
        body: JSON.stringify(
          {
            username,
            createdUserId: createdUser!.id,
            statusFlow: ["created", "updated", "disabled", "password-reset", "deleted"],
          },
          null,
          2,
        ),
        contentType: "application/json",
      });
    } finally {
      if (userId) {
        await deleteApi<void>(
          adminApi.context,
          `/api/admin/users/${userId}`,
          adminApi.token,
        ).catch(() => undefined);
      }
      await adminApi.context.dispose();
    }
  });
});

test.describe("@admin knowledge base and assistant bindings", () => {
  test.use({ storageState: adminStorageStatePath });

  test("creates, updates, binds, reloads, and deletes a knowledge base through admin pages", async ({
    page,
  }, testInfo) => {
    test.setTimeout(180_000);
    const users = await readE2eUsers();
    const adminApi = await createAdminApi(users.admin);
    const kbName = generatedKbName();
    const updatedDescription = `Updated Phase 8 admin governance KB ${suffix()}`;
    let knowledgeBaseId: string | null = null;
    let originalBindingIds: string[] | null = null;

    try {
      originalBindingIds = await getAssistantBindingIds(adminApi);
      for (const knowledgeBase of await listKnowledgeBases(adminApi)) {
        if (knowledgeBase.name === kbName) {
          await deleteKnowledgeBaseIfPresent(adminApi, knowledgeBase.id);
        }
      }

      await loginThroughUi(page, users.admin, adminStorageStatePath);
      await page.goto("/admin/knowledge-bases");

      await expect(page.getByText("Admin / Knowledge Bases")).toBeVisible();
      await expect(page.getByRole("heading", { name: "Knowledge base catalog" })).toBeVisible();
      await page.getByPlaceholder("Employee Handbook").fill(kbName);
      await page
        .getByPlaceholder("What this knowledge base covers")
        .fill("Generated by Phase 8 admin E2E.");
      await page.getByRole("button", { name: "Create" }).click();
      await expect(page.getByText("Knowledge base created.")).toBeVisible();
      await page.waitForURL(/\/admin\/knowledge-bases\/[^/]+$/);
      knowledgeBaseId = new URL(page.url()).pathname.split("/").pop() ?? null;
      expect(knowledgeBaseId).toBeTruthy();

      await expect(page.getByRole("heading", { name: kbName })).toBeVisible();
      await expect(page.getByText("Knowledge base metadata")).toBeVisible();
      await page.getByLabel("Description").fill(updatedDescription);
      await page.getByRole("button", { name: "Save changes" }).click();
      await expect(page.getByText("Knowledge base updated.")).toBeVisible();
      const savedKnowledgeBase = await getKnowledgeBase(adminApi, knowledgeBaseId!);
      expect(savedKnowledgeBase.description).toBe(updatedDescription);

      await page.goto("/admin/assistant");
      await expect(page.getByText("Admin / Assistant")).toBeVisible();
      await expect(page.getByRole("heading", { name: "Internal assistant bindings" })).toBeVisible();
      const bindingLabel = page.locator("label", { hasText: kbName }).first();
      await expect(bindingLabel).toBeVisible();
      const bindingCheckbox = bindingLabel.getByRole("checkbox");
      if (!(await bindingCheckbox.isChecked())) {
        await bindingLabel.click();
      }
      await page.getByRole("button", { name: "Save bindings" }).click();
      await expect(page.getByText("Assistant bindings updated.")).toBeVisible();
      await page.reload();
      await expect(page.locator("label", { hasText: kbName }).getByRole("checkbox")).toBeChecked();

      const bindingIds = await getAssistantBindingIds(adminApi);
      expect(bindingIds).toContain(knowledgeBaseId);
      await testInfo.attach("admin-kb-binding-evidence", {
        body: JSON.stringify(
          {
            knowledgeBaseId,
            name: kbName,
            updatedDescription,
            assistantBindingContainsGeneratedKb: bindingIds.includes(knowledgeBaseId!),
          },
          null,
          2,
        ),
        contentType: "application/json",
      });

      if (originalBindingIds) {
        await setAssistantBindingIds(adminApi, originalBindingIds);
      }
      await page.goto(`/admin/knowledge-bases/${knowledgeBaseId}`);
      await page.getByRole("button", { name: "Delete" }).first().click();
      await page.getByRole("button", { name: "Delete" }).last().click();
      await expect(page.getByText("Knowledge base deleted.")).toBeVisible();
      await expect(page).toHaveURL(/\/admin\/knowledge-bases$/);
      knowledgeBaseId = null;
    } finally {
      if (originalBindingIds) {
        await setAssistantBindingIds(adminApi, originalBindingIds).catch(() => undefined);
      }
      await deleteKnowledgeBaseIfPresent(adminApi, knowledgeBaseId);
      await adminApi.context.dispose();
    }
  });
});

test.describe("@admin MCP Ops page actions", () => {
  test.use({ storageState: adminStorageStatePath });

  test("adds, tests, syncs, and deletes a local MCP server from the Ops page", async ({
    page,
    request,
  }, testInfo) => {
    test.setTimeout(240_000);
    const users = await readE2eUsers();
    const adminApi = await createAdminApi(users.admin);
    const slug = generatedMcpSlug();
    const name = `E2E Admin Ops MCP ${suffix().toUpperCase()}`;
    let serverId: string | null = null;

    try {
      await assertLocalMcpEndpointReachable(request);
      await deleteGeneratedMcpServers(adminApi);
      await loginThroughUi(page, users.admin, adminStorageStatePath);
      await page.goto("/admin/mcp");

      await expect(page.getByText("Admin / MCP Operations")).toBeVisible();
      await expect(page.getByRole("heading", { name: "MCP Ops" })).toBeVisible();
      await expect(page.getByText("Guardrails")).toBeVisible();

      await page.getByRole("button", { name: "Add server" }).click();
      const drawer = page.getByRole("dialog", { name: "Add MCP server" });
      await expect(drawer).toBeVisible();
      await drawer.getByLabel("Slug").fill(slug);
      await drawer.getByLabel("Display name").fill(name);
      await drawer.getByLabel("Description").fill("Generated by Phase 8 admin Ops E2E.");
      await drawer.getByLabel("Endpoint URL").fill(MCP_ENDPOINT_URL);
      await drawer.getByRole("button", { name: "Add server" }).click();
      await expect(page.getByText("MCP server created. Run Test first, then Sync to expose tools.")).toBeVisible();

      const createdServer = await findMcpServerBySlug(adminApi, slug);
      expect(createdServer).toBeTruthy();
      serverId = createdServer!.id;
      const serverCard = page
        .getByText(slug, { exact: true })
        .locator("xpath=ancestor::div[contains(@class,'rounded-2xl')][1]");
      await serverCard.scrollIntoViewIfNeeded();
      await expect(serverCard.getByRole("button", { name: "Test" })).toBeVisible();

      await serverCard.getByRole("button", { name: "Test" }).click();
      await expect(page.getByText(/Probe succeeded\./)).toBeVisible({ timeout: 60_000 });
      await serverCard.getByRole("button", { name: "Sync" }).click();
      await expect(page.getByText(/Catalog synced\./)).toBeVisible({ timeout: 60_000 });

      const syncedServer = await findMcpServerBySlug(adminApi, slug);
      expect(syncedServer?.status).toBe("ACTIVE");
      await testInfo.attach("admin-mcp-ops-evidence", {
        body: JSON.stringify(
          {
            serverId,
            slug,
            name,
            endpointUrl: MCP_ENDPOINT_URL,
            statusAfterSync: syncedServer?.status,
          },
          null,
          2,
        ),
        contentType: "application/json",
      });

      await serverCard.getByRole("button", { name: "Delete" }).click();
      await expect(page.getByText("MCP server deleted.")).toBeVisible({ timeout: 30_000 });
      await expect(page.getByText(slug, { exact: true })).toHaveCount(0);
      serverId = null;
    } finally {
      if (serverId) {
        await deleteApi(
          adminApi.context,
          `/api/admin/mcp-servers/${serverId}?force=true`,
          adminApi.token,
        ).catch(() => undefined);
      }
      await adminApi.context.dispose();
    }
  });
});
