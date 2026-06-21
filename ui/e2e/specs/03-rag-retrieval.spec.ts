import {
  request as playwrightRequest,
  type Browser,
  type BrowserContext,
  type Page,
} from "@playwright/test";
import { createHash } from "node:crypto";
import { expect, test } from "../fixtures";
import { loginThroughUi, loginUser, type E2eUser } from "../helpers/auth";
import {
  deleteApi,
  getApi,
  patchApi,
  postApi,
  postMultipartApi,
  putApi,
} from "../helpers/api";
import {
  sendMessage,
  sessionIdFromUrl,
  startChatAndWaitForAssistant,
  waitForAssistantTurn,
  waitForInputReady,
  type ChatTurnEvidence,
} from "../helpers/chat";
import {
  readAssistantAllowedTools,
  readKnowledgeBaseBindingEvidence,
  readToolCallEvidence,
  setAssistantAllowedTools,
  waitForKnowledgeBaseEvidence,
  waitForSessionFileEvidence,
  waitForTurnCompletion,
  type KnowledgeBaseEvidence,
} from "../helpers/db";
import {
  apiBaseUrl,
  e2eRunId,
  normalStorageStatePath,
  uiBaseUrl,
} from "../helpers/env";
import { readE2eUsers } from "../helpers/testUsers";
import type { CitationMetadata } from "../../src/types";

const TURN_TIMEOUT_MS = 240_000;
const INGESTION_TIMEOUT_MS = 300_000;
const RAG_JOURNEY_TIMEOUT_MS = 1_800_000;
const GENERATED_KB_PREFIX = "E2E Evidence ";
const SYSTEM_ASSISTANT_ID = "3f9f84f7-2df0-4a5f-9c85-9f2d9b7aaf10";
const SAFE_DIRECT_TOOL_NAME = "dataBaseTool";
const SESSION_FILE_TOOL_NAME = "SessionFileSearchTool";

interface KnowledgeBaseListItem {
  id: string;
  name: string;
  description?: string | null;
  status?: string;
}

interface PublicRagFixture {
  url: string;
  name: string;
  mimeType: string;
  markers: string[];
}

interface DownloadedPublicRagFixture extends PublicRagFixture {
  buffer: Buffer;
  bytes: number;
  sha256Prefix: string;
}

const PUBLIC_RAG_FIXTURES: PublicRagFixture[] = [
  {
    url: "https://www.w3.org/WAI/WCAG20/Techniques/working-examples/PDF20/table.pdf",
    name: "public-w3c-table.pdf",
    mimeType: "application/pdf",
    markers: ["Mobility", "95.4%"],
  },
  {
    url: "https://raw.githubusercontent.com/datasets/continent-codes/master/data/continent-codes.csv",
    name: "public-continent-codes.csv",
    mimeType: "text/csv",
    markers: ["North America", "South America"],
  },
  {
    url: "https://raw.githubusercontent.com/rounakdatta/CorrectLy/master/sample.docx",
    name: "public-correctly-sample.docx",
    mimeType:
      "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    markers: ["sample document"],
  },
  {
    url: "https://raw.githubusercontent.com/frictionlessdata/datasets/main/files/excel/sample-1-sheet.xlsx",
    name: "public-frictionless-sample.xlsx",
    mimeType: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    markers: ["boolean", "four"],
  },
];

async function downloadPublicRagFixtures(): Promise<
  DownloadedPublicRagFixture[]
> {
  const context = await playwrightRequest.newContext();
  try {
    const downloaded: DownloadedPublicRagFixture[] = [];
    for (const fixture of PUBLIC_RAG_FIXTURES) {
      const response = await context.get(fixture.url, { timeout: 30_000 });
      if (!response.ok()) {
        throw new Error(
          `Failed to download public RAG fixture ${fixture.name}: HTTP ${response.status()} ${response.statusText()}`,
        );
      }
      const buffer = await response.body();
      const sha256Prefix = createHash("sha256")
        .update(buffer)
        .digest("hex")
        .slice(0, 16);
      downloaded.push({
        ...fixture,
        buffer,
        bytes: buffer.byteLength,
        sha256Prefix,
      });
    }
    return downloaded;
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

async function readAssistantBindingIdsByApi(
  adminUser: E2eUser,
): Promise<string[]> {
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

async function updateKnowledgeBaseByApi(
  adminUser: E2eUser,
  knowledgeBaseId: string,
  name: string,
  description: string,
) {
  const context = await playwrightRequest.newContext({ baseURL: apiBaseUrl });
  try {
    const auth = await loginUser(context, adminUser);
    await patchApi<void>(
      context,
      `/api/admin/knowledge-bases/${knowledgeBaseId}`,
      { name, description },
      auth.accessToken,
    );
  } finally {
    await context.dispose();
  }
}

async function readKnowledgeBaseByApi(
  adminUser: E2eUser,
  knowledgeBaseId: string,
): Promise<KnowledgeBaseListItem> {
  const context = await playwrightRequest.newContext({ baseURL: apiBaseUrl });
  try {
    const auth = await loginUser(context, adminUser);
    return await getApi<KnowledgeBaseListItem>(
      context,
      `/api/admin/knowledge-bases/${knowledgeBaseId}`,
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
    const remaining = await getApi<KnowledgeBaseListItem[]>(
      context,
      "/api/admin/knowledge-bases",
      auth.accessToken,
    );
    const remainingGenerated = remaining.filter((knowledgeBase) =>
      knowledgeBase.name.startsWith(GENERATED_KB_PREFIX),
    );
    if (remainingGenerated.length > 0) {
      throw new Error(
        `Generated knowledge bases were not fully cleaned: ${remainingGenerated
          .map((knowledgeBase) => knowledgeBase.name)
          .join(", ")}`,
      );
    }
  } finally {
    await context.dispose();
  }
}

async function createNormalContext(
  browser: Browser,
  username: string,
  password: string,
): Promise<{ context: BrowserContext; page: Page }> {
  const context = await browser.newContext({
    storageState: normalStorageStatePath,
    baseURL: uiBaseUrl,
    viewport: { width: 1366, height: 900 },
  });
  const page = await context.newPage();
  await loginThroughUi(
    page,
    { username, password },
    normalStorageStatePath,
  );
  return { context, page };
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
  if (!evidence.user.turnId) {
    throw new Error("Persisted RAG E2E user message has no turnId.");
  }
  await waitForTurnCompletion(sessionId, evidence.user.turnId);
  return evidence;
}

function referencedCitationIndexes(content: string): number[] {
  return [...new Set(
    Array.from(content.matchAll(/\[(\d+)\]/g), (match) => Number(match[1])),
  )].sort((left, right) => left - right);
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
  expect(await sourceCards.evaluateAll((cards) =>
    cards.map((card) => ({
      index: card.getAttribute("data-citation-index"),
      documentName: card.getAttribute("data-document-name"),
    })),
  )).toEqual(
    citations.map((citation, index) => ({
      index: String(index + 1),
      documentName:
        citation.documentName || citation.documentId || "Unknown source",
    })),
  );

  const inlineTags = messageRoot.getByRole("button", { name: /^Citation \d+:/ });
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
        root?.querySelectorAll<HTMLElement>(
          `button[data-citation-index]`,
        ) ?? [],
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

test.describe("@rag retrieval, citations, and session files", () => {
  test("proves unbound session-file search and bound KB evidence rendering", async ({
    browser,
  }, testInfo) => {
    test.setTimeout(RAG_JOURNEY_TIMEOUT_MS);
    const users = await readE2eUsers();
    await deleteGeneratedKnowledgeBasesByApi(users.admin);

    const suffix = e2eRunId.replace(/[^a-zA-Z0-9]/g, "").slice(-8).toUpperCase();
    const sessionMarker = `SESSION-FILE-CODE-${suffix}`;
    const kbPolicyMarker = `KB-LAUNCH-TOKEN-${suffix}`;
    const kbHtmlMarker = `KB-CEILING-CODE-${suffix}`;
    const sessionFilename = `session-evidence-${suffix}.txt`;
    const longMarkdownFilename = `${"quarterly-evidence-".repeat(7)}${suffix}.md`;
    const htmlFilename = `structured-policy-${suffix}.html`;
    const knowledgeBaseName = `E2E Evidence ${suffix}`;
    const publicFixtureMarkers = PUBLIC_RAG_FIXTURES.flatMap(
      (fixture) => fixture.markers,
    );
    const originalBindings = await readAssistantBindingIdsByApi(users.admin);
    let originalAllowedTools: string[] | null = null;
    let createdKnowledgeBaseId: string | null = null;
    let normalContext: BrowserContext | null = null;
    let cleanupError: Error | null = null;

    try {
      originalAllowedTools = (
        await readAssistantAllowedTools(SYSTEM_ASSISTANT_ID)
      ).allowedTools;
      await setAssistantAllowedTools(SYSTEM_ASSISTANT_ID, [
        SAFE_DIRECT_TOOL_NAME,
      ]);

      await test.step("remove KB bindings and prove session-file retrieval still works", async () => {
        await setAssistantBindingsByApi(users.admin, []);
        const normal = await createNormalContext(
          browser,
          users.normal.username,
          users.normal.password,
        );
        normalContext = normal.context;

        const firstTurn = await startChatAndWaitForAssistant(
          normal.page,
          "I'm organizing reference notes for this chat. What's one simple way to keep a short note easy to find later?",
          { mode: "REACT", timeoutMs: TURN_TIMEOUT_MS },
        );
        expect(firstTurn.assistant.content).toMatch(
          /note|reference|title|label|name|organize|search/i,
        );
        expect(firstTurn.user.turnId).toBeTruthy();
        await waitForTurnCompletion(
          firstTurn.sessionId,
          firstTurn.user.turnId!,
        );
        const firstTurnToolEvidence = await readToolCallEvidence(
          firstTurn.sessionId,
          firstTurn.user.turnId!,
        );
        await testInfo.attach("rag-first-turn-tool-evidence", {
          body: JSON.stringify(firstTurnToolEvidence, null, 2),
          contentType: "application/json",
        });
        expect(firstTurnToolEvidence.calls).toEqual([]);

        await setAssistantAllowedTools(SYSTEM_ASSISTANT_ID, [
          SESSION_FILE_TOOL_NAME,
        ]);

        await normal.page.locator("input[type='file']").setInputFiles({
          name: sessionFilename,
          mimeType: "text/plain",
          buffer: Buffer.from(
            `Session-only Evidence\nThe private session access code is ${sessionMarker}.\n`,
          ),
        });

        const sessionEvidence = await waitForSessionFileEvidence(
          firstTurn.sessionId,
          sessionFilename,
          [sessionMarker],
          (evidence) =>
            evidence.parseStatus === "COMPLETED" &&
            evidence.chunks.length > 0 &&
            evidence.markers[sessionMarker],
          { timeoutMs: INGESTION_TIMEOUT_MS },
        );
        await testInfo.attach("session-file-evidence", {
          body: JSON.stringify(sessionEvidence, null, 2),
          contentType: "application/json",
        });

        const answer = await continueConversation(
          normal.page,
          firstTurn.sessionId,
          `I just attached a short note for this chat. What is the private session access code from that note? ` +
            `Please include the source citation.`,
        );
        expect(answer.assistant.content).toContain(sessionMarker);
        const citations = answer.assistant.metadata?.citations ?? [];
        expect(citations.length).toBeGreaterThan(0);
        expect(citations.every((citation) => citation.sourceType === "SESSION_FILE")).toBe(true);
        expect(citations.some((citation) => citation.documentName === sessionFilename)).toBe(true);
        await assertCitationRendering(
          normal.page,
          answer.assistant.id,
          answer.assistant.content,
          citations,
        );

        await normal.context.close();
        normalContext = null;
      });

      await test.step("create, ingest, and bind Markdown plus structured HTML", async () => {
        createdKnowledgeBaseId = await createKnowledgeBaseByApi(
          users.admin,
          knowledgeBaseName,
          "Generated evidence for headed RAG verification.",
        );
        const updatedDescription =
          "Updated generated evidence for headed RAG verification.";
        await updateKnowledgeBaseByApi(
          users.admin,
          createdKnowledgeBaseId,
          knowledgeBaseName,
          updatedDescription,
        );
        const persistedKnowledgeBase = await readKnowledgeBaseByApi(
          users.admin,
          createdKnowledgeBaseId,
        );
        expect(persistedKnowledgeBase.name).toBe(knowledgeBaseName);
        expect(persistedKnowledgeBase.description).toBe(updatedDescription);
        const publicFixtures = await downloadPublicRagFixtures();
        await testInfo.attach("public-rag-fixture-downloads", {
          body: JSON.stringify(
            publicFixtures.map((fixture) => ({
              name: fixture.name,
              url: fixture.url,
              bytes: fixture.bytes,
              sha256Prefix: fixture.sha256Prefix,
              markers: fixture.markers,
            })),
            null,
            2,
          ),
          contentType: "application/json",
        });
        await uploadKnowledgeDocumentByApi(users.admin, createdKnowledgeBaseId, {
          name: longMarkdownFilename,
          mimeType: "text/markdown",
          buffer: Buffer.from(
            `# Launch Review\n\nThe Atlas launch token is **${kbPolicyMarker}**. ` +
              `This deliberately long sentence preserves Markdown evidence and forces the citation source snippet to wrap cleanly.\n`,
          ),
        });
        await uploadKnowledgeDocumentByApi(users.admin, createdKnowledgeBaseId, {
          name: htmlFilename,
          mimeType: "text/html",
          buffer: Buffer.from(
            `<html><body><main><h1>Compliance Ceiling</h1><section><h2>Approved Limit</h2>` +
              `<p>The compliance ceiling code is ${kbHtmlMarker}.</p></section></main></body></html>`,
          ),
        });
        for (const fixture of publicFixtures) {
          await uploadKnowledgeDocumentByApi(
            users.admin,
            createdKnowledgeBaseId,
            {
              name: fixture.name,
              mimeType: fixture.mimeType,
              buffer: fixture.buffer,
            },
          );
        }

        const kbEvidence = await waitForKnowledgeBaseEvidence(
          createdKnowledgeBaseId,
          [kbPolicyMarker, kbHtmlMarker, ...publicFixtureMarkers],
          (evidence) =>
            evidence.documents.length === 2 + PUBLIC_RAG_FIXTURES.length &&
            evidence.documents.every(
              (document) =>
                document.parseStatus === "COMPLETED" &&
                document.indexed &&
                document.chunks.length > 0,
            ) &&
            evidence.documents.some(
              (document) => document.markers[kbPolicyMarker],
            ) &&
            evidence.documents.some(
              (document) => document.markers[kbHtmlMarker],
            ) &&
            PUBLIC_RAG_FIXTURES.every((fixture) =>
              evidence.documents.some((document) =>
                fixture.markers.every((marker) => document.markers[marker]),
              ),
            ),
          { timeoutMs: INGESTION_TIMEOUT_MS },
        );
        await testInfo.attach("knowledge-base-evidence", {
          body: JSON.stringify(kbEvidence, null, 2),
          contentType: "application/json",
        });

        await setAssistantBindingsByApi(users.admin, [createdKnowledgeBaseId]);

        const bindingEvidence = await readKnowledgeBaseBindingEvidence(
          createdKnowledgeBaseId,
        );
        expect(bindingEvidence.assistantBindingCount).toBeGreaterThan(0);
        expect(bindingEvidence.intentBindings).toEqual([]);
        await testInfo.attach("assistant-bound-intent-unbound-evidence", {
          body: JSON.stringify(bindingEvidence, null, 2),
          contentType: "application/json",
        });
      });

      await test.step("retrieve assistant-bound, intent-unbound KB evidence and verify rendered citations", async () => {
        if (!createdKnowledgeBaseId) {
          throw new Error("Knowledge base fixture was not created.");
        }
        const normal = await createNormalContext(
          browser,
          users.normal.username,
          users.normal.password,
        );
        normalContext = normal.context;
        const answer = await startChatAndWaitForAssistant(
          normal.page,
          `For the internal launch review materials, what are the Atlas launch token and the compliance ceiling code? ` +
            `Give two short claims and cite the supporting sources.`,
          { mode: "REACT", timeoutMs: TURN_TIMEOUT_MS },
        );
        expect(answer.assistant.content).toContain(kbPolicyMarker);
        expect(answer.assistant.content).toContain(kbHtmlMarker);
        expect(referencedCitationIndexes(answer.assistant.content).length).toBeGreaterThanOrEqual(2);

        const citations = answer.assistant.metadata?.citations ?? [];
        expect(citations.length).toBeGreaterThanOrEqual(2);
        expect(citations.every((citation) => citation.sourceType === "KNOWLEDGE_BASE")).toBe(true);
        expect(citations.every((citation) => citation.sourceId === createdKnowledgeBaseId)).toBe(true);
        const kbEvidence = await waitForKnowledgeBaseEvidence(
          createdKnowledgeBaseId,
          [kbPolicyMarker, kbHtmlMarker],
          (evidence) =>
            evidence.documents.some(
              (document) => document.markers[kbPolicyMarker],
            ) &&
            evidence.documents.some(
              (document) => document.markers[kbHtmlMarker],
            ),
        );
        expectCitationDocumentsMatchEvidence(citations, kbEvidence);
        await assertCitationRendering(
          normal.page,
          answer.assistant.id,
          answer.assistant.content,
          citations,
        );

        await normal.context.close();
        normalContext = null;
      });

      await test.step("retrieve public PDF and CSV evidence through natural conversation", async () => {
        if (!createdKnowledgeBaseId) {
          throw new Error("Knowledge base fixture was not created.");
        }
        const normal = await createNormalContext(
          browser,
          users.normal.username,
          users.normal.password,
        );
        normalContext = normal.context;
        const answer = await startChatAndWaitForAssistant(
          normal.page,
          `I am checking the internal reference pack. What code is listed for North America, ` +
            `and which disability category has 3 participants and 3 completed ballots? Cite the supporting sources.`,
          { mode: "REACT", timeoutMs: TURN_TIMEOUT_MS },
        );
        expect(answer.assistant.content).toMatch(/\bNA\b/);
        expect(answer.assistant.content).toContain("Mobility");
        const citations = answer.assistant.metadata?.citations ?? [];
        expect(citations.length).toBeGreaterThanOrEqual(2);
        expect(citations.every((citation) => citation.sourceType === "KNOWLEDGE_BASE")).toBe(true);
        expect(citations.every((citation) => citation.sourceId === createdKnowledgeBaseId)).toBe(true);
        expect(
          citations.some(
            (citation) => citation.documentName === "public-continent-codes.csv",
          ),
        ).toBe(true);
        expect(
          citations.some(
            (citation) => citation.documentName === "public-w3c-table.pdf",
          ),
        ).toBe(true);
        const referencedDocuments = referencedCitationIndexes(
          answer.assistant.content,
        ).map((index) => citations[index - 1]?.documentName);
        expect(referencedDocuments).toContain("public-continent-codes.csv");
        expect(referencedDocuments).toContain("public-w3c-table.pdf");
        const kbEvidence = await waitForKnowledgeBaseEvidence(
          createdKnowledgeBaseId,
          ["North America", "Mobility"],
          (evidence) =>
            evidence.documents.some(
              (document) =>
                document.filename === "public-continent-codes.csv" &&
                document.markers["North America"],
            ) &&
            evidence.documents.some(
              (document) =>
                document.filename === "public-w3c-table.pdf" &&
                document.markers.Mobility,
            ),
        );
        expectCitationDocumentsMatchEvidence(citations, kbEvidence);
        await assertCitationRendering(
          normal.page,
          answer.assistant.id,
          answer.assistant.content,
          citations,
        );

        await normal.context.close();
        normalContext = null;
      });
    } finally {
      const cleanupErrors: string[] = [];
      await normalContext?.close().catch((error: unknown) => {
        cleanupErrors.push(
          `close normal browser context: ${error instanceof Error ? error.message : String(error)}`,
        );
      });
      await deleteGeneratedKnowledgeBasesByApi(users.admin).catch(
        (error: unknown) => {
          cleanupErrors.push(
            `delete generated knowledge bases: ${error instanceof Error ? error.message : String(error)}`,
          );
        },
      );
      await setAssistantBindingsByApi(users.admin, originalBindings).catch(
        (error: unknown) => {
          cleanupErrors.push(
            `restore assistant KB bindings: ${error instanceof Error ? error.message : String(error)}`,
          );
        },
      );
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
      if (cleanupErrors.length > 0) {
        cleanupError = new Error(
          `RAG E2E cleanup failed: ${cleanupErrors.join("; ")}`,
        );
      }
    }
    if (cleanupError) {
      throw cleanupError;
    }
  });
});

test.describe("@vlm configured multimodal session-file path", () => {
  test.use({ storageState: normalStorageStatePath });

  test("transcribes an uploaded image and recalls it with evidence", async ({
    page,
  }, testInfo) => {
    test.setTimeout(600_000);
    const users = await readE2eUsers();
    const originalBindings = await readAssistantBindingIdsByApi(users.admin);
    await setAssistantBindingsByApi(users.admin, []);

    try {
      await loginThroughUi(page, users.normal, normalStorageStatePath);
      const suffix = e2eRunId.replace(/[^a-zA-Z0-9]/g, "").slice(-8).toUpperCase();
      const visualCode = `VLM-CODE-${suffix}`;
      const filename = `vlm-evidence-${suffix}.png`;
      const imagePage = await page.context().newPage();
      await imagePage.setViewportSize({ width: 960, height: 360 });
      await imagePage.setContent(`
        <main style="box-sizing:border-box;width:960px;height:360px;padding:54px;background:#fff;color:#111;font-family:Arial,sans-serif">
          <h1 style="margin:0 0 44px;font-size:58px;letter-spacing:0">CHATAGENT HEADED VLM E2E</h1>
          <p style="margin:0;font-size:46px">Verification code: ${visualCode}</p>
        </main>
      `);
      const png = await imagePage.screenshot({ type: "png" });
      await imagePage.close();

      await page.locator("input[type='file']").setInputFiles({
        name: filename,
        mimeType: "image/png",
        buffer: png,
      });
      await page.waitForURL(/\/chat\/[^/]+$/, { timeout: 30_000 });
      const sessionId = sessionIdFromUrl(page.url());
      await waitForInputReady(page);

      const evidence = await waitForSessionFileEvidence(
        sessionId,
        filename,
        [visualCode],
        (current) =>
          ["COMPLETED", "FAILED", "REJECTED"].includes(current.parseStatus),
        { timeoutMs: INGESTION_TIMEOUT_MS },
      );
      await testInfo.attach("vlm-session-file-evidence", {
        body: JSON.stringify(evidence, null, 2),
        contentType: "application/json",
      });

      const matchingChunk = evidence.chunks.find(
        (chunk) => chunk.markers[visualCode],
      );
      if (
        evidence.parseStatus !== "COMPLETED" ||
        !matchingChunk ||
        matchingChunk.degraded !== false ||
        matchingChunk.engineId !== "vlm" ||
        matchingChunk.modelId !== "glm-4.6v-flash"
      ) {
        throw new Error(
          `VLM provider capability prerequisite failed: expected a non-degraded glm-4.6v-flash chunk containing the generated visual code, received ${JSON.stringify(evidence)}.`,
        );
      }

      const answer = await continueConversation(
        page,
        sessionId,
        `I uploaded an image with a verification code. What code is shown in the image? Please include the source citation.`,
      );
      expect(answer.assistant.content).toContain(visualCode);
      const citations = answer.assistant.metadata?.citations ?? [];
      expect(citations.length).toBeGreaterThan(0);
      expect(
        citations.some(
          (citation) =>
            citation.sourceType === "SESSION_FILE" &&
            citation.documentName === filename,
        ),
      ).toBe(true);
      await assertCitationRendering(
        page,
        answer.assistant.id,
        answer.assistant.content,
        citations,
      );
    } finally {
      await setAssistantBindingsByApi(users.admin, originalBindings);
    }
  });
});

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
      : undefined;
    expect(document).toBeDefined();
    expect(citation.documentName).toBe(document?.filename);
    expect(typeof citation.chunkIndex).toBe("number");
    expect(citation.snippet?.trim().length).toBeGreaterThan(0);
    if (citation.isFallback || citation.scoreType === "fallback") {
      expect(citation.isFallback).toBe(true);
      expect(citation.scoreType).toBe("fallback");
      expect(citation.score ?? null).toBeNull();
    } else {
      expect(typeof citation.score).toBe("number");
    }
  }
}
