import { expect } from "@playwright/test";
import type { AgentTraceMetadata } from "../../src/types";

function countMatches(value: string, pattern: RegExp): number {
  return value.match(pattern)?.length ?? 0;
}

export function expectEnglishDominant(text: string): void {
  const cjkCount = countMatches(text, /[\u3400-\u9fff]/g);
  const latinWordCount = countMatches(text, /\b[A-Za-z]{2,}\b/g);

  expect(latinWordCount, `Expected English-dominant answer: ${text}`).toBeGreaterThan(
    0,
  );
  expect(cjkCount, `Expected little or no Chinese text: ${text}`).toBeLessThan(4);
}

export function expectChineseDominant(text: string): void {
  const cjkCount = countMatches(text, /[\u3400-\u9fff]/g);
  const latinWordCount = countMatches(text, /\b[A-Za-z]{2,}\b/g);

  expect(cjkCount, `Expected Chinese-dominant answer: ${text}`).toBeGreaterThanOrEqual(
    4,
  );
  expect(
    cjkCount,
    `Expected Chinese to dominate Latin words: ${text}`,
  ).toBeGreaterThanOrEqual(latinWordCount);
}

export function flattenAgentTraceText(
  trace: AgentTraceMetadata | undefined,
): string {
  if (!trace) {
    return "";
  }
  const parts: string[] = [];
  if (trace.planning?.goal) {
    parts.push(trace.planning.goal);
  }
  for (const step of trace.planning?.steps ?? []) {
    if (step.title) {
      parts.push(step.title);
    }
  }
  for (const summary of trace.execution?.stepSummaries ?? []) {
    if (summary.conclusion) {
      parts.push(summary.conclusion);
    }
  }
  if (trace.reflection?.summary) {
    parts.push(trace.reflection.summary);
  }
  for (const issue of trace.verification?.issues ?? []) {
    if (issue.claim) {
      parts.push(issue.claim);
    }
  }
  return parts.join("\n");
}

export function expectAgentTraceEnglishDominant(
  trace: AgentTraceMetadata | undefined,
): void {
  const text = flattenAgentTraceText(trace);
  expect(text.trim(), "Expected DeepThink trace text to be present").not.toEqual(
    "",
  );
  expectEnglishDominant(text);
}
