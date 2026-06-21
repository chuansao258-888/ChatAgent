import {
  request as playwrightRequest,
  type APIRequestContext,
  type Page,
} from "@playwright/test";
import { expect, test } from "../fixtures";
import { getApi } from "../helpers/api";
import { loginUser } from "../helpers/auth";
import {
  getChatMessagesFromBrowser,
  startChatAndWaitForAssistant,
} from "../helpers/chat";
import {
  apiBaseUrl,
  e2eRunId,
  normalStorageStatePath,
  routingFixtureBaseUrl,
  routingFixtureLateDelayMs,
  routingProviderMode,
} from "../helpers/env";
import { readE2eUsers } from "../helpers/testUsers";

interface RoutingCandidateState {
  id: string;
  springClientKey: string;
  effectiveEnabled: boolean;
  registered: boolean;
  circuitState: string;
  consecutiveFailures: number;
}

interface RoutingState {
  firstPacketTimeoutSeconds: number;
  registeredModels: string[];
  candidates: RoutingCandidateState[];
}

interface RoutingFixtureCall {
  provider: "anthropic" | "deepseek";
  path: string;
  scenario: string;
  code: string | null;
  model: string | null;
  stream: boolean;
  thinking: string | null;
  at: string;
}

interface RoutingFixtureState {
  calls: RoutingFixtureCall[];
}

async function createAdminApi(): Promise<{
  context: APIRequestContext;
  token: string;
}> {
  const users = await readE2eUsers();
  const context = await playwrightRequest.newContext({ baseURL: apiBaseUrl });
  const auth = await loginUser(context, users.admin);
  return { context, token: auth.accessToken };
}

async function readRoutingState(api: {
  context: APIRequestContext;
  token: string;
}): Promise<RoutingState> {
  return getApi<RoutingState>(
    api.context,
    "/api/admin/chat-routing/state",
    api.token,
  );
}

async function resetRoutingFixture(request: APIRequestContext): Promise<void> {
  const health = await request.get(
    `${routingFixtureBaseUrl}/__routing-fixture/health`,
  );
  if (!health.ok()) {
    throw new Error(
      `Phase 7 routing fixture prerequisite failed: expected ${routingFixtureBaseUrl}/__routing-fixture/health to be reachable. Start it with "node ui/e2e/fixtures/routing-provider-fixture.mjs" and run the backend with CHATAGENT_DEEPSEEK_BASE_URL and CHATAGENT_ZAI_CODING_ANTHROPIC_BASE_URL pointing to that fixture.`,
    );
  }
  const reset = await request.post(
    `${routingFixtureBaseUrl}/__routing-fixture/reset`,
  );
  expect(reset.ok()).toBe(true);
}

async function readRoutingFixtureState(
  request: APIRequestContext,
): Promise<RoutingFixtureState> {
  const response = await request.get(
    `${routingFixtureBaseUrl}/__routing-fixture/state`,
  );
  expect(response.ok()).toBe(true);
  return (await response.json()) as RoutingFixtureState;
}

function code(prefix: string): string {
  const suffix = e2eRunId.replace(/[^a-zA-Z0-9]/g, "").slice(-8).toUpperCase();
  return `${prefix}-${suffix}`;
}

async function askForCode(page: Page, expectedCode: string) {
  // Protocol-only echo payload: this verifies first-packet routing, not dialogue quality.
  return startChatAndWaitForAssistant(
    page,
    `Please reply with exactly this confirmation code: ${expectedCode}. Do not explain.`,
    { timeoutMs: 180_000 },
  );
}

function callsForCode(state: RoutingFixtureState, expectedCode: string) {
  return state.calls.filter((call) => call.code === expectedCode);
}

async function assertFixturePrerequisites(api: {
  context: APIRequestContext;
  token: string;
}, testInfo: { attach: (name: string, options: { body: string; contentType: string }) => Promise<void> }) {
  const state = await readRoutingState(api);
  const primary = state.candidates.find((candidate) => candidate.id === "glm-5.2");
  const fallback = state.candidates.find(
    (candidate) => candidate.id === "deepseek-v4-flash",
  );

  expect(primary?.effectiveEnabled).toBe(true);
  expect(primary?.registered).toBe(true);
  expect(fallback?.effectiveEnabled).toBe(true);
  expect(fallback?.registered).toBe(true);
  expect(state.registeredModels).toEqual(
    expect.arrayContaining(["glm-5.2", "deepseek-v4-flash"]),
  );
  expect(state.firstPacketTimeoutSeconds).toBeLessThanOrEqual(3);
  await testInfo.attach("chat-routing-admin-state-before", {
    body: JSON.stringify(state, null, 2),
    contentType: "application/json",
  });
}

test.describe("@routing @routing-fixture first-packet routing and fallback", () => {
  test.use({ storageState: normalStorageStatePath });
  test.skip(
    routingProviderMode !== "fixture",
    "Set PLAYWRIGHT_ROUTING_PROVIDER_MODE=fixture for deterministic local provider fault injection.",
  );

  test("keeps primary first-packet success on the primary provider", async ({
    page,
    request,
  }, testInfo) => {
    test.setTimeout(240_000);
    const adminApi = await createAdminApi();
    try {
      await resetRoutingFixture(request);
      await assertFixturePrerequisites(adminApi, testInfo);

      const expectedCode = code("ASTER-PRIMARY");
      const evidence = await askForCode(page, expectedCode);
      expect(evidence.assistant.content).toContain(expectedCode);

      const fixtureState = await readRoutingFixtureState(request);
      const matchingCalls = callsForCode(fixtureState, expectedCode);
      expect(matchingCalls.some((call) => call.provider === "anthropic")).toBe(true);
      expect(matchingCalls.some((call) => call.provider === "deepseek")).toBe(false);
      await testInfo.attach("routing-primary-success-fixture-state", {
        body: JSON.stringify(fixtureState, null, 2),
        contentType: "application/json",
      });
    } finally {
      await adminApi.context.dispose();
    }
  });

  test("falls back without leaking failed primary first-packet content", async ({
    page,
    request,
  }, testInfo) => {
    test.setTimeout(420_000);
    const adminApi = await createAdminApi();
    try {
      await resetRoutingFixture(request);
      await assertFixturePrerequisites(adminApi, testInfo);

      const cases = [
        { scenario: "error", expectedCode: code("BIRCH-ERROR") },
        { scenario: "timeout", expectedCode: code("CEDAR-TIMEOUT") },
        { scenario: "no-content", expectedCode: code("DUNE-EMPTY") },
        { scenario: "late", expectedCode: code("EMBER-LATE") },
      ];

      for (const current of cases) {
        await test.step(`primary ${current.scenario} falls back cleanly`, async () => {
          const evidence = await askForCode(page, current.expectedCode);
          const renderedMessages = evidence.messages
            .map((message) => message.content ?? "")
            .join("\n");
          expect(evidence.assistant.content).toContain(current.expectedCode);
          expect(renderedMessages).not.toContain("PRIMARY-LATE-LEAK");
          if (current.scenario === "late") {
            await page.waitForTimeout(routingFixtureLateDelayMs + 1_500);
            const messagesAfterLateWindow = await getChatMessagesFromBrowser(
              page,
              evidence.sessionId,
            );
            expect(
              messagesAfterLateWindow
                .map((message) => message.content ?? "")
                .join("\n"),
            ).not.toContain("PRIMARY-LATE-LEAK");
          }
        });
        if (current !== cases[cases.length - 1]) {
          await test.step(`reset primary health after ${current.scenario}`, async () => {
            const resetCode = code(`ASTER-RESET-${current.scenario.toUpperCase()}`);
            const resetEvidence = await askForCode(page, resetCode);
            expect(resetEvidence.assistant.content.trim().length).toBeGreaterThan(0);
            expect(resetEvidence.assistant.content).not.toContain("AI_ERROR");
          });
        }
      }

      const fixtureState = await readRoutingFixtureState(request);
      for (const current of cases) {
        const matchingCalls = callsForCode(fixtureState, current.expectedCode);
        expect(
          matchingCalls.some(
            (call) =>
              call.provider === "anthropic" && call.scenario === current.scenario,
          ),
        ).toBe(true);
        expect(
          matchingCalls.some(
            (call) =>
              call.provider === "deepseek" &&
              call.thinking === "disabled" &&
              call.stream,
          ),
        ).toBe(true);
      }
      await testInfo.attach("routing-fallback-fixture-state", {
        body: JSON.stringify(fixtureState, null, 2),
        contentType: "application/json",
      });
      await testInfo.attach("chat-routing-admin-state-after", {
        body: JSON.stringify(await readRoutingState(adminApi), null, 2),
        contentType: "application/json",
      });
    } finally {
      await adminApi.context.dispose();
    }
  });
});

test.describe("@routing @routing-real real-provider routing smoke", () => {
  test.use({ storageState: normalStorageStatePath });
  test.skip(
    routingProviderMode !== "real",
    "Set PLAYWRIGHT_ROUTING_PROVIDER_MODE=real to run the real-provider stream smoke.",
  );

  test("streams a simple primary/fallback routed turn through configured real providers", async ({
    page,
  }, testInfo) => {
    test.setTimeout(240_000);
    const adminApi = await createAdminApi();
    try {
      const before = await readRoutingState(adminApi);
      const primary = before.candidates.find((candidate) => candidate.id === "glm-5.2");
      const fallback = before.candidates.find(
        (candidate) => candidate.id === "deepseek-v4-flash",
      );
      expect(primary?.effectiveEnabled).toBe(true);
      expect(primary?.registered).toBe(true);
      expect(fallback?.effectiveEnabled).toBe(true);
      expect(fallback?.registered).toBe(true);
      await testInfo.attach("chat-routing-real-state-before", {
        body: JSON.stringify(before, null, 2),
        contentType: "application/json",
      });

      const expectedCode = code("ASTER-REAL");
      const evidence = await askForCode(page, expectedCode);
      expect(evidence.assistant.content).toContain(expectedCode);
      expect(evidence.assistant.content).not.toContain("AI_ERROR");

      await testInfo.attach("chat-routing-real-state-after", {
        body: JSON.stringify(await readRoutingState(adminApi), null, 2),
        contentType: "application/json",
      });
    } finally {
      await adminApi.context.dispose();
    }
  });
});
