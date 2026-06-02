import { beforeEach, describe, expect, it } from "vitest";
import {
  getStoredExecutionMode,
  setStoredExecutionMode,
} from "./executionModeStorage.ts";

describe("executionModeStorage", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it("defaults to REACT when no mode is stored", () => {
    expect(getStoredExecutionMode("session-1")).toBe("REACT");
  });

  it("stores and restores DEEPTHINK per session", () => {
    setStoredExecutionMode("session-1", "DEEPTHINK");
    setStoredExecutionMode("session-2", "REACT");

    expect(getStoredExecutionMode("session-1")).toBe("DEEPTHINK");
    expect(getStoredExecutionMode("session-2")).toBe("REACT");
  });

  it("falls back to REACT for unknown stored values", () => {
    localStorage.setItem("chatagent:execution-mode:session-1", "DEEP_THINK");

    expect(getStoredExecutionMode("session-1")).toBe("REACT");
  });
});
