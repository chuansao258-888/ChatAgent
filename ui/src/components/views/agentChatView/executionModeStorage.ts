import type { AgentExecutionMode } from "../../../types";

const STORAGE_PREFIX = "chatagent:execution-mode:";

export function getStoredExecutionMode(sessionId: string): AgentExecutionMode {
  const value = localStorage.getItem(`${STORAGE_PREFIX}${sessionId}`);
  return value === "DEEPTHINK" ? "DEEPTHINK" : "REACT";
}

export function setStoredExecutionMode(
  sessionId: string,
  executionMode: AgentExecutionMode,
) {
  localStorage.setItem(`${STORAGE_PREFIX}${sessionId}`, executionMode);
}
