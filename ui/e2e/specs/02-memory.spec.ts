import type { Page } from "@playwright/test";
import { expect, test } from "../fixtures";
import { loginThroughUi } from "../helpers/auth";
import {
  sendMessage,
  startChatAndWaitForAssistant,
  waitForAssistantTurn,
  waitForInputReady,
  type ChatTurnEvidence,
} from "../helpers/chat";
import {
  readMemoryEvidence,
  waitForMemoryEvidence,
  waitForTurnCompletion,
  type MemoryEvidence,
} from "../helpers/db";
import { e2eRunId, normalStorageStatePath } from "../helpers/env";
import {
  expectChineseDominant,
  expectEnglishDominant,
} from "../helpers/languageAssert";
import { readE2eUsers } from "../helpers/testUsers";

const TURN_TIMEOUT_MS = 180_000;
const MEMORY_EVIDENCE_TIMEOUT_MS = 300_000;
const MEMORY_JOURNEY_TIMEOUT_MS = 1_800_000;
const FOUR_PM_PATTERN = /\b4(?::00)?\s*(?:pm|p\.m\.)/i;

interface ConversationDriver {
  sessionId: string | null;
  runTurn(
    prompt: string,
    expected: Array<string | RegExp>,
    forbidden?: Array<string | RegExp>,
    language?: "english" | "chinese",
  ): Promise<ChatTurnEvidence>;
}

function expectContentMatch(content: string, expected: string | RegExp): void {
  if (typeof expected === "string") {
    expect(content).toContain(expected);
    return;
  }
  expect(content).toMatch(expected);
}

async function expectAssistantContentVisible(
  page: Page,
  messageId: string,
): Promise<void> {
  const message = page.locator(`[data-chat-message-id="${messageId}"]`);
  await expect(message).toBeVisible();
  await expect(message.locator(".chat-markdown")).toHaveText(/\S/);
}

function createConversationDriver(page: Page): ConversationDriver {
  let previousAssistantContent = "";
  const driver: ConversationDriver = {
    sessionId: null,
    async runTurn(prompt, expected, forbidden = [], language = "english") {
      const evidence = driver.sessionId
        ? await continueConversation(page, driver.sessionId, prompt)
        : await startChatAndWaitForAssistant(page, prompt, {
            mode: "REACT",
            timeoutMs: TURN_TIMEOUT_MS,
          });

      driver.sessionId = evidence.sessionId;
      if (!evidence.user.turnId) {
        throw new Error("Persisted E2E user message has no turnId.");
      }
      await waitForTurnCompletion(evidence.sessionId, evidence.user.turnId);
      expect(evidence.user.metadata?.executionMode).toBe("REACT");
      for (const requiredContent of expected) {
        expectContentMatch(evidence.assistant.content, requiredContent);
      }
      for (const forbiddenContent of forbidden) {
        if (typeof forbiddenContent === "string") {
          expect(evidence.assistant.content).not.toContain(forbiddenContent);
        } else {
          expect(evidence.assistant.content).not.toMatch(forbiddenContent);
        }
      }
      if (language === "chinese") {
        expectChineseDominant(evidence.assistant.content);
      } else {
        expectEnglishDominant(evidence.assistant.content);
      }
      expect(evidence.assistant.content.trim()).not.toEqual(
        previousAssistantContent.trim(),
      );
      previousAssistantContent = evidence.assistant.content;
      await expectAssistantContentVisible(page, evidence.assistant.id);
      return evidence;
    },
  };
  return driver;
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
  await expect(page.getByPlaceholder("Ask anything").last()).toBeEditable();
  return evidence;
}

function markerAppearsInSummary(
  evidence: MemoryEvidence,
  marker: string,
): boolean {
  return Boolean(
    evidence.summary?.markers[marker] ||
      evidence.summary?.segments.some((segment) => segment.markers[marker]),
  );
}

function markerHasIndexedItem(
  evidence: MemoryEvidence,
  marker: string,
): boolean {
  return evidence.items.some(
    (item) =>
      item.status === "active" &&
      item.indexStatus === "indexed" &&
      item.markers[marker],
  );
}

test.describe("@memory three-layer memory and multi-turn relevance", () => {
  test.use({ storageState: normalStorageStatePath });

  test("proves repeated L1, L2, and L3 recall without stale-turn answers", async ({
    page,
  }, testInfo) => {
    test.setTimeout(MEMORY_JOURNEY_TIMEOUT_MS);
    const users = await readE2eUsers();
    await loginThroughUi(page, users.normal, normalStorageStatePath);

    const suffix = e2eRunId.replace(/[^a-zA-Z0-9]/g, "").slice(-8).toUpperCase();
    const l2Incident = `INCIDENT-${suffix}`;
    const l2Room = `CEDAR-${suffix}`;
    const l3Badge = `CRIMSON-LANTERN-${suffix}`;
    const l3Project = `NORTHSTAR-${suffix}`;
    const allMemoryFacts = [l2Incident, l2Room, l3Badge, l3Project];
    const conversation = createConversationDriver(page);
    let stableThroughSeqNo = 0;

    await test.step("seed two session facts and two durable facts", async () => {
      await conversation.runTurn(
        `I'm preparing today's release review. For future reviews, please remember that I prefer the badge label ${l3Badge}. ` +
          `For this incident only, the identifier is ${l2Incident}. What should I bring to a short incident triage?`,
        [/incident|triage|timeline|log|owner|impact|status/i],
      );

      await conversation.runTurn(
        `The project's codename is ${l3Project}; please remember that for future conversations. ` +
          `Today's review is in room ${l2Room}. Could you suggest a concise three-part agenda for the meeting?`,
        [/agenda|meeting|review|decision|action|minute/i],
      );
    });

    await test.step("prove repeated L1 recall across topic switches", async () => {
      await conversation.runTurn(
        "I need to paste the incident identifier into the status update. What was it?",
        [l2Incident],
        [l2Room],
      );

      await conversation.runTurn(
        "A colleague is joining the review in person. Where are we meeting today?",
        [l2Room],
        [l2Incident],
      );

      await conversation.runTurn(
        "Please draft a one-sentence status update that mentions the incident identifier but leaves out the meeting location.",
        [l2Incident],
        [l2Room],
      );

      const evidence = await conversation.runTurn(
        "I'm walking over to the review now. Remind me which room I should go to.",
        [l2Room],
        [l2Incident],
      );
      stableThroughSeqNo = evidence.assistant.seqNo ?? 0;
      expect(stableThroughSeqNo).toBeGreaterThan(0);
    });

    await test.step("push every session-fact mention outside the L1 window", async () => {
      const followUps: Array<[string, RegExp]> = [
        [
          "What's one useful checklist item for reviewing a pull request?",
          /review|change|test|code|requirement/i,
        ],
        [
          "Give me one practical tip for taking meeting notes.",
          /note|decision|action|meeting|owner/i,
        ],
        [
          "How can I make a weekly status update easier to scan?",
          /brief|bullet|concise|status|section|heading/i,
        ],
        [
          "What is one benefit of keeping a test fixture small?",
          /test|fixture|repeat|determin|maintain|fast/i,
        ],
        [
          "What should a useful rollback note include?",
          /rollback|version|impact|step|trigger|owner/i,
        ],
        [
          "How do I keep an incident handoff clear for the next engineer?",
          /incident|handoff|owner|status|timeline|action|current state|engineer|context|tried|working|broken|focus/i,
        ],
        [
          "Suggest one way to reduce notification noise during focused work.",
          /notification|alert|mute|priority|batch|focus/i,
        ],
        [
          "What makes an action item genuinely useful?",
          /action|owner|deadline|specific|measur|clear/i,
        ],
      ];
      for (const [prompt, expectation] of followUps) {
        await conversation.runTurn(prompt, [expectation]);
      }
    });

    const sessionId = conversation.sessionId;
    expect(sessionId).toBeTruthy();
    if (!sessionId) {
      throw new Error("Memory journey did not create a chat session.");
    }

    const l2Evidence = await test.step("await and attach redacted L2 evidence", async () => {
      const evidence = await waitForMemoryEvidence(
        users.normal.username,
        sessionId,
        allMemoryFacts,
        (current) =>
          Boolean(
            current.summary &&
              current.summary.summarizedUntilSeqNo >= stableThroughSeqNo &&
              current.summary.segmentCount > 0 &&
              markerAppearsInSummary(current, l2Incident) &&
              markerAppearsInSummary(current, l2Room),
          ),
        { timeoutMs: MEMORY_EVIDENCE_TIMEOUT_MS },
      );
      await testInfo.attach("l2-memory-evidence", {
        body: JSON.stringify(evidence, null, 2),
        contentType: "application/json",
      });
      return evidence;
    });

    expect(l2Evidence.summary?.summarizedUntilSeqNo).toBeGreaterThanOrEqual(
      stableThroughSeqNo,
    );

    await test.step("prove repeated L2 recall after the facts leave L1", async () => {
      await conversation.runTurn(
        "I'm finishing the incident report. Which incident identifier did we use earlier today?",
        [l2Incident],
        [l2Room],
      );

      await conversation.runTurn(
        "I also need to tell a colleague where today's review was held. Which room was it?",
        [l2Room],
        [l2Incident],
      );
    });

    const l3Evidence = await test.step("await indexed L3 evidence and reject scope leakage", async () => {
      const evidence = await waitForMemoryEvidence(
        users.normal.username,
        sessionId,
        allMemoryFacts,
        (current) =>
          current.extractions.length > 0 &&
          current.extractions.every((entry) => entry.status === "completed") &&
          markerHasIndexedItem(current, l3Badge) &&
          markerHasIndexedItem(current, l3Project),
        { timeoutMs: MEMORY_EVIDENCE_TIMEOUT_MS },
      );

      expect(evidence.items.some((item) => item.markers[l2Incident])).toBe(false);
      expect(evidence.items.some((item) => item.markers[l2Room])).toBe(false);
      expect(
        evidence.items.some(
          (item) => item.type === "preference" && item.markers[l3Badge],
        ),
      ).toBe(true);
      expect(
        evidence.items.some(
          (item) => item.type === "fact" && item.markers[l3Project],
        ),
      ).toBe(true);

      await testInfo.attach("l3-memory-evidence", {
        body: JSON.stringify(evidence, null, 2),
        contentType: "application/json",
      });
      return evidence;
    });

    expect(l3Evidence.items.length).toBeGreaterThanOrEqual(2);

    await test.step("prove repeated L3 recall in a fresh session", async () => {
      const freshConversation = createConversationDriver(page);
      await freshConversation.runTurn(
        "I'm setting up the next review. What badge label do I usually prefer?",
        [l3Badge],
        [l3Project],
      );
      expect(freshConversation.sessionId).not.toBe(sessionId);

      await freshConversation.runTurn(
        "What codename did I give my project?",
        [l3Project],
        [l3Badge],
      );
    });

    const finalEvidence = await readMemoryEvidence(
      users.normal.username,
      sessionId,
      allMemoryFacts,
    );
    await testInfo.attach("memory-evidence-final", {
      body: JSON.stringify(finalEvidence, null, 2),
      contentType: "application/json",
    });
  });

  test("keeps a corrected plan coherent through a 20-turn bilingual conversation", async ({
    page,
  }, testInfo) => {
    test.setTimeout(MEMORY_JOURNEY_TIMEOUT_MS);
    const users = await readE2eUsers();
    await loginThroughUi(page, users.normal, normalStorageStatePath);

    const suffix = e2eRunId.replace(/[^a-zA-Z0-9]/g, "").slice(-8).toUpperCase();
    const project = `HARBOR-${suffix}`;
    const originalRoom = `MAPLE-${suffix}`;
    const currentRoom = `ORBIT-${suffix}`;
    const conversation = createConversationDriver(page);

    await test.step("establish a realistic project plan", async () => {
      await conversation.runTurn(
        `I'm coordinating the ${project} launch rehearsal. Priya owns it, the team originally booked room ${originalRoom}, ` +
          "and the rehearsal was pencilled in for Tuesday at 10:30. What should I check before inviting the reviewers?",
        [/review|invite|agenda|demo|material|availability|objective|attendee/i],
      );
      await conversation.runTurn(
        "I have a quick call with the delivery lead. Who is currently responsible for the rehearsal?",
        ["Priya"],
        [originalRoom],
      );
      await conversation.runTurn(
        "我还要和产品同事碰一下。正式发邀请之前，最值得先确认哪两件事？",
        [/确认|时间|参会|目标|材料|议程|演示|范围/],
        [],
        "chinese",
      );
    });

    await test.step("apply corrections and survive unrelated topic switches", async () => {
      await conversation.runTurn(
        `Facilities moved the rehearsal from ${originalRoom} to ${currentRoom}. Could you draft a short update for the attendees?`,
        [currentRoom],
      );
      await conversation.runTurn(
        "Before lunch, can you suggest a gentle five-minute desk stretch for tight shoulders?",
        [/shoulder|stretch|neck|arm|posture|breath/i],
        [project, originalRoom, currentRoom, "Priya"],
      );
      await conversation.runTurn(
        "I'm back to the rehearsal logistics now. Which room should I put on the calendar?",
        [currentRoom],
      );
      await conversation.runTurn(
        "Jon will represent support. Suggest a compact agenda that gives him space to raise operational concerns.",
        [/agenda|support|operational|concern|risk|question/i, "Jon"],
        [originalRoom],
      );
      await conversation.runTurn(
        "Priya has handed ownership to Elena. What should Elena do first to keep the rehearsal on track?",
        ["Elena", /review|confirm|align|agenda|owner|attendee|status|plan/i],
      );
    });

    await test.step("switch language without importing stale project content", async () => {
      await conversation.runTurn(
        "I'm making a simple dinner tonight. What's a practical way to keep roasted vegetables from turning soggy?",
        [/roast|heat|pan|space|dry|moisture|temperature|oil/i],
        [project, originalRoom, currentRoom, "Elena", "Jon"],
      );
      await conversation.runTurn(
        "如果有剩下的烤蔬菜，第二天怎么加热口感会更好？",
        [/烤箱|空气炸锅|平底锅|高温|加热|微波|水分/],
        [project, originalRoom, currentRoom],
        "chinese",
      );
      await conversation.runTurn(
        "Back on the launch rehearsal: who owns it now, and where is it being held?",
        ["Elena", currentRoom],
      );
    });

    await test.step("carry the latest constraints through the long tail", async () => {
      await conversation.runTurn(
        "The reviewers cannot make Tuesday, so the rehearsal is now Friday at 4 pm. Write a concise calendar note with the current owner and location.",
        ["Friday", FOUR_PM_PATTERN, "Elena", currentRoom],
      );
      await conversation.runTurn(
        "What is one useful question to ask at the end of a customer interview?",
        [/question|ask|customer|interview|problem|priority|missing|next/i],
        [project, originalRoom, currentRoom, "Elena", "Friday"],
      );
      await conversation.runTurn(
        "How can I make a short train journey more useful without opening my laptop?",
        [/read|listen|plan|note|reflect|audio|phone|rest/i],
        [project, originalRoom, currentRoom, "Elena", "Friday"],
      );
      await conversation.runTurn(
        "I need to brief my manager on the rehearsal. Give me the latest project name, owner, room, and time in a compact list.",
        [project, "Elena", currentRoom, "Friday", FOUR_PM_PATTERN],
        ["Priya", originalRoom, "Tuesday", "10:30"],
      );
      await conversation.runTurn(
        "Jon has only ten minutes available. How would you adapt the agenda so his support risks are still covered?",
        ["Jon", /ten|10/, /support|risk|operational|agenda|priorit/i],
        [originalRoom],
      );
      await conversation.runTurn(
        "推荐一种会前两分钟就能完成的放松方法，避免影响接下来的演示。",
        [/呼吸|肩|颈|放松|伸展|分钟|姿势/],
        [project, originalRoom, currentRoom, "Elena", "Jon"],
        "chinese",
      );
      await conversation.runTurn(
        "Please remind me of the current rehearsal logistics once more, then suggest one final readiness check.",
        [project, "Elena", currentRoom, "Friday", FOUR_PM_PATTERN],
      );
      await conversation.runTurn(
        "A reviewer asks why the room changed. We don't know the reason. How should I answer without inventing one?",
        [/don['’]?t know|not know|confirm|facilities|avoid|reason|verify/i],
      );
      await conversation.runTurn(
        "Last check before I send the invite: what are the four current logistics fields, and which person needs dedicated agenda time?",
        [project, "Elena", currentRoom, "Friday", FOUR_PM_PATTERN, "Jon"],
        ["Priya", originalRoom, "Tuesday", "10:30"],
      );
    });

    const sessionId = conversation.sessionId;
    expect(sessionId).toBeTruthy();
    if (!sessionId) {
      throw new Error("Long conversation did not create a chat session.");
    }
    const messages = await readMemoryEvidence(
      users.normal.username,
      sessionId,
      [project, originalRoom, currentRoom],
    );
    await testInfo.attach("long-conversation-memory-evidence", {
      body: JSON.stringify(messages, null, 2),
      contentType: "application/json",
    });
  });
});
