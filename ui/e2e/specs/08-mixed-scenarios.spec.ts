import {
  request as playwrightRequest,
  type APIRequestContext,
  type Browser,
  type BrowserContext,
  type Page,
  type TestInfo,
} from "@playwright/test";
import { expect, test } from "../fixtures";
import {
  deleteApi,
  getApi,
  postApi,
  postMultipartApi,
  putApi,
} from "../helpers/api";
import { loginThroughUi, loginUser } from "../helpers/auth";
import {
  sendMessage,
  startChatAndWaitForAssistant,
  waitForAssistantTurn,
  waitForInputReady,
  type ChatTurnEvidence,
} from "../helpers/chat";
import {
  cleanupGeneratedIntentArtifacts,
  cleanupStaleGeneratedIntentArtifacts,
  readActiveIntentRuntimeEvidence,
  readAssistantAllowedTools,
  readKnowledgeBaseBindingEvidence,
  readMemoryEvidence,
  readToolCallEvidence,
  setActiveIntentVersion,
  setAssistantAllowedTools,
  waitForKnowledgeBaseEvidence,
  waitForMemoryEvidence,
  waitForSessionFileEvidence,
  waitForToolCallEvidence,
  waitForTurnCompletion,
  type KnowledgeBaseEvidence,
  type ToolCallEvidence,
} from "../helpers/db";
import {
  apiBaseUrl,
  e2eRunId,
  liveWebSearchEnabled,
  normalStorageStatePath,
  uiBaseUrl,
} from "../helpers/env";
import {
  expectChineseDominant,
  expectEnglishDominant,
} from "../helpers/languageAssert";
import {
  classifyLiveWebSearchResultUrl,
  containsLiveWebSearchFixtureFingerprint,
  runLiveWebSearchPreflight,
  WEB_SEARCH_BACKEND_TOOL_NAME,
  WEB_SEARCH_MODEL_TOOL_NAME,
  type LiveWebSearchPreflightResult,
} from "../helpers/liveWebSearch";
import {
  createMixedScenarioRun,
  type MixedScenarioRun,
} from "../helpers/mixedScenario";
import { readE2eUsers } from "../helpers/testUsers";
import type { CitationMetadata } from "../../src/types";

const SYSTEM_ASSISTANT_ID = "3f9f84f7-2df0-4a5f-9c85-9f2d9b7aaf10";
const MCP_ENDPOINT_URL = "http://localhost:8090/mcp";
const INTENT_PREFIX = "E2E Intent Mixed ";
const KB_PREFIX = "E2E Mixed KB ";
const MCP_SLUG_PREFIX = "e2e_mixed_weather_";
const TURN_TIMEOUT_MS = 240_000;
const INGESTION_TIMEOUT_MS = 300_000;
const MIXED_JOURNEY_TIMEOUT_MS = 7_200_000;
const SESSION_FILE_TOOL_NAME = "SessionFileSearchTool";
const DATABASE_BACKEND_TOOL_NAME = "dataBaseTool";

interface AdminApi {
  context: APIRequestContext;
  token: string;
}

interface KnowledgeBaseListItem {
  id: string;
  name: string;
}

interface IntentTreeResponse {
  activeVersion?: number | null;
}

interface CreateIntentNodeResponse {
  nodeId: string;
}

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

interface MixedConversationDriver {
  readonly label: string;
  readonly page: Page;
  readonly testInfo: TestInfo;
  readonly prompts: string[];
  sessionId: string | null;
  turnCount: number;
  runTurn(
    prompt: string,
    expected: Array<string | RegExp>,
    options?: MixedTurnOptions,
  ): Promise<ChatTurnEvidence>;
}

interface MixedTurnOptions {
  forbidden?: Array<string | RegExp>;
  language?: "english" | "chinese";
  citation?: CitationExpectation;
  expectedTools?: string[];
  forbiddenTools?: string[];
  noToolCalls?: boolean;
}

interface CitationExpectation {
  minCount?: number;
  sourceType?: "KNOWLEDGE_BASE" | "SESSION_FILE";
  sourceId?: string;
  documentName?: string;
}

interface MixedPromptChainRecord {
  group: string;
  turn: number;
  basedOn: string;
  generatedPrompt: string;
}

interface LiveWebSearchAnswerSummary {
  turnId: string;
  status: "public-source" | "unavailable";
  publicUrls: Array<{ url: string; domain: string }>;
}

function suffix(): string {
  return e2eRunId.replace(/[^a-zA-Z0-9]/g, "").slice(-8).toUpperCase();
}

function generatedSlug(): string {
  return `${MCP_SLUG_PREFIX}${suffix().toLowerCase()}`;
}

function uniqueStrings(values: string[]): string[] {
  return [...new Set(values.filter(Boolean))];
}

async function assertDialogueRealism(
  testInfo: TestInfo,
  label: string,
  prompts: string[],
): Promise<void> {
  const shortTurns = prompts.filter((prompt) => prompt.trim().length <= 64);
  const mechanismMentions = prompts.filter((prompt) =>
    /\b(?:tool|database|knowledge base|mcp)\b/i.test(prompt),
  );
  const citationDirectives = prompts.filter((prompt) =>
    /\b(?:cite|citation|source)\b/i.test(prompt),
  );
  const evidence = {
    totalTurns: prompts.length,
    shortTurnCount: shortTurns.length,
    mechanismMentionCount: mechanismMentions.length,
    citationDirectiveCount: citationDirectives.length,
    shortTurns,
  };

  expect(evidence.totalTurns).toBeGreaterThanOrEqual(30);
  expect(evidence.shortTurnCount).toBeGreaterThanOrEqual(12);
  expect(evidence.mechanismMentionCount).toBe(0);
  expect(evidence.citationDirectiveCount).toBe(0);
  await testInfo.attach(`${label}-dialogue-realism`, {
    body: JSON.stringify(evidence, null, 2),
    contentType: "application/json",
  });
}

function expectContentMatch(content: string, expected: string | RegExp): void {
  if (typeof expected === "string") {
    expect(content).toContain(expected);
    return;
  }
  expect(content).toMatch(expected);
}

function expectContentNotMatch(content: string, forbidden: string | RegExp): void {
  if (typeof forbidden === "string") {
    expect(content).not.toContain(forbidden);
    return;
  }
  expect(content).not.toMatch(forbidden);
}

function summarizeForPromptChain(content: string): string {
  return content.replace(/\s+/g, " ").trim().slice(0, 240);
}

function generatePromptFromPrevious(
  driver: MixedConversationDriver,
  promptChain: MixedPromptChainRecord[],
  previous: ChatTurnEvidence,
  generatedPrompt: string,
): string {
  promptChain.push({
    group: driver.label,
    turn: driver.turnCount + 1,
    basedOn: summarizeForPromptChain(previous.assistant.content),
    generatedPrompt,
  });
  return generatedPrompt;
}

function extractHttpUrls(content: string): string[] {
  return [
    ...new Set(
      Array.from(content.matchAll(/https?:\/\/[^\s<>"']+/g), (match) =>
        match[0].replace(/[)\].,;!?]+$/g, ""),
      ),
    ),
  ];
}

function isUnavailableLiveAnswer(content: string): boolean {
  return /(?:unable|unavailable|could(?:n't| not)|no)\b.{0,80}\b(?:current|online|public|live|verify|verification|result|link)/i.test(
    content,
  );
}

function referencedCitationIndexes(content: string): number[] {
  return [
    ...new Set(
      Array.from(content.matchAll(/\[(\d+)\]/g), (match) => Number(match[1])),
    ),
  ].sort((left, right) => left - right);
}

function referencedCitationIndexesInOrder(content: string): number[] {
  return Array.from(content.matchAll(/\[(\d+)\]/g), (match) =>
    Number(match[1]),
  );
}

function expectValidReferencedIndexes(indexes: number[], citationCount: number) {
  expect(indexes.length).toBeGreaterThan(0);
  expect(Math.min(...indexes)).toBeGreaterThanOrEqual(1);
  expect(Math.max(...indexes)).toBeLessThanOrEqual(citationCount);
}

async function assertCitationRendering(
  page: Page,
  messageId: string,
  content: string,
  citations: CitationMetadata[],
) {
  const messageRoot = page.locator(`[data-chat-message-id='${messageId}']`);
  await expect(messageRoot).toHaveCount(1);

  const referencesInOrder = referencedCitationIndexesInOrder(content);
  const referencedIndexes = referencedCitationIndexes(content);
  expectValidReferencedIndexes(referencedIndexes, citations.length);

  const sourceCards = messageRoot.locator(
    `[id^='citation-source-${messageId}-']`,
  );
  await expect(sourceCards).toHaveCount(citations.length);
  expect(
    await sourceCards.evaluateAll((cards) =>
      cards.map((card) => ({
        index: card.getAttribute("data-citation-index"),
        documentName: card.getAttribute("data-document-name"),
      })),
    ),
  ).toEqual(
    citations.map((citation, index) => ({
      index: String(index + 1),
      documentName:
        citation.documentName || citation.documentId || "Unknown source",
    })),
  );

  const inlineTags = messageRoot.getByRole("button", {
    name: /^Citation \d+:/,
  });
  await expect(inlineTags).toHaveCount(referencesInOrder.length);

  await page.evaluate(() => {
    const original = Element.prototype.scrollIntoView;
    Object.defineProperty(window, "__e2eCitationTarget", {
      configurable: true,
      writable: true,
      value: null,
    });
    Element.prototype.scrollIntoView = function scrollIntoView(options) {
      (window as Window & { __e2eCitationTarget?: string | null })
        .__e2eCitationTarget = this.id;
      original.call(this, options);
    };
  });

  for (let index = 0; index < referencesInOrder.length; index += 1) {
    const inlineTag = inlineTags.nth(index);
    const citationIndex = referencesInOrder[index];
    await expect(inlineTag).toHaveAttribute(
      "data-citation-index",
      String(citationIndex),
    );
    await page.evaluate(() => {
      (window as Window & { __e2eCitationTarget?: string | null })
        .__e2eCitationTarget = null;
    });
    await inlineTag.click();
    await expect
      .poll(() =>
        page.evaluate(
          () =>
            (window as Window & { __e2eCitationTarget?: string | null })
              .__e2eCitationTarget,
        ),
      )
      .toBe(`citation-source-${messageId}-${citationIndex}`);
  }

  const layout = await page.evaluate(
    ({ targetMessageId }) => {
      const root = document.querySelector<HTMLElement>(
        `[data-chat-message-id='${targetMessageId}']`,
      );
      const cards = Array.from(
        root?.querySelectorAll<HTMLElement>(
          `[id^='citation-source-${targetMessageId}-']`,
        ) ?? [],
      );
      const tags = Array.from(
        root?.querySelectorAll<HTMLElement>(`button[data-citation-index]`) ??
          [],
      ).filter((tag) => tag.closest(".chat-markdown"));
      const hasOverlap = tags.some((tag, index) => {
        const left = tag.getBoundingClientRect();
        return tags.slice(index + 1).some((candidate) => {
          const right = candidate.getBoundingClientRect();
          return !(
            left.right <= right.left ||
            right.right <= left.left ||
            left.bottom <= right.top ||
            right.bottom <= left.top
          );
        });
      });
      return {
        hasOverlap,
        cardsFit: cards.every(
          (card) => card.scrollWidth <= card.clientWidth + 1,
        ),
        visibleText: cards.every(
          (card) =>
            card.textContent?.trim() &&
            getComputedStyle(card).visibility !== "hidden" &&
            getComputedStyle(card).display !== "none",
        ),
      };
    },
    { targetMessageId: messageId },
  );
  expect(layout).toEqual({
    hasOverlap: false,
    cardsFit: true,
    visibleText: true,
  });
}

async function createAdminApi(): Promise<AdminApi> {
  const users = await readE2eUsers();
  const context = await playwrightRequest.newContext({ baseURL: apiBaseUrl });
  const auth = await loginUser(context, users.admin);
  return { context, token: auth.accessToken };
}

async function readIntentTree(api: AdminApi): Promise<IntentTreeResponse> {
  return getApi<IntentTreeResponse>(
    api.context,
    "/api/admin/assistant/intent-tree",
    api.token,
  );
}

async function createIntentNode(
  api: AdminApi,
  request: Record<string, unknown>,
): Promise<string> {
  const response = await postApi<CreateIntentNodeResponse>(
    api.context,
    "/api/admin/assistant/intent-tree/nodes",
    request,
    api.token,
  );
  return response.nodeId;
}

async function publishIntentTree(api: AdminApi): Promise<number> {
  return postApi<number>(
    api.context,
    "/api/admin/assistant/intent-tree/publish",
    undefined,
    api.token,
  );
}

async function setAssistantBindings(api: AdminApi, knowledgeBaseIds: string[]) {
  await putApi<void>(
    api.context,
    "/api/admin/assistant/knowledge-bases",
    { knowledgeBaseIds: uniqueStrings(knowledgeBaseIds) },
    api.token,
  );
}

async function readAssistantBindings(api: AdminApi): Promise<string[]> {
  const bindings = await getApi<KnowledgeBaseListItem[]>(
    api.context,
    "/api/admin/assistant/knowledge-bases",
    api.token,
  );
  return bindings.map((binding) => binding.id);
}

async function createKnowledgeBase(
  api: AdminApi,
  name: string,
  marker: string,
  body: string,
): Promise<string> {
  const knowledgeBaseId = await postApi<string>(
    api.context,
    "/api/admin/knowledge-bases",
    { name, description: "Generated headed mixed-scenario evidence." },
    api.token,
  );
  await postMultipartApi<void>(
    api.context,
    `/api/admin/knowledge-bases/${knowledgeBaseId}/documents/upload`,
    {
      file: {
        name: `${name.replace(/[^a-zA-Z0-9]/g, "-")}.md`,
        mimeType: "text/markdown",
        buffer: Buffer.from(`# Mixed Scenario Evidence\n\n${body}\n\nMarker: ${marker}\n`),
      },
    },
    api.token,
  );
  await waitForKnowledgeBaseEvidence(
    knowledgeBaseId,
    [marker],
    (evidence) =>
      evidence.documents.length === 1 &&
      evidence.documents[0].parseStatus === "COMPLETED" &&
      evidence.documents[0].indexed &&
      evidence.documents[0].markers[marker],
    { timeoutMs: INGESTION_TIMEOUT_MS },
  );
  return knowledgeBaseId;
}

async function deleteGeneratedKnowledgeBases(api: AdminApi) {
  const knowledgeBases = await getApi<KnowledgeBaseListItem[]>(
    api.context,
    "/api/admin/knowledge-bases",
    api.token,
  );
  for (const knowledgeBase of knowledgeBases.filter((candidate) =>
    candidate.name.startsWith(KB_PREFIX),
  )) {
    await deleteApi<void>(
      api.context,
      `/api/admin/knowledge-bases/${knowledgeBase.id}`,
      api.token,
    );
  }
}

async function deleteGeneratedMcpServers(adminUser: {
  username: string;
  password: string;
}) {
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
  } finally {
    await context.dispose();
  }
}

async function createAndSyncLocalWeatherMcp(adminUser: {
  username: string;
  password: string;
}) {
  const context = await playwrightRequest.newContext({ baseURL: apiBaseUrl });
  try {
    const auth = await loginUser(context, adminUser);
    const serverId = await postApi<string>(
      context,
      "/api/admin/mcp-servers",
      {
        slug: generatedSlug(),
        name: "E2E Mixed Weather MCP",
        description: "Generated local MCP server for mixed headed E2E.",
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

    const activeTools = syncResult.activeTools;
    const currentDateTimeTool = activeTools.find(
      (tool) => tool.remoteOriginalName === "get_current_datetime",
    )?.exposedModelName;
    const convertTimeTool = activeTools.find(
      (tool) => tool.remoteOriginalName === "convert_time",
    )?.exposedModelName;
    if (!currentDateTimeTool || !convertTimeTool) {
      throw new Error("Synced MCP tools did not include both time tools.");
    }

    const optionalTools = await getApi<ToolVO[]>(
      context,
      "/api/tools",
      auth.accessToken,
    );
    for (const tool of activeTools) {
      expect(
        optionalTools.some((candidate) => candidate.name === tool.exposedModelName),
      ).toBe(true);
    }

    return {
      serverId,
      serverSlug: syncResult.server.slug,
      activeTools,
      currentDateTimeTool,
      convertTimeTool,
    };
  } finally {
    await context.dispose();
  }
}

async function openNormalChat(
  browser: Browser,
): Promise<{ context: BrowserContext; page: Page }> {
  const users = await readE2eUsers();
  const context = await browser.newContext({
    storageState: normalStorageStatePath,
    baseURL: uiBaseUrl,
    viewport: { width: 1366, height: 900 },
  });
  const page = await context.newPage();
  await loginThroughUi(page, users.normal, normalStorageStatePath);
  return { context, page };
}

async function assertCitationExpectation(
  page: Page,
  evidence: ChatTurnEvidence,
  expectation: CitationExpectation,
) {
  const citations = evidence.assistant.metadata?.citations ?? [];
  expect(citations.length).toBeGreaterThanOrEqual(expectation.minCount ?? 1);
  if (expectation.sourceType) {
    expect(
      citations.every((citation) => citation.sourceType === expectation.sourceType),
    ).toBe(true);
  }
  if (expectation.sourceId) {
    expect(
      citations.some((citation) => citation.sourceId === expectation.sourceId),
    ).toBe(true);
  }
  if (expectation.documentName) {
    expect(
      citations.some(
        (citation) => citation.documentName === expectation.documentName,
      ),
    ).toBe(true);
  }
  await assertCitationRendering(
    page,
    evidence.assistant.id,
    evidence.assistant.content,
    citations,
  );
}

async function assertToolEvidence(
  sessionId: string,
  turnId: string,
  options: MixedTurnOptions,
): Promise<ToolCallEvidence | null> {
  let evidence: ToolCallEvidence | null = null;
  if (options.expectedTools?.length) {
    evidence = await waitForToolCallEvidence(
      sessionId,
      turnId,
      options.expectedTools,
      { timeoutMs: TURN_TIMEOUT_MS },
    );
  }
  if (options.noToolCalls || options.forbiddenTools?.length) {
    evidence = evidence ?? (await readToolCallEvidence(sessionId, turnId));
  }
  if (options.noToolCalls) {
    expect(evidence?.calls ?? []).toEqual([]);
  }
  for (const forbiddenTool of options.forbiddenTools ?? []) {
    expect(evidence?.calls.some((call) => call.name === forbiddenTool)).toBe(false);
    expect(
      evidence?.responses.some((response) => response.name === forbiddenTool),
    ).toBe(false);
  }
  return evidence;
}

async function assertLiveWebSearchAnswerEvidence(
  testInfo: TestInfo,
  label: string,
  evidence: ChatTurnEvidence,
): Promise<LiveWebSearchAnswerSummary> {
  if (!evidence.user.turnId) {
    throw new Error(`${label} live web-search turn has no persisted turnId.`);
  }
  expect(containsLiveWebSearchFixtureFingerprint(evidence.assistant.content))
    .toBe(false);

  const toolEvidence = await readToolCallEvidence(
    evidence.sessionId,
    evidence.user.turnId,
  );
  expect(
    toolEvidence.calls.some((call) => call.name === WEB_SEARCH_MODEL_TOOL_NAME),
  ).toBe(true);

  const checkedUrls = extractHttpUrls(evidence.assistant.content).map((url) => ({
    url,
    check: classifyLiveWebSearchResultUrl(url),
  }));
  const rejectedUrls = checkedUrls.filter((candidate) => !candidate.check.ok);
  expect(
    rejectedUrls,
    "Live web-search answer must not include fixture, localhost, private, or reserved test URLs.",
  ).toEqual([]);
  const publicUrls = checkedUrls
    .filter((candidate) => candidate.check.ok)
    .map((candidate) => ({
      url: candidate.url,
      domain: candidate.check.domain ?? "",
    }));
  const unavailable = isUnavailableLiveAnswer(evidence.assistant.content);
  if (publicUrls.length === 0) {
    expect(
      unavailable,
      "Live web-search answer must either include a public URL or explicitly say current online verification was unavailable.",
    ).toBe(true);
  }

  const summary: LiveWebSearchAnswerSummary = {
    turnId: evidence.user.turnId,
    status: publicUrls.length > 0 ? "public-source" : "unavailable",
    publicUrls,
  };
  await testInfo.attach(`${label}-live-web-search-answer-evidence`, {
    body: JSON.stringify(
      {
        ...summary,
        toolCalls: toolEvidence.calls.map((call) => call.name),
      },
      null,
      2,
    ),
    contentType: "application/json",
  });
  return summary;
}

async function runLiveWebSearchSegment(
  driver: MixedConversationDriver,
  testInfo: TestInfo,
  previousTurn: ChatTurnEvidence,
  forbidden: Array<string | RegExp>,
): Promise<void> {
  const promptChain: MixedPromptChainRecord[] = [];
  const answerSummaries: LiveWebSearchAnswerSummary[] = [];

  const firstPrompt = generatePromptFromPrevious(
    driver,
    promptChain,
    previousTurn,
    "Before I close the handoff, can you check what's current online about OpenAI model news and give me the link you used?",
  );
  const firstTurn = await driver.runTurn(
    firstPrompt,
    [/OpenAI|model|news|release|update|current|online|unable|verify/i],
    {
      forbidden,
      expectedTools: [WEB_SEARCH_MODEL_TOOL_NAME],
    },
  );
  answerSummaries.push(
    await assertLiveWebSearchAnswerEvidence(
      testInfo,
      `${driver.label}-turn-${driver.turnCount}`,
      firstTurn,
    ),
  );

  const secondPrompt = generatePromptFromPrevious(
    driver,
    promptChain,
    firstTurn,
    "Can you check one more current online detail from that update, like the date or headline I should mention?",
  );
  const secondTurn = await driver.runTurn(
    secondPrompt,
    [/OpenAI|date|headline|model|release|update|current|online|unable|verify/i],
    {
      forbidden,
      expectedTools: [WEB_SEARCH_MODEL_TOOL_NAME],
    },
  );
  answerSummaries.push(
    await assertLiveWebSearchAnswerEvidence(
      testInfo,
      `${driver.label}-turn-${driver.turnCount}`,
      secondTurn,
    ),
  );

  await testInfo.attach(`${driver.label}-live-web-search-prompt-chain`, {
    body: JSON.stringify({ promptChain, answerSummaries }, null, 2),
    contentType: "application/json",
  });
}

function createMixedDriver(
  label: string,
  page: Page,
  testInfo: TestInfo,
  scenarioRun?: MixedScenarioRun,
): MixedConversationDriver {
  let previousAssistantContent = "";
  return {
    label,
    page,
    testInfo,
    prompts: [],
    sessionId: null,
    turnCount: 0,
    async runTurn(prompt, expected, options = {}) {
      const renderedPrompt =
        scenarioRun?.renderPrompt(label, this.turnCount + 1, prompt) ?? prompt;
      this.prompts.push(renderedPrompt);
      const evidence = this.sessionId
        ? await continueConversation(page, this.sessionId, renderedPrompt)
        : await startChatAndWaitForAssistant(page, renderedPrompt, {
            mode: "REACT",
            timeoutMs: TURN_TIMEOUT_MS,
          });
      this.sessionId = evidence.sessionId;
      this.turnCount += 1;
      if (!evidence.user.turnId) {
        throw new Error(`${label} turn ${this.turnCount} has no persisted turnId.`);
      }
      await waitForTurnCompletion(evidence.sessionId, evidence.user.turnId);
      expect(evidence.user.metadata?.executionMode).toBe("REACT");
      for (const required of expected) {
        expectContentMatch(evidence.assistant.content, required);
      }
      for (const forbidden of options.forbidden ?? []) {
        expectContentNotMatch(evidence.assistant.content, forbidden);
      }
      if (options.language === "chinese") {
        expectChineseDominant(evidence.assistant.content);
      } else {
        expectEnglishDominant(evidence.assistant.content);
      }
      expect(evidence.assistant.content.trim()).not.toEqual(
        previousAssistantContent.trim(),
      );
      previousAssistantContent = evidence.assistant.content;

      const toolEvidence = await assertToolEvidence(
        evidence.sessionId,
        evidence.user.turnId,
        options,
      );
      if (toolEvidence) {
        await testInfo.attach(`${label}-turn-${this.turnCount}-tool-evidence`, {
          body: JSON.stringify(toolEvidence, null, 2),
          contentType: "application/json",
        });
      }
      if (options.citation) {
        await assertCitationExpectation(page, evidence, options.citation);
      }

      const message = page.locator(
        `[data-chat-message-id="${evidence.assistant.id}"]`,
      );
      await expect(message).toBeVisible();
      await expect(message.locator(".chat-markdown")).toHaveText(/\S/);
      return evidence;
    },
  };
}

async function continueConversation(
  page: Page,
  sessionId: string,
  prompt: string,
): Promise<ChatTurnEvidence> {
  await sendMessage(page, prompt);
  const evidence = await waitForAssistantTurn(page, sessionId, prompt, {
    timeoutMs: TURN_TIMEOUT_MS,
  });
  await waitForInputReady(page);
  return evidence;
}

async function uploadSessionFile(
  page: Page,
  sessionId: string,
  filename: string,
  body: string,
  markers: string[],
) {
  await page.locator("input[type='file']").setInputFiles({
    name: filename,
    mimeType: "text/markdown",
    buffer: Buffer.from(body),
  });
  await waitForSessionFileEvidence(
    sessionId,
    filename,
    markers,
    (evidence) =>
      evidence.parseStatus === "COMPLETED" &&
      markers.every((marker) => evidence.markers[marker]),
    { timeoutMs: INGESTION_TIMEOUT_MS },
  );
}

function markerAppearsInSummaryOrItem(
  evidence: Awaited<ReturnType<typeof readMemoryEvidence>>,
  marker: string,
): boolean {
  return Boolean(
    evidence.summary?.markers[marker] ||
      evidence.summary?.segments.some((segment) => segment.markers[marker]) ||
      evidence.items.some(
        (item) =>
          item.status === "active" &&
          item.indexStatus === "indexed" &&
          item.markers[marker],
      ),
  );
}

async function attachMemoryEvidence(
  testInfo: TestInfo,
  label: string,
  username: string,
  sessionId: string,
  markers: string[],
) {
  const evidence = await waitForMemoryEvidence(
    username,
    sessionId,
    markers,
    (candidate) =>
      Boolean(candidate.summary) &&
      markers.some((marker) => markerAppearsInSummaryOrItem(candidate, marker)),
    { timeoutMs: INGESTION_TIMEOUT_MS },
  );
  await testInfo.attach(`${label}-memory-evidence`, {
    body: JSON.stringify(evidence, null, 2),
    contentType: "application/json",
  });
}

function expectCitationDocumentsMatchEvidence(
  citations: CitationMetadata[],
  evidence: KnowledgeBaseEvidence,
) {
  const documentsById = new Map(
    evidence.documents.map((document) => [document.id, document]),
  );
  for (const citation of citations) {
    expect(citation.documentId).toBeTruthy();
    const document = citation.documentId
      ? documentsById.get(citation.documentId)
      : null;
    expect(document).toBeTruthy();
    expect(citation.documentName).toBe(document?.filename);
    expect(typeof citation.chunkIndex).toBe("number");
    expect(citation.snippet?.trim().length).toBeGreaterThan(0);
  }
}

test.describe("@mixed-dialogue long realistic mixed scenarios", () => {
  test("keeps KB, tools, MCP, memory, language, and intent boundaries coherent across two 30-turn groups", async ({
    browser,
  }, testInfo) => {
    test.setTimeout(MIXED_JOURNEY_TIMEOUT_MS);
    const users = await readE2eUsers();
    const runSuffix = suffix();
    const mixedScenario = await createMixedScenarioRun(e2eRunId, runSuffix);
    const { profile } = mixedScenario;
    const adminApi = await createAdminApi();
    let originalActiveVersion: number | null = null;
    let originalBindings: string[] = [];
    let originalAllowedTools: string[] = [];
    let baselineCaptured = false;
    const generatedVersions: number[] = [];
    const generatedKnowledgeBaseIds: string[] = [];
    let groupAContext: BrowserContext | null = null;
    let groupBContext: BrowserContext | null = null;
    let groupADriver: MixedConversationDriver | null = null;
    let groupBDriver: MixedConversationDriver | null = null;
    let scenarioCompleted = false;
    let cleanupError: Error | null = null;

    const groupAProject = `${profile.groupA.project}-${runSuffix}`;
    const groupAOldRoom = `${profile.groupA.oldRoom}-${runSuffix}`;
    const groupAFileRoom = `${profile.groupA.fileRoom}-${runSuffix}`;
    const groupACurrentRoom = `${profile.groupA.currentRoom}-${runSuffix}`;
    const groupAOwnerInitial = `${profile.groupA.ownerInitial}-${runSuffix}`;
    const groupAOwnerCurrent = `${profile.groupA.ownerCurrent}-${runSuffix}`;
    const groupAKbMarker = `MIXED-A-KB-${runSuffix}`;
    const groupADecoyMarker = `MIXED-A-DECOY-${runSuffix}`;
    const groupAUnboundMarker = `MIXED-A-UNBOUND-${runSuffix}`;
    const groupAFileMarker = `MIXED-A-FILE-${runSuffix}`;
    const groupAFileName = `${profile.groupA.fileSlug}-${runSuffix}.md`;
    const rootAName = `${INTENT_PREFIX}${runSuffix} ${profile.groupA.desk}`;
    const rootBName = `${INTENT_PREFIX}${runSuffix} ${profile.groupA.archive}`;
    const categoryAName = `${INTENT_PREFIX}${runSuffix} ${profile.groupA.workbench}`;
    const launchNotesName = `${INTENT_PREFIX}${runSuffix} ${profile.groupA.notes}`;
    const roomCardName = `${INTENT_PREFIX}${runSuffix} ${profile.groupA.fileLabel}`;

    const groupBProject = `${profile.groupB.project}-${runSuffix}`;
    const groupBOldLocker = `${profile.groupB.oldLocker}-${runSuffix}`;
    const groupBCurrentLocker = `${profile.groupB.currentLocker}-${runSuffix}`;
    const groupBOwnerInitial = `${profile.groupB.ownerInitial}-${runSuffix}`;
    const groupBOwnerCurrent = `${profile.groupB.ownerCurrent}-${runSuffix}`;
    const groupBKbMarker = `MIXED-B-KB-${runSuffix}`;
    const groupBFileMarker = `MIXED-B-FILE-${runSuffix}`;
    const groupBFileName = `${profile.groupB.fileSlug}-${runSuffix}.md`;
    const ambiguousPrompt = profile.ambiguousPrompt;
    let liveWebSearchPreflight: LiveWebSearchPreflightResult | null = null;

    try {
      await deleteGeneratedKnowledgeBases(adminApi);
      await deleteGeneratedMcpServers(users.admin);
      await cleanupStaleGeneratedIntentArtifacts(
        SYSTEM_ASSISTANT_ID,
        INTENT_PREFIX,
      );
      const originalTree = await readIntentTree(adminApi);
      originalActiveVersion = originalTree.activeVersion ?? null;
      originalBindings = await readAssistantBindings(adminApi);
      originalAllowedTools = (
        await readAssistantAllowedTools(SYSTEM_ASSISTANT_ID)
      ).allowedTools;
      baselineCaptured = true;
      if (liveWebSearchEnabled) {
        liveWebSearchPreflight = await runLiveWebSearchPreflight(
          adminApi.context,
          adminApi.token,
        );
        await testInfo.attach("mixed-live-web-search-preflight", {
          body: JSON.stringify(liveWebSearchPreflight, null, 2),
          contentType: "application/json",
        });
      } else {
        await testInfo.attach("mixed-live-web-search-mode", {
          body: JSON.stringify(
            {
              enabled: false,
              status: "skipped",
              reason: "PLAYWRIGHT_LIVE_WEB_SEARCH is not true.",
            },
            null,
            2,
          ),
          contentType: "application/json",
        });
      }
      const syncedMcp = await createAndSyncLocalWeatherMcp(users.admin);
      await testInfo.attach("mixed-mcp-sync-evidence", {
        body: JSON.stringify(
          {
            serverId: syncedMcp.serverId,
            serverSlug: syncedMcp.serverSlug,
            activeTools: syncedMcp.activeTools.map((tool) => ({
              remoteOriginalName: tool.remoteOriginalName,
              exposedModelName: tool.exposedModelName,
            })),
          },
          null,
          2,
        ),
        contentType: "application/json",
      });

      const groupATargetKbId = await createKnowledgeBase(
        adminApi,
        `${KB_PREFIX}${runSuffix} Ridgewater Target`,
        groupAKbMarker,
        `For ${groupAProject} operations handoff, the verification code is ${groupAKbMarker}. ` +
          `The escalation owner in the launch note is Ada Cho.`,
      );
      const groupADecoyKbId = await createKnowledgeBase(
        adminApi,
        `${KB_PREFIX}${runSuffix} Ridgewater Decoy`,
        groupADecoyMarker,
        `For unrelated Ridgewater archive training, the obsolete code is ${groupADecoyMarker}.`,
      );
      const groupAUnboundKbId = await createKnowledgeBase(
        adminApi,
        `${KB_PREFIX}${runSuffix} Nebula Unbound`,
        groupAUnboundMarker,
        `The independent archive code recorded for the Nebula Cedar dossier is ${groupAUnboundMarker}.`,
      );
      const groupBTargetKbId = await createKnowledgeBase(
        adminApi,
        `${KB_PREFIX}${runSuffix} Harborlight Default`,
        groupBKbMarker,
        `For ${groupBProject} vendor readiness, the readiness code is ${groupBKbMarker}. ` +
          `The vendor contact in the note is Mira Sol.`,
      );
      generatedKnowledgeBaseIds.push(
        groupATargetKbId,
        groupADecoyKbId,
        groupAUnboundKbId,
        groupBTargetKbId,
      );
      await setAssistantBindings(adminApi, [
        ...originalBindings,
        ...generatedKnowledgeBaseIds,
      ]);
      await setAssistantAllowedTools(
        SYSTEM_ASSISTANT_ID,
        uniqueStrings([
          ...originalAllowedTools,
          SESSION_FILE_TOOL_NAME,
          DATABASE_BACKEND_TOOL_NAME,
          ...(liveWebSearchPreflight ? [WEB_SEARCH_BACKEND_TOOL_NAME] : []),
          ...syncedMcp.activeTools.map((tool) => tool.exposedModelName),
        ]),
      );

      const groupAKbQuery =
        `Do you remember the ${groupAProject} handoff code and escalation contact?`;
      const groupAUnboundQuery =
        "What was the Nebula Cedar archive code again?";
      const groupAFileQuery =
        "What room and door phrase are on the card I attached?";
      const groupATimePrompt =
        "What time is it in Singapore right now?";
      const groupAConvertPrompt =
        "What was 9:30 AM in Singapore on June 19 in New York?";

      const rootAId = await createIntentNode(adminApi, {
        nodeLevel: "DOMAIN",
        name: rootAName,
        description: "Generated mixed E2E active-intent domain.",
        examples: [
          ambiguousPrompt,
          groupAKbQuery,
          groupAFileQuery,
          groupATimePrompt,
          groupAConvertPrompt,
        ],
        enabled: true,
        sortOrder: 1500,
      });
      const categoryAId = await createIntentNode(adminApi, {
        parentId: rootAId,
        nodeLevel: "CATEGORY",
        name: categoryAName,
        examples: [groupAKbQuery, groupAFileQuery, groupATimePrompt],
        enabled: true,
        sortOrder: 0,
      });
      const kbTopicId = await createIntentNode(adminApi, {
        parentId: categoryAId,
        nodeLevel: "TOPIC",
        name: launchNotesName,
        examples: [groupAKbQuery],
        intentKind: "KB",
        scopePolicy: "STRICT",
        enabled: true,
        sortOrder: 0,
      });
      await putApi<void>(
        adminApi.context,
        `/api/admin/assistant/intent-tree/nodes/${kbTopicId}/knowledge-bases`,
        { knowledgeBaseIds: [groupATargetKbId] },
        adminApi.token,
      );
      await createIntentNode(adminApi, {
        parentId: categoryAId,
        nodeLevel: "TOPIC",
        name: roomCardName,
        examples: [groupAFileQuery],
        intentKind: "TOOL",
        allowedTools: [SESSION_FILE_TOOL_NAME],
        enabled: true,
        sortOrder: 1,
      });
      await createIntentNode(adminApi, {
        parentId: categoryAId,
        nodeLevel: "TOPIC",
        name: `${INTENT_PREFIX}${runSuffix} Time Questions`,
        examples: [groupATimePrompt, groupAConvertPrompt],
        intentKind: "TOOL",
        allowedTools: [
          syncedMcp.currentDateTimeTool,
          syncedMcp.convertTimeTool,
        ],
        enabled: true,
        sortOrder: 2,
      });
      const rootBId = await createIntentNode(adminApi, {
        nodeLevel: "DOMAIN",
        name: rootBName,
        description: "Generated mixed E2E clarification alternative.",
        examples: [ambiguousPrompt],
        enabled: true,
        sortOrder: 1501,
      });
      await createIntentNode(adminApi, {
        parentId: rootBId,
        nodeLevel: "CATEGORY",
        name: `${INTENT_PREFIX}${runSuffix} Archive Follow-up`,
        examples: [ambiguousPrompt],
        enabled: true,
        sortOrder: 0,
      });
      const rootCId = await createIntentNode(adminApi, {
        nodeLevel: "DOMAIN",
        name: `${INTENT_PREFIX}${runSuffix} Assistant Default Archives`,
        description: "Generated mixed E2E fallback KB domain.",
        examples: [groupAUnboundQuery],
        enabled: true,
        sortOrder: 1502,
      });
      const categoryCId = await createIntentNode(adminApi, {
        parentId: rootCId,
        nodeLevel: "CATEGORY",
        name: `${INTENT_PREFIX}${runSuffix} Unbound Lookup`,
        examples: [groupAUnboundQuery],
        enabled: true,
        sortOrder: 0,
      });
      await createIntentNode(adminApi, {
        parentId: categoryCId,
        nodeLevel: "TOPIC",
        name: `${INTENT_PREFIX}${runSuffix} Assistant Bound Archive`,
        examples: [groupAUnboundQuery],
        intentKind: "KB",
        scopePolicy: "FALLBACK_ALLOWED",
        enabled: true,
        sortOrder: 0,
      });
      const activeVersion = await publishIntentTree(adminApi);
      generatedVersions.push(activeVersion);
      const activeRuntime = await readActiveIntentRuntimeEvidence(
        SYSTEM_ASSISTANT_ID,
      );
      expect(activeRuntime.activeVersion).toBe(activeVersion);
      await testInfo.attach("mixed-active-intent-runtime-evidence", {
        body: JSON.stringify(activeRuntime, null, 2),
        contentType: "application/json",
      });

      const groupA = await openNormalChat(browser);
      groupAContext = groupA.context;
      groupADriver = createMixedDriver(
        "intent-tree-group",
        groupA.page,
        testInfo,
        mixedScenario,
      );

      await groupADriver.runTurn(
        `I'm running ${groupAProject} with ${groupAOwnerInitial}. We started in ${groupAOldRoom}. Can you give me a quick prep list?`,
        [/checklist|confirm|owner|agenda|room|review/i],
        { forbidden: [groupBProject], noToolCalls: true },
      );
      await groupADriver.runTurn(
        "Who's on point?",
        [groupAOwnerInitial],
        { forbidden: [groupBOwnerInitial], noToolCalls: true },
      );
      await groupADriver.runTurn(
        "先换个话题，三条待办怎么排？",
        [/待办|优先|清楚|会议|准备/],
        {
          forbidden: [groupAProject, groupAOldRoom, groupAOwnerInitial],
          language: "chinese",
          noToolCalls: true,
        },
      );
      await groupADriver.runTurn(groupAKbQuery, [groupAKbMarker, "Ada"], {
        forbidden: [groupADecoyMarker, groupBProject],
        citation: {
          minCount: 1,
          sourceType: "KNOWLEDGE_BASE",
          sourceId: groupATargetKbId,
        },
      });
      await groupADriver.runTurn(
        "My vegetables keep going soggy. Any quick fix?",
        [/roast|heat|dry|pan|space|moisture|temperature|oil/i],
        {
          forbidden: [groupAKbMarker, groupADecoyMarker, groupAUnboundMarker],
          noToolCalls: true,
        },
      );

      if (!groupADriver.sessionId) {
        throw new Error("Intent-tree group did not start a session.");
      }
      await uploadSessionFile(
        groupA.page,
        groupADriver.sessionId,
        groupAFileName,
        `# Room Card\n\nThe room card lists ${groupAFileRoom}. The door phrase is ${groupAFileMarker}.\n`,
        [groupAFileRoom, groupAFileMarker],
      );
      await groupADriver.runTurn(groupAFileQuery, [groupAFileRoom, groupAFileMarker], {
        citation: {
          minCount: 1,
          sourceType: "SESSION_FILE",
          documentName: groupAFileName,
        },
        expectedTools: [SESSION_FILE_TOOL_NAME],
      });
      await groupADriver.runTurn(
        `No, that card is old. We're in ${groupACurrentRoom} now.`,
        [groupACurrentRoom],
        { forbidden: [groupBProject], noToolCalls: true },
      );
      await groupADriver.runTurn(
        "Which room goes on the invite?",
        [groupACurrentRoom],
        { forbidden: [groupAOldRoom, groupAFileMarker], noToolCalls: true },
      );
      await groupADriver.runTurn(groupATimePrompt, [/Asia\/Singapore|Singapore|SGT|UTC\+8/i], {
        expectedTools: [syncedMcp.currentDateTimeTool],
      });
      await groupADriver.runTurn(
        "Generic question: how do I make any handoff easy to scan?",
        [/handoff|scan|bullet|heading|owner|action|deadline/i],
        { forbidden: [groupAKbMarker, groupAFileMarker], noToolCalls: true },
      );
      const unboundBinding = await readKnowledgeBaseBindingEvidence(groupAUnboundKbId);
      expect(unboundBinding.assistantBindingCount).toBeGreaterThan(0);
      expect(unboundBinding.intentBindings).toEqual([]);
      await groupADriver.runTurn(groupAUnboundQuery, [groupAUnboundMarker], {
        citation: {
          minCount: 1,
          sourceType: "KNOWLEDGE_BASE",
          sourceId: groupAUnboundKbId,
        },
      });
      await testInfo.attach("mixed-active-tree-unbound-kb-evidence", {
        body: JSON.stringify(unboundBinding, null, 2),
        contentType: "application/json",
      });
      await groupADriver.runTurn(groupAConvertPrompt, [/America\/New_York|New York/i, /21:30|9:30\s*PM/i], {
        expectedTools: [syncedMcp.convertTimeTool],
      });
      await groupADriver.runTurn(
        "Quick recap: owner and room?",
        [groupAOwnerInitial, groupACurrentRoom],
        { forbidden: [groupAOldRoom, groupAFileRoom], noToolCalls: true },
      );
      await groupADriver.runTurn(
        "下午犯困了，怎么缓一下？",
        [/呼吸|走动|伸展|喝水|休息|注意力/],
        {
          language: "chinese",
          forbidden: [groupAProject, groupACurrentRoom, groupAOwnerInitial],
          noToolCalls: true,
        },
      );
      await groupADriver.runTurn(ambiguousPrompt, [rootAName, rootBName, /choose|confirm|reply/i], {
        noToolCalls: true,
      });
      await groupADriver.runTurn(
        "Not sure yet.",
        [rootAName, rootBName, /number|name|choose|identify|reply/i],
        { noToolCalls: true },
      );
      await groupADriver.runTurn(
        `Let's use ${rootAName}.`,
        [launchNotesName, roomCardName, /number|name|choose|reply/i],
        { forbidden: [rootBName], noToolCalls: true },
      );
      await groupADriver.runTurn(
        "How do I keep the handoff clear?",
        [/handoff|clear|owner|next|context|status/i],
        { forbidden: [rootBName], noToolCalls: true },
      );
      await groupADriver.runTurn(
        `${groupAOwnerInitial} handed this to ${groupAOwnerCurrent}. First move?`,
        [groupAOwnerCurrent, /confirm|review|align|agenda|status|handoff/i],
        { noToolCalls: true },
      );
      await groupADriver.runTurn(
        "So who's got it now, and where are we meeting?",
        [groupAOwnerCurrent, groupACurrentRoom],
        { forbidden: [groupAOwnerInitial, groupAOldRoom, groupAFileRoom], noToolCalls: true },
      );
      await groupADriver.runTurn(
        "How do I show two time zones without clutter?",
        [/timezone|time zone|local|agenda|label|city|IANA/i],
        { forbidden: [groupAKbMarker, groupAFileMarker], noToolCalls: true },
      );
      await groupADriver.runTurn(
        "What was the handoff code again?",
        [groupAKbMarker],
        {
          forbidden: [groupADecoyMarker],
          citation: {
            minCount: 1,
            sourceType: "KNOWLEDGE_BASE",
            sourceId: groupATargetKbId,
          },
        },
      );
      await groupADriver.runTurn(
        "What did the old room card say?",
        [groupAFileRoom, groupAFileMarker],
        {
          citation: {
            minCount: 1,
            sourceType: "SESSION_FILE",
            documentName: groupAFileName,
          },
          expectedTools: [SESSION_FILE_TOOL_NAME],
        },
      );
      await groupADriver.runTurn(
        "Old card versus current room?",
        [groupAFileRoom, groupACurrentRoom],
        { forbidden: [groupAOldRoom], noToolCalls: true },
      );
      await groupADriver.runTurn(
        "Do we know why it moved?",
        [/do not|don't|avoid|confirm|verify|facilities|reason|unknown/i],
        { forbidden: [groupAKbMarker, groupAUnboundMarker], noToolCalls: true },
      );
      await groupADriver.runTurn(
        "提醒一句：别临时加没确认的信息。",
        [/确认|未经|信息|临时|提醒/],
        {
          language: "chinese",
          forbidden: [groupAKbMarker, groupAFileMarker],
          noToolCalls: true,
        },
      );
      await groupADriver.runTurn(
        "Current owner, room, and biggest readiness risk?",
        [groupAOwnerCurrent, groupACurrentRoom, /risk|ready|readiness|check/i],
        { forbidden: [groupAOwnerInitial, groupAOldRoom], noToolCalls: true },
      );
      const groupAQaTurn = await groupADriver.runTurn(
        "How much Q&A fits in fifteen minutes?",
        [/question|Q&A|agenda|time|reserve|buffer/i],
        { forbidden: [groupBKbMarker, groupBProject], noToolCalls: true },
      );
      if (liveWebSearchPreflight) {
        await runLiveWebSearchSegment(
          groupADriver,
          testInfo,
          groupAQaTurn,
          [groupAKbMarker, groupAFileMarker, groupBProject, groupBKbMarker],
        );
      }
      await groupADriver.runTurn(
        "Can you wrap this up with the current owner and room, plus the two codes we checked?",
        [groupAProject, groupAOwnerCurrent, groupACurrentRoom, groupAKbMarker, groupAFileMarker],
        {
          forbidden: [groupADecoyMarker, groupBProject],
          citation: { minCount: 2 },
        },
      );
      await groupADriver.runTurn(
        "What am I most likely to mix up?",
        [/old|current|room|owner|source|mix/i],
        { forbidden: [groupBProject, groupBKbMarker], noToolCalls: true },
      );
      expect(groupADriver.turnCount).toBeGreaterThanOrEqual(30);
      await assertDialogueRealism(testInfo, "intent-tree-group", groupADriver.prompts);
      await attachMemoryEvidence(
        testInfo,
        "intent-tree-group",
        users.normal.username,
        groupADriver.sessionId!,
        [groupAProject, groupAOwnerCurrent, groupACurrentRoom],
      );

      await setActiveIntentVersion(SYSTEM_ASSISTANT_ID, null);
      const noIntentRuntime = await readActiveIntentRuntimeEvidence(
        SYSTEM_ASSISTANT_ID,
      );
      expect(noIntentRuntime.activeVersion).toBeNull();
      expect(noIntentRuntime.nodes).toEqual([]);
      await testInfo.attach("mixed-no-intent-runtime-evidence", {
        body: JSON.stringify(noIntentRuntime, null, 2),
        contentType: "application/json",
      });
      const noIntentBinding = await readKnowledgeBaseBindingEvidence(groupBTargetKbId);
      expect(noIntentBinding.assistantBindingCount).toBeGreaterThan(0);
      expect(noIntentBinding.intentBindings).toEqual([]);
      await testInfo.attach("mixed-no-intent-kb-binding-evidence", {
        body: JSON.stringify(noIntentBinding, null, 2),
        contentType: "application/json",
      });

      const groupB = await openNormalChat(browser);
      groupBContext = groupB.context;
      groupBDriver = createMixedDriver(
        "no-intent-group",
        groupB.page,
        testInfo,
        mixedScenario,
      );
      const groupBDefaultKbQuery =
        `What code is on the ${groupBProject} readiness note, and who's the contact?`;
      const groupBFileQuery =
        "What locker and phrase are in the file I attached?";

      await groupBDriver.runTurn(
        `I'm handling ${groupBProject} with ${groupBOwnerInitial}. We started with ${groupBOldLocker}. Quick checklist?`,
        [/checklist|vendor|confirm|owner|locker|readiness/i],
        { forbidden: [groupAProject], noToolCalls: true },
      );
      await groupBDriver.runTurn(
        "Who's handling it?",
        [groupBOwnerInitial],
        { forbidden: [groupAOwnerCurrent, groupAProject], noToolCalls: true },
      );
      await groupBDriver.runTurn(groupBDefaultKbQuery, [groupBKbMarker, "Mira"], {
        forbidden: [groupAKbMarker, groupADecoyMarker],
        citation: {
          minCount: 1,
          sourceType: "KNOWLEDGE_BASE",
          sourceId: groupBTargetKbId,
        },
      });
      await groupBDriver.runTurn(
        "Got a train ride later. Any low-effort way to use it well?",
        [/read|listen|plan|note|reflect|audio|phone|rest/i],
        {
          forbidden: [groupBProject, groupBKbMarker, groupAProject],
          noToolCalls: true,
        },
      );
      if (!groupBDriver.sessionId) {
        throw new Error("No-intent group did not start a session.");
      }
      await uploadSessionFile(
        groupB.page,
        groupBDriver.sessionId,
        groupBFileName,
        `# Floor Note\n\nThe floor note lists ${groupBOldLocker}. The check phrase is ${groupBFileMarker}.\n`,
        [groupBOldLocker, groupBFileMarker],
      );
      await groupBDriver.runTurn(groupBFileQuery, [groupBOldLocker, groupBFileMarker], {
        forbidden: [groupAFileMarker],
        citation: {
          minCount: 1,
          sourceType: "SESSION_FILE",
          documentName: groupBFileName,
        },
        expectedTools: [SESSION_FILE_TOOL_NAME],
      });
      await groupBDriver.runTurn(
        `That note's stale. It's ${groupBCurrentLocker} now.`,
        [groupBCurrentLocker],
        { forbidden: [groupACurrentRoom], noToolCalls: true },
      );
      await groupBDriver.runTurn(
        "Which locker now?",
        [groupBCurrentLocker],
        { forbidden: [groupBOldLocker, groupBFileMarker, groupACurrentRoom], noToolCalls: true },
      );
      await groupBDriver.runTurn(
        "Singapore time right now?",
        [/Asia\/Singapore|Singapore|SGT|UTC\+8/i],
        { expectedTools: [syncedMcp.currentDateTimeTool] },
      );
      await groupBDriver.runTurn(
        "开会前有点乱，怎么缓一下？",
        [/呼吸|写下|优先|思路|分钟|整理/],
        {
          language: "chinese",
          forbidden: [groupBProject, groupBCurrentLocker, groupBKbMarker],
          noToolCalls: true,
        },
      );
      await groupBDriver.runTurn(
        "And 9:30 AM Singapore time on June 19 in New York?",
        [/America\/New_York|New York/i, /21:30|9:30\s*PM/i],
        { expectedTools: [syncedMcp.convertTimeTool] },
      );
      await groupBDriver.runTurn(
        "Can you double-check the readiness code?",
        [groupBKbMarker],
        {
          forbidden: [groupAKbMarker],
          citation: {
            minCount: 1,
            sourceType: "KNOWLEDGE_BASE",
            sourceId: groupBTargetKbId,
          },
        },
      );
      await groupBDriver.runTurn(
        "How do I make a status note easy to skim?",
        [/status|reviewer|summary|bullet|owner|next|risk/i],
        { forbidden: [groupBKbMarker, groupBFileMarker], noToolCalls: true },
      );
      await groupBDriver.runTurn(
        "Owner and locker?",
        [groupBOwnerInitial, groupBCurrentLocker],
        { forbidden: [groupAProject, groupACurrentRoom], noToolCalls: true },
      );
      await groupBDriver.runTurn(
        "What did the old floor note say?",
        [groupBOldLocker, groupBFileMarker],
        {
          forbidden: [groupAFileMarker],
          citation: {
            minCount: 1,
            sourceType: "SESSION_FILE",
            documentName: groupBFileName,
          },
          expectedTools: [SESSION_FILE_TOOL_NAME],
        },
      );
      await groupBDriver.runTurn(
        `${groupBOwnerInitial} handed it to ${groupBOwnerCurrent}. First check?`,
        [groupBOwnerCurrent, /confirm|review|vendor|readiness|status|owner/i],
        { noToolCalls: true },
      );
      await groupBDriver.runTurn(
        "Who's on point now, and which locker?",
        [groupBOwnerCurrent, groupBCurrentLocker],
        { forbidden: [groupBOwnerInitial, groupBOldLocker, groupAOwnerCurrent], noToolCalls: true },
      );
      await groupBDriver.runTurn(
        "operations",
        [/operation|ops|context|clarify|mean|help|area/i],
        {
          forbidden: [rootAName, rootBName, groupAKbMarker],
          noToolCalls: true,
        },
      );
      await groupBDriver.runTurn(
        "Can you double-check the vendor contact?",
        ["Mira"],
        {
          forbidden: [groupAUnboundMarker],
          citation: {
            minCount: 1,
            sourceType: "KNOWLEDGE_BASE",
            sourceId: groupBTargetKbId,
          },
        },
      );
      await groupBDriver.runTurn(
        "Snack ideas for a long afternoon?",
        [/snack|protein|water|fruit|nuts|energy/i],
        {
          forbidden: [groupBProject, groupBKbMarker, groupBCurrentLocker],
          noToolCalls: true,
        },
      );
      await groupBDriver.runTurn(
        "Two status notes disagree. What now?",
        [/compare|source|latest|confirm|owner|date|verify/i],
        { forbidden: [groupAKbMarker, groupAProject], noToolCalls: true },
      );
      await groupBDriver.runTurn(
        "How should I word the locker correction?",
        [groupBCurrentLocker, /old|attached|note|current|updated|changed/i],
        { forbidden: [groupACurrentRoom], noToolCalls: true },
      );
      await groupBDriver.runTurn(
        "3:45 PM Singapore time on June 20 in New York?",
        [/America\/New_York|New York|Eastern|EDT|EST/i, /03:45|3:45\s*AM/i],
        { expectedTools: [syncedMcp.convertTimeTool] },
      );
      await groupBDriver.runTurn(
        "Wait, which project is this?",
        [groupBProject],
        { forbidden: [groupAProject, groupAOwnerCurrent, groupACurrentRoom], noToolCalls: true },
      );
      await groupBDriver.runTurn(
        "How do I close the check-in without sounding rushed?",
        [/thank|next|action|deadline|confirm|follow/i],
        { forbidden: [groupBKbMarker, groupBFileMarker], noToolCalls: true },
      );
      await groupBDriver.runTurn(
        "Double-check the readiness code?",
        [groupBKbMarker],
        {
          forbidden: [groupAKbMarker, groupAProject],
          citation: {
            minCount: 1,
            sourceType: "KNOWLEDGE_BASE",
            sourceId: groupBTargetKbId,
          },
        },
      );
      await groupBDriver.runTurn(
        "Old locker versus current one?",
        [groupBOldLocker, groupBCurrentLocker],
        { forbidden: [groupACurrentRoom], noToolCalls: true },
      );
      await groupBDriver.runTurn(
        "现在用哪个 locker？",
        [groupBCurrentLocker],
        {
          language: "chinese",
          forbidden: [groupBOldLocker, groupAFileRoom],
          noToolCalls: true,
        },
      );
      await groupBDriver.runTurn(
        "Who owns it now, and which locker?",
        [groupBOwnerCurrent, groupBCurrentLocker],
        { forbidden: [groupBOwnerInitial, groupBOldLocker, groupAProject], noToolCalls: true },
      );
      await groupBDriver.runTurn(
        "Can you make that correction sound concise?",
        [groupBCurrentLocker, /corrected|updated|brief|clear|current/i],
        { forbidden: [groupAFileMarker], noToolCalls: true },
      );
      await groupBDriver.runTurn(
        "Can you wrap this up with the project, who owns it, the right locker, and the two codes?",
        [groupBProject, groupBOwnerCurrent, groupBCurrentLocker, groupBKbMarker, groupBFileMarker],
        {
          forbidden: [groupAProject, groupAKbMarker, groupAFileMarker],
          citation: { minCount: 2 },
        },
      );
      await groupBDriver.runTurn(
        "What are the two easy mix-ups?",
        [/old|current|locker|owner|source|note|code/i],
        { forbidden: [groupAProject, groupACurrentRoom], noToolCalls: true },
      );
      expect(groupBDriver.turnCount).toBeGreaterThanOrEqual(30);

      const groupBKbEvidence = await waitForKnowledgeBaseEvidence(
        groupBTargetKbId,
        [groupBKbMarker],
        (evidence) =>
          evidence.documents.some((document) => document.markers[groupBKbMarker]),
      );
      const finalCitations =
        groupBDriver.sessionId == null
          ? []
          : (
              await readMemoryEvidence(users.normal.username, groupBDriver.sessionId, [
                groupBProject,
              ])
            ).items;
      await testInfo.attach("mixed-no-intent-kb-evidence", {
        body: JSON.stringify(
          {
            knowledgeBase: groupBKbEvidence,
            memoryItemCount: finalCitations.length,
          },
          null,
          2,
        ),
        contentType: "application/json",
      });
      await attachMemoryEvidence(
        testInfo,
        "no-intent-group",
        users.normal.username,
        groupBDriver.sessionId!,
        [groupBProject, groupBOwnerCurrent, groupBCurrentLocker],
      );

      const groupBMessages = await groupB.page
        .locator(`[data-chat-message-id]`)
        .evaluateAll((nodes) => nodes.map((node) => node.textContent ?? ""));
      expect(groupBMessages.join("\n")).not.toContain(groupAKbMarker);
      expect(groupBMessages.join("\n")).not.toContain(groupAFileMarker);

      const groupAKbEvidence = await waitForKnowledgeBaseEvidence(
        groupATargetKbId,
        [groupAKbMarker],
        (evidence) =>
          evidence.documents.some((document) => document.markers[groupAKbMarker]),
      );
      const groupBReferenceAnswer = await groupBDriver.runTurn(
        "Can you verify the vendor contact one last time?",
        ["Mira"],
        {
          forbidden: [groupAProject, groupAKbMarker],
          citation: {
            minCount: 1,
            sourceType: "KNOWLEDGE_BASE",
            sourceId: groupBTargetKbId,
          },
        },
      );
      await assertDialogueRealism(testInfo, "no-intent-group", groupBDriver.prompts);
      expectCitationDocumentsMatchEvidence(
        groupBReferenceAnswer.assistant.metadata?.citations ?? [],
        groupBKbEvidence,
      );
      expect(
        (groupBReferenceAnswer.assistant.metadata?.citations ?? []).some(
          (citation) =>
            groupAKbEvidence.documents.some(
              (document) => document.id === citation.documentId,
            ),
        ),
      ).toBe(false);
      scenarioCompleted = true;
    } finally {
      const errors: string[] = [];
      await mixedScenario
        .persistManifest(
          testInfo,
          groupADriver?.prompts ?? [],
          groupBDriver?.prompts ?? [],
          scenarioCompleted,
        )
        .catch((error: unknown) => {
          errors.push(
            `mixed scenario manifest: ${error instanceof Error ? error.message : String(error)}`,
          );
        });
      await groupAContext?.close().catch((error: unknown) => {
        errors.push(
          `close intent-tree browser context: ${error instanceof Error ? error.message : String(error)}`,
        );
      });
      await groupBContext?.close().catch((error: unknown) => {
        errors.push(
          `close no-intent browser context: ${error instanceof Error ? error.message : String(error)}`,
        );
      });
      if (baselineCaptured) {
        await cleanupGeneratedIntentArtifacts(
          SYSTEM_ASSISTANT_ID,
          INTENT_PREFIX + runSuffix,
          generatedVersions,
          originalActiveVersion,
        ).catch((error: unknown) => {
          errors.push(
            `intent cleanup: ${error instanceof Error ? error.message : String(error)}`,
          );
        });
        await setActiveIntentVersion(
          SYSTEM_ASSISTANT_ID,
          originalActiveVersion,
        ).catch((error: unknown) => {
          errors.push(
            `restore active intent version: ${error instanceof Error ? error.message : String(error)}`,
          );
        });
        await setAssistantBindings(adminApi, originalBindings).catch(
          (error: unknown) => {
            errors.push(
              `restore assistant KB bindings: ${error instanceof Error ? error.message : String(error)}`,
            );
          },
        );
        await setAssistantAllowedTools(
          SYSTEM_ASSISTANT_ID,
          originalAllowedTools,
        ).catch((error: unknown) => {
          errors.push(
            `restore assistant tools: ${error instanceof Error ? error.message : String(error)}`,
          );
        });
      }
      for (const knowledgeBaseId of generatedKnowledgeBaseIds) {
        await deleteApi<void>(
          adminApi.context,
          `/api/admin/knowledge-bases/${knowledgeBaseId}`,
          adminApi.token,
        ).catch((error: unknown) => {
          errors.push(
            `KB cleanup ${knowledgeBaseId}: ${error instanceof Error ? error.message : String(error)}`,
          );
        });
      }
      await deleteGeneratedKnowledgeBases(adminApi).catch((error: unknown) => {
        errors.push(
          `delete generated mixed KBs: ${error instanceof Error ? error.message : String(error)}`,
        );
      });
      await deleteGeneratedMcpServers(users.admin).catch((error: unknown) => {
        errors.push(
          `delete generated mixed MCP servers: ${error instanceof Error ? error.message : String(error)}`,
        );
      });
      await adminApi.context.dispose().catch((error: unknown) => {
        errors.push(
          `dispose admin context: ${error instanceof Error ? error.message : String(error)}`,
        );
      });
      if (errors.length > 0) {
        cleanupError = new Error(errors.join("; "));
      }
    }
    if (cleanupError) {
      throw cleanupError;
    }
  });
});
