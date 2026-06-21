import type { Page } from "@playwright/test";
import { normalStorageStatePath } from "../helpers/env";
import { loginThroughUi } from "../helpers/auth";
import { readE2eUsers } from "../helpers/testUsers";
import {
  startChatAndWaitForAssistant,
  waitForInputReady,
} from "../helpers/chat";
import {
  expectAgentTraceEnglishDominant,
  expectChineseDominant,
  expectEnglishDominant,
} from "../helpers/languageAssert";
import { expect, test } from "../fixtures";

const NORMAL_AGENT_TIMEOUT_MS = 180_000;
const DEEPTHINK_TIMEOUT_MS = 300_000;
const CORE_AGENT_JOURNEY_TIMEOUT_MS = 720_000;

async function expectNoPersistentUnstableState(page: Page): Promise<void> {
  await expect(
    page.getByText(/连接不稳|长时间未收到稳定回复|connection unstable/i),
  ).toHaveCount(0);
}

async function expectAssistantContentVisible(
  page: Page,
  messageId: string,
): Promise<void> {
  const message = page.locator(`[data-chat-message-id="${messageId}"]`);
  await expect(message).toBeVisible();
  await expect(message.locator(".chat-markdown")).toHaveText(/\S/);
}

test.describe("@core-agent language and execution modes", () => {
  test.use({ storageState: normalStorageStatePath });

  test("covers ReAct language contract and DeepThink trace", async ({ page }) => {
    test.setTimeout(CORE_AGENT_JOURNEY_TIMEOUT_MS);
    const users = await readE2eUsers();
    await loginThroughUi(page, users.normal, normalStorageStatePath);

    await test.step("English ReAct defaults to English and has no DeepThink trace", async () => {
      const prompt =
        "I'm choosing a verification strategy for a small web app. " +
        "In two or three sentences, explain one practical advantage of running Playwright with a visible browser and one trade-off.";

      const evidence = await startChatAndWaitForAssistant(page, prompt, {
        mode: "REACT",
        timeoutMs: NORMAL_AGENT_TIMEOUT_MS,
      });

      expect(evidence.user.metadata?.executionMode).toBe("REACT");
      expect(evidence.assistant.metadata?.agentTrace).toBeFalsy();
      expect(evidence.assistant.content).toMatch(/playwright|browser/i);
      expect(evidence.assistant.content).toMatch(
        /advantage|benefit|useful|observe|debug|visual|see/i,
      );
      expect(evidence.assistant.content).toMatch(
        /trade[- ]?off|drawback|cost|slower|resource|overhead/i,
      );
      expectEnglishDominant(evidence.assistant.content);
      await expectAssistantContentVisible(page, evidence.assistant.id);
      await expect(page.getByText("深度思考详情")).toHaveCount(0);
      await expectNoPersistentUnstableState(page);
    });

    await test.step("Chinese ReAct follows a Chinese latest message", async () => {
      const prompt =
        "我在安排明天的代码评审。请给我两条简短建议：一条关于会前准备，一条关于控制会议时间。";

      const evidence = await startChatAndWaitForAssistant(page, prompt, {
        mode: "REACT",
        timeoutMs: NORMAL_AGENT_TIMEOUT_MS,
      });

      expect(evidence.user.metadata?.executionMode).toBe("REACT");
      expect(evidence.assistant.metadata?.agentTrace).toBeFalsy();
      expect(evidence.assistant.content).toMatch(/准备|材料|变更|代码|上下文/);
      expect(evidence.assistant.content).toMatch(/时间|时长|议程|超时|分钟/);
      expectChineseDominant(evidence.assistant.content);
      await expectAssistantContentVisible(page, evidence.assistant.id);
      await expectNoPersistentUnstableState(page);
    });

    await test.step("Mixed-language prompt follows the latest dominant Chinese request", async () => {
      const prompt =
        "Background: our browser test is flaky after the login suite. " +
        "我现在要向团队解释这个问题，请用中文简要说明一个常见原因和一个优先排查动作。";

      const evidence = await startChatAndWaitForAssistant(page, prompt, {
        mode: "REACT",
        timeoutMs: NORMAL_AGENT_TIMEOUT_MS,
      });

      expect(evidence.user.metadata?.executionMode).toBe("REACT");
      expect(evidence.assistant.content).toMatch(/测试|用例|状态|登录|会话|时序|竞态/);
      expect(evidence.assistant.content).toMatch(/检查|排查|查看|复现|日志|追踪/);
      expectChineseDominant(evidence.assistant.content);
      await expectAssistantContentVisible(page, evidence.assistant.id);
      await expectNoPersistentUnstableState(page);
    });

    await test.step("DeepThink mode renders sanitized trace", async () => {
      const prompt =
        "A browser test passes by itself but fails after the login suite. " +
        "Compare two plausible root causes and recommend the first diagnostic step, without changing the code yet.";

      const evidence = await startChatAndWaitForAssistant(page, prompt, {
        mode: "DEEPTHINK",
        timeoutMs: DEEPTHINK_TIMEOUT_MS,
        requireAgentTrace: true,
      });

      expect(evidence.user.metadata?.executionMode).toBe("DEEPTHINK");
      expect(evidence.assistant.metadata?.agentTrace).toBeTruthy();
      expectAgentTraceEnglishDominant(evidence.assistant.metadata?.agentTrace);
      expect(evidence.assistant.content).toMatch(
        /cookie|session|auth|state|storage|timing|race|cleanup|isolation/i,
      );
      expect(evidence.assistant.content).toMatch(
        /inspect|trace|log|reproduce|compare|diagnos|run/i,
      );
      expectEnglishDominant(evidence.assistant.content);
      expect(evidence.assistant.content).not.toMatch(
        /chain[- ]of[- ]thought|hidden reasoning|system prompt|developer message/i,
      );

      await waitForInputReady(page);
      await expectAssistantContentVisible(page, evidence.assistant.id);
      await expect(page.getByText("深度思考详情")).toBeVisible();
      await expectNoPersistentUnstableState(page);
    });
  });
});
