import {
  expect,
  request as playwrightRequest,
  type APIRequestContext,
  type Page,
} from "@playwright/test";
import { createHash } from "node:crypto";
import { apiBaseUrl, e2eRunId } from "./env";
import { getApi, postApi } from "./api";

export interface E2eUser {
  username: string;
  password: string;
}

export interface AuthResponse {
  accessToken: string;
  userId: string;
  username: string;
  role: string;
  avatar?: string;
}

export interface CurrentUserResponse {
  userId: string;
  username: string;
  role: string;
  avatar?: string;
}

const USERNAME_MAX_LENGTH = 64;
const EMAIL_DOMAIN = "@example.com";
const LOGIN_FORM_TIMEOUT_MS = 15_000;

export function buildE2eUsername(
  kind: "normal" | "admin",
  runId = e2eRunId,
): string {
  const prefix = `chatagent.${kind}.`;
  const maxTokenLength = USERNAME_MAX_LENGTH - prefix.length - EMAIL_DOMAIN.length;
  const normalizedRunId =
    runId.replace(/[^a-zA-Z0-9]/g, "").toLowerCase() || "run";
  const digest = createHash("sha1").update(runId).digest("hex").slice(0, 8);
  const rawBudget = Math.max(0, maxTokenLength - digest.length);
  const rawPart =
    rawBudget > 0 ? normalizedRunId.slice(-rawBudget) : "";
  const token = `${rawPart}${digest}`.slice(-maxTokenLength);
  return `${prefix}${token}${EMAIL_DOMAIN}`;
}

export function createE2eUser(kind: "normal" | "admin"): E2eUser {
  return {
    username: buildE2eUsername(kind),
    password: `E2e-${e2eRunId}-Password1`,
  };
}

export async function waitForBackendHealth(timeoutMs = 60_000): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  let lastError = "backend health check did not run";

  while (Date.now() < deadline) {
    const context = await playwrightRequest.newContext({ baseURL: apiBaseUrl });
    try {
      const response = await context.get("/health", { timeout: 5_000 });
      if (response.ok() && (await response.text()) === "ok") {
        return;
      }
      lastError = `HTTP ${response.status()}`;
    } catch (error) {
      lastError = error instanceof Error ? error.message : String(error);
    } finally {
      await context.dispose();
    }
    await new Promise((resolve) => setTimeout(resolve, 1_000));
  }

  throw new Error(
    `Backend is not healthy at ${apiBaseUrl}/health after ${timeoutMs}ms: ${lastError}`,
  );
}

export async function registerUser(
  request: APIRequestContext,
  user: E2eUser,
): Promise<AuthResponse> {
  return postApi<AuthResponse>(request, "/api/auth/register", user);
}

export async function loginUser(
  request: APIRequestContext,
  user: E2eUser,
): Promise<AuthResponse> {
  return postApi<AuthResponse>(request, "/api/auth/login", user);
}

export async function getCurrentUser(
  request: APIRequestContext,
  accessToken: string,
): Promise<CurrentUserResponse> {
  return getApi<CurrentUserResponse>(request, "/api/user/me", accessToken);
}

export async function loginThroughUi(
  page: Page,
  user: E2eUser,
  storageStatePath: string,
): Promise<void> {
  await page.goto("/chat");

  const currentUser = page.getByText(user.username);
  if (await currentUser.isVisible().catch(() => false)) {
    await page.context().storageState({ path: storageStatePath });
    return;
  }

  await openLoginForm(page, user.username);
  if (await currentUser.isVisible().catch(() => false)) {
    await page.context().storageState({ path: storageStatePath });
    return;
  }
  await page.getByPlaceholder("Email address").fill(user.username);
  await page.getByPlaceholder("Password").fill(user.password);

  const loginResponse = page.waitForResponse(
    (response) =>
      response.url().includes("/api/auth/login") && response.status() === 200,
  );
  await page.getByRole("button", { name: "Continue" }).click();
  await loginResponse;

  await expect(page.getByText(user.username)).toBeVisible();
  await page.context().storageState({ path: storageStatePath });
}

async function openLoginForm(page: Page, username: string): Promise<void> {
  const emailInput = page.getByPlaceholder("Email address");
  const deadline = Date.now() + LOGIN_FORM_TIMEOUT_MS;
  let lastError = "login button was not clicked";

  while (Date.now() < deadline) {
    if (await page.getByText(username).isVisible().catch(() => false)) {
      return;
    }
    if (await emailInput.isVisible().catch(() => false)) {
      return;
    }

    try {
      await page
        .getByRole("button", { name: "Log in", exact: true })
        .click({ timeout: 3_000 });
      await emailInput.waitFor({ state: "visible", timeout: 3_000 });
      return;
    } catch (error) {
      lastError = error instanceof Error ? error.message : String(error);
      await page.waitForTimeout(250);
    }
  }

  throw new Error(`Unable to open login form: ${lastError}`);
}
