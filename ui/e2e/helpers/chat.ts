import type { Page } from "@playwright/test";
import type {
  AgentExecutionMode,
  ChatMessageVO,
  MessageType,
} from "../../src/types";
import { apiBaseUrl, normalStorageStatePath } from "./env";

interface ApiEnvelope<T> {
  code: number;
  message: string;
  data: T;
}

interface BrowserAuthResponse {
  accessToken: string;
}

export interface ChatTurnEvidence {
  sessionId: string;
  user: ChatMessageVO;
  assistant: ChatMessageVO;
  messages: ChatMessageVO[];
}

const FINAL_RESPONSE_STABLE_MS = 2_000;

async function browserRefreshAccessToken(
  page: Page,
  storageStatePath?: string,
): Promise<string> {
  const accessToken = await page.evaluate(async ({ baseUrl }) => {
    type E2eWindow = Window & { __chatagentE2eAccessToken?: string };
    const e2eWindow = window as E2eWindow;
    if (e2eWindow.__chatagentE2eAccessToken) {
      return e2eWindow.__chatagentE2eAccessToken;
    }

    const response = await fetch(`${baseUrl}/api/auth/refresh`, {
      method: "POST",
      credentials: "include",
      headers: {
        "Content-Type": "application/json",
      },
    });
    const envelope = (await response.json()) as ApiEnvelope<BrowserAuthResponse>;
    if (!response.ok || envelope.code !== 200 || !envelope.data?.accessToken) {
      throw new Error(
        envelope.message || `Unable to refresh browser auth: HTTP ${response.status}`,
      );
    }
    e2eWindow.__chatagentE2eAccessToken = envelope.data.accessToken;
    return envelope.data.accessToken;
  }, { baseUrl: apiBaseUrl });
  if (storageStatePath) {
    await page.context().storageState({ path: storageStatePath });
  }
  return accessToken;
}

export async function browserApiGet<T>(page: Page, path: string): Promise<T> {
  const accessToken = await browserRefreshAccessToken(
    page,
    normalStorageStatePath,
  );
  return page.evaluate(
    async ({ baseUrl, requestPath, token }) => {
      const response = await fetch(`${baseUrl}/api${requestPath}`, {
        method: "GET",
        credentials: "include",
        headers: {
          Authorization: `Bearer ${token}`,
        },
      });
      const envelope = (await response.json()) as ApiEnvelope<T>;
      if (!response.ok || envelope.code !== 200) {
        throw new Error(
          envelope.message ||
            `Browser API GET ${requestPath} failed: HTTP ${response.status}`,
        );
      }
      return envelope.data;
    },
    { baseUrl: apiBaseUrl, requestPath: path, token: accessToken },
  );
}

export async function getChatMessagesFromBrowser(
  page: Page,
  sessionId: string,
): Promise<ChatMessageVO[]> {
  return browserApiGet<ChatMessageVO[]>(
    page,
    `/chat-messages/session/${sessionId}`,
  );
}

export function sessionIdFromUrl(url: string): string {
  const pathname = new URL(url).pathname;
  const match = pathname.match(/\/chat\/([^/]+)$/);
  if (!match?.[1]) {
    throw new Error(`Expected a chat session URL, got ${url}`);
  }
  return match[1];
}

export async function selectExecutionMode(
  page: Page,
  mode: AgentExecutionMode,
): Promise<void> {
  const targetLabel = mode === "DEEPTHINK" ? "DeepThink" : "ReAct";
  const currentButton = page.getByRole("button", {
    name: /^(ReAct|DeepThink)$/,
  });
  const currentLabel = (await currentButton.textContent())?.trim();
  if (currentLabel !== targetLabel) {
    await currentButton.click();
  }
  await page.getByRole("button", { name: targetLabel }).waitFor({
    state: "visible",
    timeout: 5_000,
  });
}

export async function sendMessage(page: Page, message: string): Promise<void> {
  const input = page.getByPlaceholder("Ask anything").last();
  await input.fill(message);
  await input.press("Enter");
}

function summarizeMessages(messages: ChatMessageVO[]): string {
  return messages
    .map((message) => {
      const content = (message.content ?? "").replace(/\s+/g, " ").slice(0, 80);
      return `${message.role}:${message.turnId ?? "-"}:${content}`;
    })
    .join(" | ");
}

function findUserMessage(
  messages: ChatMessageVO[],
  expectedContent: string,
): ChatMessageVO | undefined {
  const trimmed = expectedContent.trim();
  return [...messages]
    .reverse()
    .find(
      (message) =>
        message.role === "user" && (message.content ?? "").trim() === trimmed,
    );
}

function findAssistantForUser(
  messages: ChatMessageVO[],
  user: ChatMessageVO,
): ChatMessageVO | undefined {
  const hasUsableContent = (message: ChatMessageVO) =>
    message.role === "assistant" &&
    Boolean(message.content?.trim()) &&
    message.metadata?.internal !== true &&
    !(message.metadata?.toolCalls?.length);

  if (user.turnId) {
    return messages.find(
      (message) =>
        hasUsableContent(message) && message.turnId === user.turnId,
    );
  }

  return messages.find(
    (message) =>
      hasUsableContent(message) &&
      typeof message.seqNo === "number" &&
      typeof user.seqNo === "number" &&
      message.seqNo > user.seqNo,
  );
}

export async function waitForAssistantTurn(
  page: Page,
  sessionId: string,
  userContent: string,
  options: { timeoutMs?: number; requireAgentTrace?: boolean } = {},
): Promise<ChatTurnEvidence> {
  const timeoutMs = options.timeoutMs ?? 120_000;
  const deadline = Date.now() + timeoutMs;
  let lastMessages: ChatMessageVO[] = [];
  let stableFingerprint = "";
  let stableSince = 0;

  while (Date.now() < deadline) {
    lastMessages = await getChatMessagesFromBrowser(page, sessionId);
    const user = findUserMessage(lastMessages, userContent);
    if (user) {
      const assistant = findAssistantForUser(lastMessages, user);
      if (
        assistant &&
        (!options.requireAgentTrace || assistant.metadata?.agentTrace)
      ) {
        const fingerprint = [
          assistant.id,
          assistant.content ?? "",
          JSON.stringify(assistant.metadata?.agentTrace ?? null),
          JSON.stringify(assistant.metadata?.citations ?? null),
        ].join("\n");
        if (fingerprint !== stableFingerprint) {
          stableFingerprint = fingerprint;
          stableSince = Date.now();
        } else if (Date.now() - stableSince >= FINAL_RESPONSE_STABLE_MS) {
          return { sessionId, user, assistant, messages: lastMessages };
        }
      } else {
        stableFingerprint = "";
        stableSince = 0;
      }
    }
    await page.waitForTimeout(1_000);
  }

  throw new Error(
    `Timed out waiting for assistant response in ${sessionId}. Last messages: ${summarizeMessages(lastMessages)}`,
  );
}

export async function waitForInputReady(page: Page): Promise<void> {
  await page.getByPlaceholder("Ask anything").waitFor({
    state: "visible",
    timeout: 60_000,
  });
}

export async function startChatAndWaitForAssistant(
  page: Page,
  message: string,
  options: {
    mode?: AgentExecutionMode;
    timeoutMs?: number;
    requireAgentTrace?: boolean;
  } = {},
): Promise<ChatTurnEvidence> {
  await page.goto("/chat");
  await browserRefreshAccessToken(page, normalStorageStatePath);
  await selectExecutionMode(page, options.mode ?? "REACT");
  await sendMessage(page, message);
  await page.waitForURL(/\/chat\/[^/]+$/, { timeout: 30_000 });
  const sessionId = sessionIdFromUrl(page.url());
  const evidence = await waitForAssistantTurn(page, sessionId, message, {
    timeoutMs: options.timeoutMs,
    requireAgentTrace: options.requireAgentTrace,
  });
  await waitForInputReady(page);
  return evidence;
}

export function latestMessageByRole(
  messages: ChatMessageVO[],
  role: MessageType,
): ChatMessageVO | undefined {
  return [...messages].reverse().find((message) => message.role === role);
}
