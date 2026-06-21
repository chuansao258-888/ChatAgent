import {
  request as playwrightRequest,
  type APIRequestContext,
  type Browser,
  type BrowserContext,
  type Page,
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
  sessionIdFromUrl,
  startChatAndWaitForAssistant,
  waitForAssistantTurn,
  waitForInputReady,
} from "../helpers/chat";
import {
  cleanupGeneratedIntentArtifacts,
  readActiveIntentRuntimeEvidence,
  readKnowledgeBaseBindingEvidence,
  waitForKnowledgeBaseEvidence,
  waitForSessionFileEvidence,
  waitForToolCallEvidence,
  waitForTurnCompletion,
} from "../helpers/db";
import {
  adminStorageStatePath,
  apiBaseUrl,
  e2eRunId,
  normalStorageStatePath,
  uiBaseUrl,
} from "../helpers/env";
import { readE2eUsers } from "../helpers/testUsers";

const SYSTEM_ASSISTANT_ID = "3f9f84f7-2df0-4a5f-9c85-9f2d9b7aaf10";
const INTENT_PREFIX = "E2E Intent ";
const KB_PREFIX = "E2E Intent KB ";
const TURN_TIMEOUT_MS = 240_000;
const INGESTION_TIMEOUT_MS = 300_000;

interface KnowledgeBaseListItem {
  id: string;
  name: string;
}

interface IntentNodeVO {
  id: string;
  parentId?: string | null;
  version: number;
  status: "DRAFT" | "PUBLISHED";
  nodeLevel: "DOMAIN" | "CATEGORY" | "TOPIC";
  name: string;
  intentKind?: "KB" | "TOOL" | "SYSTEM" | null;
  allowedTools: string[];
  knowledgeBaseIds: string[];
}

interface IntentTreeResponse {
  activeVersion?: number | null;
  nodes: IntentNodeVO[];
}

interface CreateIntentNodeResponse {
  nodeId: string;
}

interface AdminApi {
  context: APIRequestContext;
  token: string;
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

async function createKnowledgeBase(
  api: AdminApi,
  name: string,
  marker: string,
  body: string,
): Promise<string> {
  const knowledgeBaseId = await postApi<string>(
    api.context,
    "/api/admin/knowledge-bases",
    { name, description: "Generated Phase 5 headed intent evidence." },
    api.token,
  );
  await postMultipartApi<void>(
    api.context,
    `/api/admin/knowledge-bases/${knowledgeBaseId}/documents/upload`,
    {
      file: {
        name: `${name.replace(/[^a-zA-Z0-9]/g, "-")}.md`,
        mimeType: "text/markdown",
        buffer: Buffer.from(`# Intent Evidence\n\n${body}\n\nMarker: ${marker}\n`),
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

async function setAssistantBindings(api: AdminApi, knowledgeBaseIds: string[]) {
  await putApi<void>(
    api.context,
    "/api/admin/assistant/knowledge-bases",
    { knowledgeBaseIds: [...new Set(knowledgeBaseIds)] },
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

test.describe("@intent intent-tree lifecycle and runtime boundaries", () => {
  test.use({ storageState: adminStorageStatePath });

  test("keeps drafts isolated, activates scoped snapshots, clarifies, and narrows tools", async ({
    browser,
    page,
  }, testInfo) => {
    test.setTimeout(1_200_000);
    const users = await readE2eUsers();
    const suffix = e2eRunId.replace(/[^a-zA-Z0-9]/g, "").slice(-8).toUpperCase();
    const generatedNamePrefix = `${INTENT_PREFIX}${suffix} `;
    const systemMarker = `INTENT-SYSTEM-${suffix}`;
    const clarificationMarker = `INTENT-CLARIFIED-${suffix}`;
    const targetMarker = `INTENT-TARGET-${suffix}`;
    const decoyMarker = `INTENT-DECOY-${suffix}`;
    const unboundMarker = `INTENT-UNBOUND-${suffix}`;
    const sessionMarker = `INTENT-FILE-${suffix}`;
    const forbiddenTool = `e2e_forbidden_${suffix.toLowerCase()}`;
    const systemQuery = `What is the standard isolation protocol for deployment ${suffix}?`;
    const scopedKbQuery = `For scoped launch policy ${suffix}, what approval code applies? Cite the source.`;
    const unboundQuery =
      "What independent archive code is recorded for the Nebula Cedar dossier? Cite the source.";
    const toolQuery = `I uploaded the audit file. What verification code is in the uploaded audit record ${suffix}?`;
    const ambiguousQuery = "operations";
    const rootAName = `${generatedNamePrefix}Operations Alpha`;
    const rootBName = `${generatedNamePrefix}Operations Beta`;
    const originalTreeApi = await createAdminApi();
    const originalTree = await readIntentTree(originalTreeApi);
    const originalActiveVersion = originalTree.activeVersion ?? null;
    const originalBindings = await readAssistantBindings(originalTreeApi);
    const generatedVersions: number[] = [];
    const generatedKnowledgeBaseIds: string[] = [];
    let cleanupError: Error | null = null;

    try {
      await deleteGeneratedKnowledgeBases(originalTreeApi);

      await test.step("exercise visible draft CRUD in the admin workspace", async () => {
        await loginThroughUi(page, users.admin, adminStorageStatePath);
        await page.goto("/admin/intent-tree");
        await expect(
          page.getByRole("heading", { name: "Intent routing workspace" }),
        ).toBeVisible();

        const temporaryName = `${generatedNamePrefix}Temporary`;
        const updatedName = `${generatedNamePrefix}Temporary Updated`;
        await page.getByRole("button", { name: "Add DOMAIN" }).click();
        await page.getByLabel("Name").fill(temporaryName);
        await page.getByLabel("Description").fill("Temporary headed CRUD node.");
        await page.getByLabel("Examples").fill(`temporary ${suffix}`);
        await page.getByRole("button", { name: "Create node" }).click();
        await expect(page.getByText("Intent node created.")).toBeVisible();
        await expect(page.getByText(temporaryName, { exact: true }).first()).toBeVisible();

        await page.getByText(temporaryName, { exact: true }).first().click();
        await page.getByRole("button", { name: "Edit selected" }).click();
        await page.getByLabel("Name").fill(updatedName);
        await page.getByRole("button", { name: "Save changes" }).click();
        await expect(page.getByText("Intent node updated.")).toBeVisible();
        await expect(page.getByText(updatedName, { exact: true }).first()).toBeVisible();

        await page.getByText(updatedName, { exact: true }).first().click();
        await page.locator("button.ant-btn-dangerous:visible").first().click();
        await page
          .locator(".ant-popconfirm")
          .getByRole("button", { name: "Delete", exact: true })
          .click();
        await expect(page.getByText("Intent node deleted.")).toBeVisible();
        await expect(page.getByText(updatedName, { exact: true })).toHaveCount(0);
      });

      const targetKbId = await createKnowledgeBase(
        originalTreeApi,
        `${KB_PREFIX}${suffix} Target`,
        targetMarker,
        `For scoped launch policy ${suffix}, the approval code is ${targetMarker}.`,
      );
      const decoyKbId = await createKnowledgeBase(
        originalTreeApi,
        `${KB_PREFIX}${suffix} Decoy`,
        decoyMarker,
        `For scoped launch policy ${suffix}, the decoy approval code is ${decoyMarker}.`,
      );
      const unboundKbId = await createKnowledgeBase(
        originalTreeApi,
        `${KB_PREFIX}${suffix} Unbound`,
        unboundMarker,
        `The independent archive code recorded for the Nebula Cedar dossier is ${unboundMarker}.`,
      );
      generatedKnowledgeBaseIds.push(targetKbId, decoyKbId, unboundKbId);
      await setAssistantBindings(originalTreeApi, [
        ...originalBindings,
        targetKbId,
        decoyKbId,
        unboundKbId,
      ]);

      const sharedRootExample = ambiguousQuery;
      const rootAId = await createIntentNode(originalTreeApi, {
        nodeLevel: "DOMAIN",
        name: rootAName,
        description: "Generated primary intent domain.",
        examples: [sharedRootExample, systemQuery, scopedKbQuery, toolQuery],
        enabled: true,
        sortOrder: 1000,
      });
      const categoryAId = await createIntentNode(originalTreeApi, {
        parentId: rootAId,
        nodeLevel: "CATEGORY",
        name: `${generatedNamePrefix}Runtime Requests`,
        examples: [systemQuery, scopedKbQuery, toolQuery],
        enabled: true,
        sortOrder: 0,
      });
      await createIntentNode(originalTreeApi, {
        parentId: categoryAId,
        nodeLevel: "TOPIC",
        name: `${generatedNamePrefix}Isolation Protocol`,
        examples: [systemQuery],
        intentKind: "SYSTEM",
        systemPromptOverride: systemMarker,
        enabled: true,
        sortOrder: 0,
      });
      const kbTopicId = await createIntentNode(originalTreeApi, {
        parentId: categoryAId,
        nodeLevel: "TOPIC",
        name: `${generatedNamePrefix}Scoped Launch Policy`,
        examples: [scopedKbQuery],
        intentKind: "KB",
        scopePolicy: "STRICT",
        enabled: true,
        sortOrder: 1,
      });
      await putApi<void>(
        originalTreeApi.context,
        `/api/admin/assistant/intent-tree/nodes/${kbTopicId}/knowledge-bases`,
        { knowledgeBaseIds: [targetKbId] },
        originalTreeApi.token,
      );
      await createIntentNode(originalTreeApi, {
        parentId: categoryAId,
        nodeLevel: "TOPIC",
        name: `${generatedNamePrefix}Uploaded Audit Record`,
        examples: [toolQuery],
        intentKind: "TOOL",
        allowedTools: ["SessionFileSearchTool", forbiddenTool],
        enabled: true,
        sortOrder: 2,
      });

      const rootBId = await createIntentNode(originalTreeApi, {
        nodeLevel: "DOMAIN",
        name: rootBName,
        description: "Generated clarification alternative.",
        examples: [sharedRootExample],
        enabled: true,
        sortOrder: 1001,
      });
      const categoryBId = await createIntentNode(originalTreeApi, {
        parentId: rootBId,
        nodeLevel: "CATEGORY",
        name: `${generatedNamePrefix}Alternative Requests`,
        examples: [rootBName, ambiguousQuery],
        enabled: true,
        sortOrder: 0,
      });
      await createIntentNode(originalTreeApi, {
        parentId: categoryBId,
        nodeLevel: "TOPIC",
        name: `${generatedNamePrefix}Alternative System Reply`,
        examples: [rootBName, ambiguousQuery],
        intentKind: "SYSTEM",
        systemPromptOverride: clarificationMarker,
        enabled: true,
        sortOrder: 0,
      });

      const rootCId = await createIntentNode(originalTreeApi, {
        nodeLevel: "DOMAIN",
        name: `${generatedNamePrefix}General Archives`,
        description: "Generated assistant-default knowledge fallback domain.",
        examples: [unboundQuery],
        enabled: true,
        sortOrder: 1002,
      });
      const categoryCId = await createIntentNode(originalTreeApi, {
        parentId: rootCId,
        nodeLevel: "CATEGORY",
        name: `${generatedNamePrefix}Archive Lookup`,
        examples: [unboundQuery],
        enabled: true,
        sortOrder: 0,
      });
      await createIntentNode(originalTreeApi, {
        parentId: categoryCId,
        nodeLevel: "TOPIC",
        name: `${generatedNamePrefix}Assistant Knowledge Fallback`,
        examples: [unboundQuery],
        intentKind: "KB",
        scopePolicy: "FALLBACK_ALLOWED",
        enabled: true,
        sortOrder: 0,
      });

      await test.step("prove draft-only nodes do not affect runtime", async () => {
        const normal = await openNormalChat(browser);
        try {
          const answer = await startChatAndWaitForAssistant(normal.page, systemQuery, {
            mode: "REACT",
            timeoutMs: TURN_TIMEOUT_MS,
          });
          expect(answer.assistant.content).not.toContain(systemMarker);
        } finally {
          await normal.context.close();
        }
      });

      await test.step("publish and activate the draft from the visible admin page", async () => {
        await page.goto("/admin/intent-tree");
        await page.getByRole("button", { name: "Refresh" }).click();
        await expect(page.getByText(rootAName, { exact: true }).first()).toBeVisible();
        await expect(page.getByText(rootBName, { exact: true }).first()).toBeVisible();
        await page.getByRole("button", { name: "Publish snapshot" }).click();
        await expect(page.getByText(/Published snapshot v\d+\./)).toBeVisible({
          timeout: 30_000,
        });
        const publishedTree = await readIntentTree(originalTreeApi);
        expect(publishedTree.activeVersion).toBeTruthy();
        generatedVersions.push(publishedTree.activeVersion!);
        await expect(
          page.getByText(`Active v${publishedTree.activeVersion}`, { exact: true }).first(),
        ).toBeVisible();
      });

      const runtimeEvidence = await readActiveIntentRuntimeEvidence(
        SYSTEM_ASSISTANT_ID,
      );
      const activeKbNode = runtimeEvidence.nodes.find(
        (node) => node.name === `${generatedNamePrefix}Scoped Launch Policy`,
      );
      const activeToolNode = runtimeEvidence.nodes.find(
        (node) => node.name === `${generatedNamePrefix}Uploaded Audit Record`,
      );
      const activeFallbackNode = runtimeEvidence.nodes.find(
        (node) => node.name === `${generatedNamePrefix}Assistant Knowledge Fallback`,
      );
      expect(activeKbNode?.scopePolicy).toBe("STRICT");
      expect(activeKbNode?.knowledgeBaseIds).toEqual([targetKbId]);
      expect(activeFallbackNode?.scopePolicy).toBe("FALLBACK_ALLOWED");
      expect(activeFallbackNode?.knowledgeBaseIds).toEqual([]);
      expect(activeToolNode?.allowedTools).toEqual(
        expect.arrayContaining(["SessionFileSearchTool", forbiddenTool]),
      );
      expect(runtimeEvidence.assistantAllowedTools).not.toContain(forbiddenTool);
      await testInfo.attach("active-intent-runtime-evidence", {
        body: JSON.stringify(runtimeEvidence, null, 2),
        contentType: "application/json",
      });

      await test.step("prove the published SYSTEM intent now serves runtime", async () => {
        const normal = await openNormalChat(browser);
        try {
          const answer = await startChatAndWaitForAssistant(normal.page, systemQuery, {
            mode: "REACT",
            timeoutMs: TURN_TIMEOUT_MS,
          });
          expect(answer.assistant.content.trim()).toBe(systemMarker);
        } finally {
          await normal.context.close();
        }
      });

      await test.step("scope a KB hit to its bound target and exclude the decoy", async () => {
        const normal = await openNormalChat(browser);
        try {
          const answer = await startChatAndWaitForAssistant(normal.page, scopedKbQuery, {
            mode: "REACT",
            timeoutMs: TURN_TIMEOUT_MS,
          });
          expect(answer.assistant.content).toContain(targetMarker);
          expect(answer.assistant.content).not.toContain(decoyMarker);
          const citations = answer.assistant.metadata?.citations ?? [];
          expect(citations.length).toBeGreaterThan(0);
          expect(citations.every((citation) => citation.sourceId === targetKbId)).toBe(true);
        } finally {
          await normal.context.close();
        }
      });

      await test.step("keep an assistant-bound but intent-unbound KB eligible", async () => {
        const bindingEvidence = await readKnowledgeBaseBindingEvidence(unboundKbId);
        expect(bindingEvidence.assistantBindingCount).toBeGreaterThan(0);
        expect(bindingEvidence.intentBindings).toEqual([]);
        const normal = await openNormalChat(browser);
        try {
          const answer = await startChatAndWaitForAssistant(normal.page, unboundQuery, {
            mode: "REACT",
            timeoutMs: TURN_TIMEOUT_MS,
          });
          expect(answer.assistant.content).toContain(unboundMarker);
          const citations = answer.assistant.metadata?.citations ?? [];
          expect(citations.some((citation) => citation.sourceId === unboundKbId)).toBe(true);
          await testInfo.attach("active-tree-unbound-kb-evidence", {
            body: JSON.stringify({ bindingEvidence, citations }, null, 2),
            contentType: "application/json",
          });
        } finally {
          await normal.context.close();
        }
      });

      await test.step("narrow TOOL intent callbacks to the assistant intersection", async () => {
        const normal = await openNormalChat(browser);
        try {
          const filename = `intent-audit-${suffix}.md`;
          await normal.page.locator("input[type='file']").setInputFiles({
            name: filename,
            mimeType: "text/markdown",
            buffer: Buffer.from(
              `# Audit Record\n\nThe verification code in this uploaded audit record is ${sessionMarker}.\n`,
            ),
          });
          await normal.page.waitForURL(/\/chat\/[^/]+$/, { timeout: 30_000 });
          const sessionId = sessionIdFromUrl(normal.page.url());
          await waitForInputReady(normal.page);
          await waitForSessionFileEvidence(
            sessionId,
            filename,
            [sessionMarker],
            (evidence) =>
              evidence.parseStatus === "COMPLETED" &&
              evidence.markers[sessionMarker],
            { timeoutMs: INGESTION_TIMEOUT_MS },
          );
          await sendMessage(normal.page, toolQuery);
          const answer = await waitForAssistantTurn(normal.page, sessionId, toolQuery, {
            timeoutMs: TURN_TIMEOUT_MS,
          });
          if (!answer.user.turnId) {
            throw new Error("Intent TOOL turn did not persist a turnId.");
          }
          await waitForTurnCompletion(sessionId, answer.user.turnId);
          const toolEvidence = await waitForToolCallEvidence(
            sessionId,
            answer.user.turnId,
            ["SessionFileSearchTool"],
          );
          expect(toolEvidence.calls.some((call) => call.name === forbiddenTool)).toBe(false);
          expect(answer.assistant.content).toContain(sessionMarker);
          await testInfo.attach("intent-tool-narrowing-evidence", {
            body: JSON.stringify({ runtimeEvidence, toolEvidence }, null, 2),
            contentType: "application/json",
          });
        } finally {
          await normal.context.close();
        }
      });

      await test.step("clarify an ambiguous root, retry unclear replies, and clear pending state", async () => {
        const normal = await openNormalChat(browser);
        try {
          const first = await startChatAndWaitForAssistant(normal.page, ambiguousQuery, {
            mode: "REACT",
            timeoutMs: TURN_TIMEOUT_MS,
          });
          expect(first.assistant.content).toContain(rootAName);
          expect(first.assistant.content).toContain(rootBName);
          expect(first.assistant.content).toMatch(/Please choose|confirm/i);

          const outOfRangeReply = "Maybe the third one.";
          await sendMessage(normal.page, outOfRangeReply);
          const outOfRangeRetry = await waitForAssistantTurn(
            normal.page,
            first.sessionId,
            outOfRangeReply,
            { timeoutMs: TURN_TIMEOUT_MS },
          );
          expect(outOfRangeRetry.assistant.content).toContain(rootAName);
          expect(outOfRangeRetry.assistant.content).toContain(rootBName);
          expect(outOfRangeRetry.assistant.content).toMatch(
            /could not identify|reply with its number|name/i,
          );

          const unclearReply = "I'm not sure which operations area fits yet.";
          await sendMessage(normal.page, unclearReply);
          const retry = await waitForAssistantTurn(
            normal.page,
            first.sessionId,
            unclearReply,
            { timeoutMs: TURN_TIMEOUT_MS },
          );
          expect(retry.assistant.content).toContain(rootAName);
          expect(retry.assistant.content).toContain(rootBName);
          expect(retry.assistant.content).toMatch(/could not identify|reply with its number|name/i);

          const selectionReply = "第二项吧。";
          await sendMessage(normal.page, selectionReply);
          const resolved = await waitForAssistantTurn(
            normal.page,
            first.sessionId,
            selectionReply,
            { timeoutMs: TURN_TIMEOUT_MS },
          );
          expect(resolved.assistant.content.trim()).toBe(clarificationMarker);

          const followUp = "What is one practical way to keep a handoff note clear?";
          await sendMessage(normal.page, followUp);
          const afterResolution = await waitForAssistantTurn(
            normal.page,
            first.sessionId,
            followUp,
            { timeoutMs: TURN_TIMEOUT_MS },
          );
          expect(afterResolution.assistant.content).not.toContain(clarificationMarker);
          expect(afterResolution.assistant.content).not.toContain(rootAName);
          expect(afterResolution.assistant.content).not.toContain(rootBName);
          expect(afterResolution.assistant.content).toMatch(/handoff|note|clear|context|owner|next/i);

          await testInfo.attach("intent-clarification-lifecycle-evidence", {
            body: JSON.stringify(
              {
                firstClarification: {
                  userTurnId: first.user.turnId,
                  assistantMessageId: first.assistant.id,
                  containsBothCandidates:
                    first.assistant.content.includes(rootAName) &&
                    first.assistant.content.includes(rootBName),
                },
                retryClarification: {
                  userTurnId: retry.user.turnId,
                  assistantMessageId: retry.assistant.id,
                  containsBothCandidates:
                    retry.assistant.content.includes(rootAName) &&
                    retry.assistant.content.includes(rootBName),
                },
                outOfRangeClarification: {
                  userTurnId: outOfRangeRetry.user.turnId,
                  assistantMessageId: outOfRangeRetry.assistant.id,
                  containsBothCandidates:
                    outOfRangeRetry.assistant.content.includes(rootAName) &&
                    outOfRangeRetry.assistant.content.includes(rootBName),
                },
                resolvedSelection: {
                  userTurnId: resolved.user.turnId,
                  assistantMessageId: resolved.assistant.id,
                  resolvedToSystemMarker:
                    resolved.assistant.content.trim() === clarificationMarker,
                },
                pendingClearedProbe: {
                  userTurnId: afterResolution.user.turnId,
                  assistantMessageId: afterResolution.assistant.id,
                  noClarificationCandidatesShown:
                    !afterResolution.assistant.content.includes(rootAName) &&
                    !afterResolution.assistant.content.includes(rootBName),
                },
              },
              null,
              2,
            ),
            contentType: "application/json",
          });
        } finally {
          await normal.context.close();
        }
      });

      await test.step("resolve a fresh clarification by candidate name", async () => {
        const normal = await openNormalChat(browser);
        try {
          const first = await startChatAndWaitForAssistant(normal.page, ambiguousQuery, {
            mode: "REACT",
            timeoutMs: TURN_TIMEOUT_MS,
          });
          expect(first.assistant.content).toContain(rootAName);
          expect(first.assistant.content).toContain(rootBName);

          const selectionReply = `Let's use ${rootBName}.`;
          await sendMessage(normal.page, selectionReply);
          const resolved = await waitForAssistantTurn(
            normal.page,
            first.sessionId,
            selectionReply,
            { timeoutMs: TURN_TIMEOUT_MS },
          );
          expect(resolved.assistant.content.trim()).toBe(clarificationMarker);

          await testInfo.attach("intent-clarification-name-selection-evidence", {
            body: JSON.stringify(
              {
                firstClarification: {
                  userTurnId: first.user.turnId,
                  assistantMessageId: first.assistant.id,
                  containsBothCandidates:
                    first.assistant.content.includes(rootAName) &&
                    first.assistant.content.includes(rootBName),
                },
                resolvedSelection: {
                  userTurnId: resolved.user.turnId,
                  assistantMessageId: resolved.assistant.id,
                  selectedByCandidateName: selectionReply.includes(rootBName),
                  resolvedToSystemMarker:
                    resolved.assistant.content.trim() === clarificationMarker,
                },
              },
              null,
              2,
            ),
            contentType: "application/json",
          });
        } finally {
          await normal.context.close();
        }
      });

      await test.step("resolve a fresh clarification by natural English ordinal", async () => {
        const normal = await openNormalChat(browser);
        try {
          const first = await startChatAndWaitForAssistant(normal.page, ambiguousQuery, {
            mode: "REACT",
            timeoutMs: TURN_TIMEOUT_MS,
          });
          expect(first.assistant.content).toContain(rootAName);
          expect(first.assistant.content).toContain(rootBName);

          const selectionReply = "Let's go with the second option.";
          await sendMessage(normal.page, selectionReply);
          const resolved = await waitForAssistantTurn(
            normal.page,
            first.sessionId,
            selectionReply,
            { timeoutMs: TURN_TIMEOUT_MS },
          );
          expect(resolved.assistant.content.trim()).toBe(clarificationMarker);

          await testInfo.attach("intent-clarification-english-ordinal-evidence", {
            body: JSON.stringify(
              {
                firstClarification: {
                  userTurnId: first.user.turnId,
                  assistantMessageId: first.assistant.id,
                  containsBothCandidates:
                    first.assistant.content.includes(rootAName) &&
                    first.assistant.content.includes(rootBName),
                },
                resolvedSelection: {
                  userTurnId: resolved.user.turnId,
                  assistantMessageId: resolved.assistant.id,
                  selectedByNaturalEnglishOrdinal: selectionReply,
                  resolvedToSystemMarker:
                    resolved.assistant.content.trim() === clarificationMarker,
                },
              },
              null,
              2,
            ),
            contentType: "application/json",
          });
        } finally {
          await normal.context.close();
        }
      });
    } finally {
      const errors: string[] = [];
      await cleanupGeneratedIntentArtifacts(
        SYSTEM_ASSISTANT_ID,
        generatedNamePrefix,
        generatedVersions,
        originalActiveVersion,
      ).catch((error: unknown) => {
        errors.push(
          `intent cleanup: ${error instanceof Error ? error.message : String(error)}`,
        );
      });
      await setAssistantBindings(originalTreeApi, originalBindings).catch(
        (error: unknown) => {
          errors.push(
            `assistant binding restore: ${error instanceof Error ? error.message : String(error)}`,
          );
        },
      );
      for (const knowledgeBaseId of generatedKnowledgeBaseIds) {
        await deleteApi<void>(
          originalTreeApi.context,
          `/api/admin/knowledge-bases/${knowledgeBaseId}`,
          originalTreeApi.token,
        ).catch((error: unknown) => {
          errors.push(
            `KB cleanup ${knowledgeBaseId}: ${error instanceof Error ? error.message : String(error)}`,
          );
        });
      }
      await originalTreeApi.context.dispose();
      if (errors.length > 0) {
        cleanupError = new Error(errors.join("; "));
      }
    }
    if (cleanupError) {
      throw cleanupError;
    }
  });
});
