import { describe, expect, it } from "vitest";
import { buildE2eUsername } from "./auth";

describe("buildE2eUsername", () => {
  it("keeps generated usernames within the database username limit", () => {
    const username = buildE2eUsername(
      "normal",
      "remaining-tiers-rag-isolated-20260620-0315-with-extra-debug-context",
    );

    expect(username.length).toBeLessThanOrEqual(64);
    expect(username).toMatch(/^chatagent\.normal\.[a-z0-9]+@example\.com$/);
  });

  it("keeps fixture roles distinct for the same run id", () => {
    const runId = "headed-e2e-run-with-a-deliberately-long-name";

    expect(buildE2eUsername("normal", runId)).not.toBe(
      buildE2eUsername("admin", runId),
    );
  });
});
