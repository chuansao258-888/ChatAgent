import { request as playwrightRequest } from "@playwright/test";
import { expect, test } from "../fixtures";
import { deleteApi, getApi, postApi, postMultipartApi, putApi } from "../helpers/api";
import { loginThroughUi, loginUser, type E2eUser } from "../helpers/auth";
import {
  sendMessage,
  startChatAndWaitForAssistant,
  waitForAssistantTurn,
  waitForInputReady,
  type ChatTurnEvidence,
} from "../helpers/chat";
import {
  readAssistantAllowedTools,
  readToolCallEvidence,
  setAssistantAllowedTools,
  waitForKnowledgeBaseEvidence,
  waitForSessionFileEvidence,
  waitForToolCallEvidence,
  waitForTurnCompletion,
} from "../helpers/db";
import {
  apiBaseUrl,
  e2eRunId,
  normalStorageStatePath,
} from "../helpers/env";
import { readE2eUsers } from "../helpers/testUsers";
import type { CitationMetadata } from "../../src/types";

const SYSTEM_ASSISTANT_ID = "3f9f84f7-2df0-4a5f-9c85-9f2d9b7aaf10";
const GENERATED_KB_PREFIX = "E2E Priority ";
const SESSION_FILE_TOOL_NAME = "SessionFileSearchTool";
const WEB_SEARCH_BACKEND_TOOL_NAME = "webSearchTool";
const WEB_SEARCH_MODEL_TOOL_NAME = "webSearch";
const TURN_TIMEOUT_MS = 300_000;
const INGESTION_TIMEOUT_MS = 300_000;
const WEB_SEARCH_MARKER =
  process.env.E2E_WEB_SEARCH_MARKER ?? "E2E-WEB-SEARCH-BEACON-ORCHARD-GREEN";
const WEB_SEARCH_SOURCE_URL =
  process.env.E2E_WEB_SEARCH_SOURCE_URL ??
  "https://example.test/e2e/beacon-orchard-status";
const EMPTY_WEB_NEEDLE =
  process.env.E2E_WEB_SEARCH_EMPTY_QUERY_NEEDLE ?? "E2E-NO-WEB-RESULTS";

interface KnowledgeBaseListItem {
  id: string;
  name: string;
  description?: string | null;
}

interface ToolVO {
  name: string;
  description?: string | null;
  type: string;
}

function generatedSuffix(): string {
  return e2eRunId.replace(/[^a-zA-Z0-9]/g, "").slice(-8).toUpperCase();
}

function uniqueTools(toolNames: string[]): string[] {
  return [...new Set(toolNames)];
}

function expectToolCalledOnlyLocalForKnowledge(evidence: Awaited<ReturnType<typeof readToolCallEvidence>>) {
  expect(evidence.calls.some((call) => call.name === SESSION_FILE_TOOL_NAME)).toBe(true);
  expect(evidence.calls.some((call) => call.name === WEB_SEARCH_MODEL_TOOL_NAME)).toBe(false);
}

function expectToolCallOrder(
  evidence: Awaited<ReturnType<typeof readToolCallEvidence>>,
  firstToolName: string,
  secondToolName: string,
) {
  const toolNames = evidence.calls.map((call) => call.name);
  const firstIndex = toolNames.indexOf(firstToolName);
  const secondIndex = toolNames.indexOf(secondToolName);
  expect(firstIndex, `${firstToolName} should be called`).toBeGreaterThanOrEqual(0);
  expect(secondIndex, `${secondToolName} should be called`).toBeGreaterThanOrEqual(0);
  expect(firstIndex, `${firstToolName} should run before ${secondToolName}`).toBeLessThan(secondIndex);
}

async function attachTurnEvidence(
  testInfo: Parameters<Parameters<typeof test>[1]>[1],
  name: string,
  evidence: unknown,
) {
  await testInfo.attach(name, {
    body: JSON.stringify(evidence, null, 2),
    contentType: "application/json",
  });
}

async function attachPromptRealismEvidence(
  testInfo: Parameters<Parameters<typeof test>[1]>[1],
  prompts: string[],
  promptChain: Array<{ turn: number; basedOn: string; generatedPrompt: string }> = [],
) {
  const mechanismMentions = prompts.filter((prompt) =>
    /\b(?:tool|database|knowledge base|mcp)\b/i.test(prompt),
  );
  const exactDuplicates = prompts.filter(
    (prompt, index) => prompts.indexOf(prompt) !== index,
  );
  await attachTurnEvidence(testInfo, "priority-10turn-prompt-realism", {
    totalTurns: prompts.length,
    mechanismMentionCount: mechanismMentions.length,
    exactDuplicateCount: exactDuplicates.length,
    averageLength: Math.round(
      prompts.reduce((total, prompt) => total + prompt.length, 0) / prompts.length,
    ),
    adaptiveChain: promptChain,
    prompts,
  });
  expect(prompts).toHaveLength(10);
  expect(mechanismMentions).toEqual([]);
  expect(exactDuplicates).toEqual([]);
}

function summarizeForPromptChain(content: string): string {
  return content.replace(/\s+/g, " ").trim().slice(0, 240);
}

function extractCode(content: string, expected: string, label: string): string {
  const escaped = expected.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const match = content.match(new RegExp(escaped, "i"));
  if (!match?.[0]) {
    throw new Error(`Unable to derive ${label} from assistant response: ${content}`);
  }
  return match[0];
}

function extractUrl(content: string, expected: string, label: string): string {
  if (!content.includes(expected)) {
    throw new Error(`Unable to derive ${label} from assistant response: ${content}`);
  }
  return expected;
}

function generatePromptFromPrevious(
  prompts: string[],
  promptChain: Array<{ turn: number; basedOn: string; generatedPrompt: string }>,
  previous: ChatTurnEvidence,
  generatedPrompt: string,
): string {
  prompts.push(generatedPrompt);
  promptChain.push({
    turn: prompts.length,
    basedOn: summarizeForPromptChain(previous.assistant.content),
    generatedPrompt,
  });
  return generatedPrompt;
}

function chooseRunVariant(seed: string, turn: number, variants: string[]): string {
  const hash = Array.from(`${seed}:${turn}`).reduce(
    (total, char) => total + char.charCodeAt(0),
    0,
  );
  return variants[hash % variants.length];
}

function expectKnowledgeCitation(
  citations: CitationMetadata[],
  sourceType: "KNOWLEDGE_BASE" | "SESSION_FILE",
  documentName: string,
) {
  expect(citations.length).toBeGreaterThan(0);
  expect(
    citations.some(
      (citation) =>
        citation.sourceType === sourceType &&
        citation.documentName === documentName,
    ),
  ).toBe(true);
}

async function createKnowledgeBaseByApi(
  adminUser: E2eUser,
  name: string,
  description: string,
): Promise<string> {
  const context = await playwrightRequest.newContext({ baseURL: apiBaseUrl });
  try {
    const auth = await loginUser(context, adminUser);
    return await postApi<string>(
      context,
      "/api/admin/knowledge-bases",
      { name, description },
      auth.accessToken,
    );
  } finally {
    await context.dispose();
  }
}

async function uploadKnowledgeDocumentByApi(
  adminUser: E2eUser,
  knowledgeBaseId: string,
  file: { name: string; mimeType: string; buffer: Buffer },
) {
  const context = await playwrightRequest.newContext({ baseURL: apiBaseUrl });
  try {
    const auth = await loginUser(context, adminUser);
    await postMultipartApi(
      context,
      `/api/admin/knowledge-bases/${knowledgeBaseId}/documents/upload`,
      { file },
      auth.accessToken,
    );
  } finally {
    await context.dispose();
  }
}

async function setAssistantBindingsByApi(
  adminUser: E2eUser,
  knowledgeBaseIds: string[],
) {
  const context = await playwrightRequest.newContext({ baseURL: apiBaseUrl });
  try {
    const auth = await loginUser(context, adminUser);
    await putApi<void>(
      context,
      "/api/admin/assistant/knowledge-bases",
      { knowledgeBaseIds },
      auth.accessToken,
    );
  } finally {
    await context.dispose();
  }
}

async function readAssistantBindingIdsByApi(adminUser: E2eUser): Promise<string[]> {
  const context = await playwrightRequest.newContext({ baseURL: apiBaseUrl });
  try {
    const auth = await loginUser(context, adminUser);
    const knowledgeBases = await getApi<KnowledgeBaseListItem[]>(
      context,
      "/api/admin/assistant/knowledge-bases",
      auth.accessToken,
    );
    return knowledgeBases.map((knowledgeBase) => knowledgeBase.id);
  } finally {
    await context.dispose();
  }
}

async function deleteGeneratedKnowledgeBasesByApi(adminUser: E2eUser) {
  const context = await playwrightRequest.newContext({ baseURL: apiBaseUrl });
  try {
    const auth = await loginUser(context, adminUser);
    const knowledgeBases = await getApi<KnowledgeBaseListItem[]>(
      context,
      "/api/admin/knowledge-bases",
      auth.accessToken,
    );
    const generated = knowledgeBases.filter((knowledgeBase) =>
      knowledgeBase.name.startsWith(GENERATED_KB_PREFIX),
    );
    for (const knowledgeBase of generated) {
      await deleteApi<void>(
        context,
        `/api/admin/knowledge-bases/${knowledgeBase.id}`,
        auth.accessToken,
      );
    }
  } finally {
    await context.dispose();
  }
}

async function requireWebSearchTool(adminUser: E2eUser) {
  const context = await playwrightRequest.newContext({ baseURL: apiBaseUrl });
  try {
    const auth = await loginUser(context, adminUser);
    const tools = await getApi<ToolVO[]>(
      context,
      "/api/tools",
      auth.accessToken,
    );
    const webSearchTool = tools.find(
      (tool) => tool.name === WEB_SEARCH_BACKEND_TOOL_NAME,
    );
    if (!webSearchTool) {
      throw new Error(
        "webSearchTool is not exposed; start the backend with CHATAGENT_WEB_SEARCH_ENABLED=true and the local SearXNG fixture.",
      );
    }
  } finally {
    await context.dispose();
  }
}

async function continueConversation(
  page: Parameters<typeof sendMessage>[0],
  sessionId: string,
  prompt: string,
): Promise<ChatTurnEvidence> {
  await sendMessage(page, prompt);
  const evidence = await waitForAssistantTurn(page, sessionId, prompt, {
    timeoutMs: TURN_TIMEOUT_MS,
  });
  await waitForInputReady(page);
  if (!evidence.user.turnId) {
    throw new Error("Persisted priority E2E user message has no turnId.");
  }
  await waitForTurnCompletion(sessionId, evidence.user.turnId);
  return evidence;
}

test.describe("@priority RAG/web/model source order", () => {
  test.use({ storageState: normalStorageStatePath });

  test("prefers local KB and uploaded files, then web search, then model knowledge", async ({
    page,
  }, testInfo) => {
    test.setTimeout(1_200_000);
    const users = await readE2eUsers();
    const suffix = generatedSuffix();
    const knowledgeBaseName = `${GENERATED_KB_PREFIX}${suffix}`;
    const kbMarker = `PRIORITY-KB-${suffix}`;
    const fileMarker = `PRIORITY-FILE-${suffix}`;
    const kbFilename = `priority-kb-${suffix}.md`;
    const sessionFilename = `priority-session-${suffix}.txt`;
    const noWebToken = `${EMPTY_WEB_NEEDLE}-${suffix}`;
    let createdKnowledgeBaseId: string | null = null;
    let originalBindingIds: string[] | null = null;
    let originalAllowedTools: string[] | null = null;

    try {
      await deleteGeneratedKnowledgeBasesByApi(users.admin);
      originalBindingIds = await readAssistantBindingIdsByApi(users.admin);
      originalAllowedTools = (await readAssistantAllowedTools(SYSTEM_ASSISTANT_ID)).allowedTools;
      await requireWebSearchTool(users.admin);
      await setAssistantAllowedTools(
        SYSTEM_ASSISTANT_ID,
        uniqueTools([
          ...originalAllowedTools,
          SESSION_FILE_TOOL_NAME,
          WEB_SEARCH_BACKEND_TOOL_NAME,
        ]),
      );

      createdKnowledgeBaseId = await createKnowledgeBaseByApi(
        users.admin,
        knowledgeBaseName,
        "Generated source-priority evidence for headed E2E.",
      );
      await uploadKnowledgeDocumentByApi(users.admin, createdKnowledgeBaseId, {
        name: kbFilename,
        mimeType: "text/markdown",
        buffer: Buffer.from(
          `# Priority KB\n\nThe private rollout marker in the internal release desk is ${kbMarker}.\n`,
        ),
      });
      const kbEvidence = await waitForKnowledgeBaseEvidence(
        createdKnowledgeBaseId,
        [kbMarker],
        (evidence) =>
          evidence.documents.length === 1 &&
          evidence.documents[0]?.parseStatus === "COMPLETED" &&
          evidence.documents[0]?.indexed &&
          evidence.documents[0]?.markers[kbMarker],
        { timeoutMs: INGESTION_TIMEOUT_MS },
      );
      await testInfo.attach("priority-kb-ingestion-evidence", {
        body: JSON.stringify(kbEvidence, null, 2),
        contentType: "application/json",
      });
      await setAssistantBindingsByApi(users.admin, [createdKnowledgeBaseId]);

      await loginThroughUi(page, users.normal, normalStorageStatePath);

      const kbPrompt =
        "Check the internal release desk before anything else. What is the private rollout marker? Cite the local source.";
      const kbTurn = await startChatAndWaitForAssistant(page, kbPrompt, {
        mode: "REACT",
        timeoutMs: TURN_TIMEOUT_MS,
      });
      expect(kbTurn.user.turnId).toBeTruthy();
      await waitForTurnCompletion(kbTurn.sessionId, kbTurn.user.turnId!);
      expect(kbTurn.assistant.content).toContain(kbMarker);
      expectKnowledgeCitation(
        kbTurn.assistant.metadata?.citations ?? [],
        "KNOWLEDGE_BASE",
        kbFilename,
      );
      const kbToolEvidence = await readToolCallEvidence(
        kbTurn.sessionId,
        kbTurn.user.turnId!,
      );
      expectToolCalledOnlyLocalForKnowledge(kbToolEvidence);
      await testInfo.attach("priority-kb-turn-evidence", {
        body: JSON.stringify({
          toolEvidence: kbToolEvidence,
          citations: kbTurn.assistant.metadata?.citations ?? [],
        }, null, 2),
        contentType: "application/json",
      });

      await page.locator("input[type='file']").setInputFiles({
        name: sessionFilename,
        mimeType: "text/plain",
        buffer: Buffer.from(
          `Session note\nThe uploaded session-only marker is ${fileMarker}.\n`,
        ),
      });
      const fileEvidence = await waitForSessionFileEvidence(
        kbTurn.sessionId,
        sessionFilename,
        [fileMarker],
        (evidence) =>
          evidence.parseStatus === "COMPLETED" &&
          evidence.chunks.length > 0 &&
          evidence.markers[fileMarker],
        { timeoutMs: INGESTION_TIMEOUT_MS },
      );
      await testInfo.attach("priority-session-file-ingestion-evidence", {
        body: JSON.stringify(fileEvidence, null, 2),
        contentType: "application/json",
      });

      const fileTurn = await continueConversation(
        page,
        kbTurn.sessionId,
        "I just attached a small note. What is the uploaded session-only marker? Cite the local source.",
      );
      expect(fileTurn.assistant.content).toContain(fileMarker);
      expectKnowledgeCitation(
        fileTurn.assistant.metadata?.citations ?? [],
        "SESSION_FILE",
        sessionFilename,
      );
      const fileToolEvidence = await readToolCallEvidence(
        kbTurn.sessionId,
        fileTurn.user.turnId!,
      );
      expectToolCalledOnlyLocalForKnowledge(fileToolEvidence);

      const webPrompt =
        "Check our local KB and uploaded note first for the Beacon Orchard public status marker. " +
        "If the local sources do not contain it, use public web search and quote the exact latest marker with the source URL.";
      const webTurn = await continueConversation(page, kbTurn.sessionId, webPrompt);
      expect(webTurn.assistant.content).toContain(WEB_SEARCH_MARKER);
      expect(webTurn.assistant.content).toContain(WEB_SEARCH_SOURCE_URL);
      expect(webTurn.assistant.content).not.toContain(kbMarker);
      expect(webTurn.assistant.content).not.toContain(fileMarker);
      const webToolEvidence = await waitForToolCallEvidence(
        kbTurn.sessionId,
        webTurn.user.turnId!,
        [SESSION_FILE_TOOL_NAME, WEB_SEARCH_MODEL_TOOL_NAME],
        { timeoutMs: TURN_TIMEOUT_MS },
      );
      await testInfo.attach("priority-web-fallback-evidence", {
        body: JSON.stringify(webToolEvidence, null, 2),
        contentType: "application/json",
      });

      const modelPrompt =
        `Check local sources first, then public web for ${noWebToken}. ` +
        "If neither has evidence, use your general knowledge and explain briefly: what is a release candidate?";
      const modelTurn = await continueConversation(page, kbTurn.sessionId, modelPrompt);
      const modelToolEvidence = await waitForToolCallEvidence(
        kbTurn.sessionId,
        modelTurn.user.turnId!,
        [SESSION_FILE_TOOL_NAME, WEB_SEARCH_MODEL_TOOL_NAME],
        { timeoutMs: TURN_TIMEOUT_MS },
      );
      await testInfo.attach("priority-model-fallback-evidence", {
        body: JSON.stringify(modelToolEvidence, null, 2),
        contentType: "application/json",
      });
      expect(modelTurn.assistant.content).toMatch(/release candidate|pre[- ]?release|candidate/i);
      expect(modelTurn.assistant.content).not.toContain(WEB_SEARCH_MARKER);
      expect(modelTurn.assistant.content).not.toContain(kbMarker);
      expect(modelTurn.assistant.content).not.toContain(fileMarker);
      expect(modelTurn.assistant.metadata?.citations ?? []).toEqual([]);
    } finally {
      if (originalAllowedTools) {
        await setAssistantAllowedTools(SYSTEM_ASSISTANT_ID, originalAllowedTools).catch(
          (error: unknown) => {
            console.warn(
              `Failed to restore assistant tools: ${error instanceof Error ? error.message : String(error)}`,
            );
          },
        );
      }
      if (originalBindingIds) {
        await setAssistantBindingsByApi(users.admin, originalBindingIds).catch(
          (error: unknown) => {
            console.warn(
              `Failed to restore assistant KB bindings: ${error instanceof Error ? error.message : String(error)}`,
            );
          },
        );
      }
      await deleteGeneratedKnowledgeBasesByApi(users.admin).catch((error: unknown) => {
        console.warn(
          `Failed to delete generated priority KBs: ${error instanceof Error ? error.message : String(error)}`,
        );
      });
    }
  });

  test("@priority-10turn keeps source order stable across ten realistic turns", async ({
    page,
  }, testInfo) => {
    test.setTimeout(2_400_000);
    const users = await readE2eUsers();
    const suffix = generatedSuffix();
    const knowledgeBaseName = `${GENERATED_KB_PREFIX}10 Turn ${suffix}`;
    const kbMarker = `PRIORITY10-ANCHOR-${suffix}`;
    const kbDecision = `PRIORITY10-RIDGE-${suffix}`;
    const fileMarker = `PRIORITY10-QUARTZ-${suffix}`;
    const fileOwner = `PRIORITY10-HARBOR-${suffix}`;
    const fileAction = `PRIORITY10-NOON-${suffix}`;
    const kbFilename = `priority-10turn-kb-${suffix}.md`;
    const sessionFilename = `priority-10turn-session-${suffix}.txt`;
    const noWebToken = `${EMPTY_WEB_NEEDLE}-10TURN-${suffix}`;
    const prompts: string[] = [];
    const promptChain: Array<{ turn: number; basedOn: string; generatedPrompt: string }> = [];
    let createdKnowledgeBaseId: string | null = null;
    let originalBindingIds: string[] | null = null;
    let originalAllowedTools: string[] | null = null;

    try {
      await deleteGeneratedKnowledgeBasesByApi(users.admin);
      originalBindingIds = await readAssistantBindingIdsByApi(users.admin);
      originalAllowedTools = (await readAssistantAllowedTools(SYSTEM_ASSISTANT_ID)).allowedTools;
      await requireWebSearchTool(users.admin);
      await setAssistantAllowedTools(
        SYSTEM_ASSISTANT_ID,
        uniqueTools([
          ...originalAllowedTools,
          SESSION_FILE_TOOL_NAME,
          WEB_SEARCH_BACKEND_TOOL_NAME,
        ]),
      );

      createdKnowledgeBaseId = await createKnowledgeBaseByApi(
        users.admin,
        knowledgeBaseName,
        "Generated ten-turn source-priority evidence for headed E2E.",
      );
      await uploadKnowledgeDocumentByApi(users.admin, createdKnowledgeBaseId, {
        name: kbFilename,
        mimeType: "text/markdown",
        buffer: Buffer.from(
          [
            "# Ten-turn priority desk",
            "",
            `The private rollout marker in the internal release desk is ${kbMarker}.`,
            `The internal decision code for the release handoff is ${kbDecision}.`,
          ].join("\n"),
        ),
      });
      const kbEvidence = await waitForKnowledgeBaseEvidence(
        createdKnowledgeBaseId,
        [kbMarker, kbDecision],
        (evidence) =>
          evidence.documents.length === 1 &&
          evidence.documents[0]?.parseStatus === "COMPLETED" &&
          evidence.documents[0]?.indexed &&
          evidence.documents[0]?.markers[kbMarker] &&
          evidence.documents[0]?.markers[kbDecision],
        { timeoutMs: INGESTION_TIMEOUT_MS },
      );
      await attachTurnEvidence(testInfo, "priority-10turn-kb-ingestion-evidence", kbEvidence);
      await setAssistantBindingsByApi(users.admin, [createdKnowledgeBaseId]);

      await loginThroughUi(page, users.normal, normalStorageStatePath);

      const turn1Prompt = chooseRunVariant(suffix, 1, [
        "I'm prepping the release standup. What's the private rollout marker from our internal release desk? Keep the exact code intact.",
        "Before the standup, can you pull the private rollout marker from our release desk notes? I need the exact code.",
        "I'm filling the handoff draft. What private rollout marker did the release desk record? Please keep the code unchanged.",
      ]);
      prompts.push(turn1Prompt);
      const turn1 = await startChatAndWaitForAssistant(page, turn1Prompt, {
        mode: "REACT",
        timeoutMs: TURN_TIMEOUT_MS,
      });
      expect(turn1.user.turnId).toBeTruthy();
      await waitForTurnCompletion(turn1.sessionId, turn1.user.turnId!);
      expect(turn1.assistant.content).toContain(kbMarker);
      expectKnowledgeCitation(
        turn1.assistant.metadata?.citations ?? [],
        "KNOWLEDGE_BASE",
        kbFilename,
      );
      expectToolCalledOnlyLocalForKnowledge(
        await readToolCallEvidence(turn1.sessionId, turn1.user.turnId!),
      );
      const observedKbMarker = extractCode(
        turn1.assistant.content,
        kbMarker,
        "private rollout marker",
      );

      await page.locator("input[type='file']").setInputFiles({
        name: sessionFilename,
        mimeType: "text/plain",
        buffer: Buffer.from(
          [
            "Session briefing note",
            `The uploaded session-only marker is ${fileMarker}.`,
            `The briefing owner code in the attachment is ${fileOwner}.`,
            `The next checklist item before noon is ${fileAction}.`,
          ].join("\n"),
        ),
      });
      const fileEvidence = await waitForSessionFileEvidence(
        turn1.sessionId,
        sessionFilename,
        [fileMarker, fileOwner, fileAction],
        (evidence) =>
          evidence.parseStatus === "COMPLETED" &&
          evidence.chunks.length > 0 &&
          evidence.markers[fileMarker] &&
          evidence.markers[fileOwner] &&
          evidence.markers[fileAction],
        { timeoutMs: INGESTION_TIMEOUT_MS },
      );
      await attachTurnEvidence(
        testInfo,
        "priority-10turn-session-file-ingestion-evidence",
        fileEvidence,
      );

      const turn2Prompt = generatePromptFromPrevious(
        prompts,
        promptChain,
        turn1,
        chooseRunVariant(suffix, 2, [
          `I see ${observedKbMarker} from the release desk. In the file I just attached, ${sessionFilename}, what's the session-only marker? Keep the exact code intact.`,
          `Okay, ${observedKbMarker} is noted. Check the attached ${sessionFilename}; what marker is only in that briefing file?`,
          `Got ${observedKbMarker}. Now look at the file I uploaded, ${sessionFilename}, and tell me the session-only marker exactly.`,
        ]),
      );
      const turn2 = await continueConversation(page, turn1.sessionId, turn2Prompt);
      expect(turn2.assistant.content).toContain(fileMarker);
      expectKnowledgeCitation(
        turn2.assistant.metadata?.citations ?? [],
        "SESSION_FILE",
        sessionFilename,
      );
      expectToolCalledOnlyLocalForKnowledge(
        await readToolCallEvidence(turn1.sessionId, turn2.user.turnId!),
      );
      const observedFileMarker = extractCode(
        turn2.assistant.content,
        fileMarker,
        "session-only marker",
      );

      const turn3Prompt = generatePromptFromPrevious(
        prompts,
        promptChain,
        turn2,
        chooseRunVariant(suffix, 3, [
          `Good, ${observedFileMarker} is the attachment marker. Who owns that same briefing item in the attached file?`,
          `Thanks. For the same attached briefing item as ${observedFileMarker}, who is listed as owner?`,
          `That helps. In the uploaded briefing, who owns the item tied to ${observedFileMarker}?`,
        ]),
      );
      const turn3 = await continueConversation(page, turn1.sessionId, turn3Prompt);
      expect(turn3.assistant.content).toContain(fileOwner);
      expectKnowledgeCitation(
        turn3.assistant.metadata?.citations ?? [],
        "SESSION_FILE",
        sessionFilename,
      );
      const observedFileOwner = extractCode(
        turn3.assistant.content,
        fileOwner,
        "briefing owner",
      );

      const turn4Prompt = generatePromptFromPrevious(
        prompts,
        promptChain,
        turn3,
        chooseRunVariant(suffix, 4, [
          `Thanks. With ${observedFileOwner} as the attachment owner, what was the internal decision code from the release desk again?`,
          `Now that ${observedFileOwner} owns the attached item, remind me of the release desk decision code.`,
          `Pairing this with ${observedFileOwner}, what internal decision code did the release desk have on record?`,
        ]),
      );
      const turn4 = await continueConversation(page, turn1.sessionId, turn4Prompt);
      expect(turn4.assistant.content).toContain(kbDecision);
      expectKnowledgeCitation(
        turn4.assistant.metadata?.citations ?? [],
        "KNOWLEDGE_BASE",
        kbFilename,
      );
      const observedKbDecision = extractCode(
        turn4.assistant.content,
        kbDecision,
        "internal decision code",
      );

      const turn5Prompt = generatePromptFromPrevious(
        prompts,
        promptChain,
        turn4,
        chooseRunVariant(suffix, 5, [
          `Now I have ${observedKbMarker}, ${observedKbDecision}, and ${observedFileMarker}. Beacon Orchard just came up in status chat; check our notes first, then current public sources. What's its public marker and link?`,
          `One more thread: Beacon Orchard was mentioned in status chat. Look through what we already have before checking current public sources, then give me its public marker and link.`,
          `Can you verify Beacon Orchard for the handoff? Start with our existing notes, then use current public sources if needed, and return the public marker plus link.`,
        ]),
      );
      const turn5 = await continueConversation(page, turn1.sessionId, turn5Prompt);
      expect(turn5.assistant.content).toContain(WEB_SEARCH_MARKER);
      expect(turn5.assistant.content).toContain(WEB_SEARCH_SOURCE_URL);
      expect(turn5.assistant.content).not.toContain(kbMarker);
      expect(turn5.assistant.content).not.toContain(fileMarker);
      const turn5Tools = await waitForToolCallEvidence(
        turn1.sessionId,
        turn5.user.turnId!,
        [SESSION_FILE_TOOL_NAME, WEB_SEARCH_MODEL_TOOL_NAME],
        { timeoutMs: TURN_TIMEOUT_MS },
      );
      expectToolCallOrder(
        turn5Tools,
        SESSION_FILE_TOOL_NAME,
        WEB_SEARCH_MODEL_TOOL_NAME,
      );
      const observedWebMarker = extractCode(
        turn5.assistant.content,
        WEB_SEARCH_MARKER,
        "public status marker",
      );
      const observedWebUrl = extractUrl(
        turn5.assistant.content,
        WEB_SEARCH_SOURCE_URL,
        "public status URL",
      );

      const turn6Prompt = generatePromptFromPrevious(
        prompts,
        promptChain,
        turn5,
        chooseRunVariant(suffix, 6, [
          `Leave ${observedWebMarker} out for a moment. Give me a compact recap of the three private or local things we found.`,
          `Set aside the public marker ${observedWebMarker}. What are the three private or local items I should keep in the internal handoff?`,
          `Don't include ${observedWebMarker} in this recap. Summarize the three private/local findings we have so far.`,
        ]),
      );
      const turn6 = await continueConversation(page, turn1.sessionId, turn6Prompt);
      expect(turn6.assistant.content).toContain(kbMarker);
      expect(turn6.assistant.content).toContain(kbDecision);
      expect(turn6.assistant.content).toContain(fileMarker);
      expect(turn6.assistant.content).not.toContain(WEB_SEARCH_MARKER);

      const turn7Prompt = generatePromptFromPrevious(
        prompts,
        promptChain,
        turn6,
        chooseRunVariant(suffix, 7, [
          `用中文简短说一下：刚才 Beacon Orchard 的公开状态来源是不是 ${observedWebUrl}？`,
          `换成中文确认一下：Beacon Orchard 的公开来源链接是不是 ${observedWebUrl}？`,
          `用中文帮我核对一句，刚才那个 Beacon Orchard 来源是不是这个链接：${observedWebUrl}？`,
        ]),
      );
      const turn7 = await continueConversation(page, turn1.sessionId, turn7Prompt);
      expect(turn7.assistant.content).toContain(WEB_SEARCH_SOURCE_URL);
      expect(turn7.assistant.content).toMatch(/[\u4e00-\u9fff]/);

      const turn8Prompt = generatePromptFromPrevious(
        prompts,
        promptChain,
        turn7,
        chooseRunVariant(suffix, 8, [
          `Back to English. Please do not repeat the private codes again. For the weird token ${noWebToken}, just say whether local or current public sources have evidence; if not, explain what a release candidate is.`,
          `Back in English: don't restate any private codes. Check whether ${noWebToken} appears in what we have or current public sources; if not, briefly explain release candidate.`,
          `English again. Keep the private codes out of the answer. For ${noWebToken}, say whether there is evidence locally or publicly, and if there is none, define release candidate.`,
        ]),
      );
      const turn8 = await continueConversation(page, turn1.sessionId, turn8Prompt);
      expect(turn8.assistant.content).toMatch(/release candidate|pre[- ]?release|candidate/i);
      expect(turn8.assistant.content).not.toContain(WEB_SEARCH_MARKER);
      expect(turn8.assistant.content).not.toContain(kbMarker);
      expect(turn8.assistant.content).not.toContain(fileMarker);
      expect(turn8.assistant.metadata?.citations ?? []).toEqual([]);
      const turn8Tools = await waitForToolCallEvidence(
        turn1.sessionId,
        turn8.user.turnId!,
        [SESSION_FILE_TOOL_NAME, WEB_SEARCH_MODEL_TOOL_NAME],
        { timeoutMs: TURN_TIMEOUT_MS },
      );
      expectToolCallOrder(
        turn8Tools,
        SESSION_FILE_TOOL_NAME,
        WEB_SEARCH_MODEL_TOOL_NAME,
      );

      const turn9Prompt = generatePromptFromPrevious(
        prompts,
        promptChain,
        turn8,
        chooseRunVariant(suffix, 9, [
          `Using your RC explanation, make a short handoff for Sarah with ${observedKbMarker}, ${observedFileMarker}, the Beacon source ${observedWebUrl}, and what RC means.`,
          `Turn that into a short note for Sarah. Include ${observedKbMarker}, ${observedFileMarker}, the Beacon source ${observedWebUrl}, and a plain-English RC meaning.`,
          `Draft Sarah's handoff: include the rollout marker ${observedKbMarker}, the attachment marker ${observedFileMarker}, the Beacon source ${observedWebUrl}, and one line on RC.`,
        ]),
      );
      const turn9 = await continueConversation(page, turn1.sessionId, turn9Prompt);
      expect(turn9.assistant.content).toContain(kbMarker);
      expect(turn9.assistant.content).toContain(fileMarker);
      expect(turn9.assistant.content).toContain(WEB_SEARCH_SOURCE_URL);
      expect(turn9.assistant.content).toMatch(/release candidate|pre[- ]?release|candidate|RC/i);

      const turn10Prompt = generatePromptFromPrevious(
        prompts,
        promptChain,
        turn9,
        chooseRunVariant(suffix, 10, [
          "Final check before I send that handoff: what exactly should I verify before noon from the attachment?",
          "Before I send this, what is the exact before-noon checklist item from the attached briefing?",
          "Last pass: what does the uploaded briefing say I need to verify before noon?",
        ]),
      );
      const turn10 = await continueConversation(page, turn1.sessionId, turn10Prompt);
      expect(turn10.assistant.content).toContain(fileAction);
      expectKnowledgeCitation(
        turn10.assistant.metadata?.citations ?? [],
        "SESSION_FILE",
        sessionFilename,
      );

      await attachPromptRealismEvidence(testInfo, prompts, promptChain);
      await attachTurnEvidence(testInfo, "priority-10turn-final-evidence", {
        sessionId: turn1.sessionId,
        turnCount: prompts.length,
        turn5Tools,
        turn8Tools,
        finalCitations: turn10.assistant.metadata?.citations ?? [],
      });
    } finally {
      if (originalAllowedTools) {
        await setAssistantAllowedTools(SYSTEM_ASSISTANT_ID, originalAllowedTools).catch(
          (error: unknown) => {
            console.warn(
              `Failed to restore assistant tools: ${error instanceof Error ? error.message : String(error)}`,
            );
          },
        );
      }
      if (originalBindingIds) {
        await setAssistantBindingsByApi(users.admin, originalBindingIds).catch(
          (error: unknown) => {
            console.warn(
              `Failed to restore assistant KB bindings: ${error instanceof Error ? error.message : String(error)}`,
            );
          },
        );
      }
      await deleteGeneratedKnowledgeBasesByApi(users.admin).catch((error: unknown) => {
        console.warn(
          `Failed to delete generated priority KBs: ${error instanceof Error ? error.message : String(error)}`,
        );
      });
    }
  });
});
