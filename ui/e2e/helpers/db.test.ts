import { describe, expect, it } from "vitest";
import {
  hasCompletedToolEvidence,
  preservedActiveIntentVersion,
  type ToolCallEvidence,
} from "./db";

function evidence(
  status: string | null,
  responseId = "call-1",
): ToolCallEvidence {
  return {
    sessionId: "session-1",
    turnId: "turn-1",
    calls: [{ id: "call-1", name: "SessionFileSearchTool" }],
    responses: [
      {
        id: responseId,
        name: "SessionFileSearchTool",
        status,
        errorCode: null,
        truncated: null,
      },
    ],
  };
}

describe("hasCompletedToolEvidence", () => {
  it("accepts both enveloped and raw persisted responses", () => {
    expect(hasCompletedToolEvidence(evidence("ok"), ["SessionFileSearchTool"]))
      .toBe(true);
    expect(hasCompletedToolEvidence(evidence(null), ["SessionFileSearchTool"]))
      .toBe(true);
  });

  it("rejects explicit failures and mismatched response ids", () => {
    expect(hasCompletedToolEvidence(evidence("error"), ["SessionFileSearchTool"]))
      .toBe(false);
    expect(
      hasCompletedToolEvidence(evidence(null, "different-call"), [
        "SessionFileSearchTool",
      ]),
    ).toBe(false);
  });
});

describe("preservedActiveIntentVersion", () => {
  it("clears generated active versions and preserves user-owned versions", () => {
    expect(preservedActiveIntentVersion(12, [11, 12])).toBeNull();
    expect(preservedActiveIntentVersion(9, [11, 12])).toBe(9);
    expect(preservedActiveIntentVersion(null, [11, 12])).toBeNull();
  });
});
