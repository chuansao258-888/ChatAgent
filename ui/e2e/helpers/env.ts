import path from "node:path";
import { randomUUID } from "node:crypto";

function readUrl(name: string, fallback: string): string {
  const value = process.env[name]?.trim() || fallback;
  const url = new URL(value);
  if (url.hostname === "127.0.0.1" || url.hostname === "::1") {
    throw new Error(
      `${name} must use localhost instead of ${url.hostname} so browser cookies are stable.`,
    );
  }
  return url.origin;
}

export const uiBaseUrl = readUrl(
  "PLAYWRIGHT_UI_BASE_URL",
  "http://localhost:5173",
);

export const apiBaseUrl = readUrl(
  "PLAYWRIGHT_API_BASE_URL",
  "http://localhost:8080",
);

export const routingFixtureBaseUrl = readUrl(
  "PLAYWRIGHT_ROUTING_FIXTURE_BASE_URL",
  "http://localhost:8890",
);

export const routingFixtureLateDelayMs = Number.parseInt(
  process.env.PLAYWRIGHT_ROUTING_FIXTURE_LATE_DELAY_MS?.trim() || "2500",
  10,
);

export const routingProviderMode =
  process.env.PLAYWRIGHT_ROUTING_PROVIDER_MODE?.trim() || "fixture";

export const liveWebSearchEnabled =
  process.env.PLAYWRIGHT_LIVE_WEB_SEARCH?.trim().toLowerCase() === "true";

export const e2eRunId =
  process.env.PLAYWRIGHT_E2E_RUN_ID?.trim() ||
  `e2e-${Date.now()}-${randomUUID().slice(0, 8)}`;

export const authDir = path.resolve(process.cwd(), ".auth");
export const normalStorageStatePath = path.join(authDir, "normal-user.json");
export const adminStorageStatePath = path.join(authDir, "admin-user.json");
export const e2eUsersPath = path.join(authDir, "e2e-users.json");
export const cleanupManifestPath = path.join(authDir, "cleanup-manifest.json");
