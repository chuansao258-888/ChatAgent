import { render, screen, fireEvent } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import type { AgentTraceMetadata, ChatMessageVO } from "../../../types";
import AgentChatHistory from "./AgentChatHistory.tsx";

function makeMessage(overrides: Partial<ChatMessageVO> & { id: string }): ChatMessageVO {
  return {
    sessionId: "session-1",
    role: "assistant",
    content: "",
    ...overrides,
  };
}

const sampleTrace: AgentTraceMetadata = {
  mode: "DEEPTHINK",
  planning: {
    goal: "分析问题",
    stepCount: 1,
    steps: [{ id: "S1", title: "搜索", status: "COMPLETED" }],
  },
  verification: {
    passed: true,
    issueCount: 0,
    issues: [],
  },
};

describe("AgentChatHistory", () => {
  it("filters out messages with metadata.internal = true", () => {
    const messages: ChatMessageVO[] = [
      makeMessage({ id: "user-1", role: "user", content: "用户问题" }),
      makeMessage({
        id: "internal-1",
        role: "assistant",
        content: "内部推理",
        metadata: { internal: true, deepThinkPhase: "EXECUTE", planStepId: "S1" },
      }),
      makeMessage({
        id: "internal-tool-1",
        role: "tool",
        content: "tool output",
        metadata: { internal: true, deepThinkPhase: "EXECUTE", planStepId: "S1" },
      }),
      makeMessage({
        id: "assistant-1",
        role: "assistant",
        content: "最终回答",
      }),
    ];

    render(
      <AgentChatHistory messages={messages} />,
    );

    // Internal messages should not appear
    expect(screen.queryByText("内部推理")).toBeNull();
    expect(screen.queryByText("tool output")).toBeNull();

    // User and final answer should appear
    expect(screen.getByText("用户问题")).toBeDefined();
    expect(screen.getByText("最终回答")).toBeDefined();
  });

  it("renders DeepThinkTracePanel for messages with agentTrace", () => {
    const messages: ChatMessageVO[] = [
      makeMessage({
        id: "assistant-deepthink-1",
        role: "assistant",
        content: "深度思考回答",
        metadata: { agentTrace: sampleTrace },
      }),
    ];

    render(
      <AgentChatHistory messages={messages} />,
    );

    // The trace panel header should be visible
    expect(screen.getByText("深度思考详情")).toBeDefined();

    // Expand to verify trace content
    fireEvent.click(screen.getByText("深度思考详情"));
    expect(screen.getByText("规划")).toBeDefined();
    expect(screen.getByText("搜索")).toBeDefined();
  });

  it("does not render trace panel for regular assistant messages", () => {
    const messages: ChatMessageVO[] = [
      makeMessage({
        id: "assistant-react-1",
        role: "assistant",
        content: "普通 ReAct 回答",
      }),
    ];

    render(
      <AgentChatHistory messages={messages} />,
    );

    expect(screen.getByText("普通 ReAct 回答")).toBeDefined();
    expect(screen.queryByText("深度思考详情")).toBeNull();
  });

  it("filters internal messages even when they arrive via SSE", () => {
    const messages: ChatMessageVO[] = [
      makeMessage({ id: "user-1", role: "user", content: "问题" }),
      makeMessage({
        id: "sse-internal-assistant",
        role: "assistant",
        content: "planning text",
        metadata: { internal: true },
      }),
      makeMessage({
        id: "sse-internal-tool",
        role: "tool",
        content: "tool result",
        metadata: { internal: true },
      }),
    ];

    render(
      <AgentChatHistory messages={messages} />,
    );

    // Only the user message should be visible
    expect(screen.getByText("问题")).toBeDefined();
    expect(screen.queryByText("planning text")).toBeNull();
    expect(screen.queryByText("tool result")).toBeNull();
  });
});
