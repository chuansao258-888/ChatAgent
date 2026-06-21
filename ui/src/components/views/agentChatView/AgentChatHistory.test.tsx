import { render, screen, fireEvent } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
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

  it("keeps citation indexes, source order, and click navigation aligned", () => {
    const scrollIntoView = vi.fn();
    HTMLElement.prototype.scrollIntoView = scrollIntoView;
    const longName = `${"quarterly-evidence-".repeat(8)}.md`;
    const messages: ChatMessageVO[] = [
      makeMessage({
        id: "assistant-citations",
        content: "**Claim one** [1][2]",
        metadata: {
          citations: [
            {
              sourceType: "KNOWLEDGE_BASE",
              sourceId: "kb-1",
              documentId: "doc-1",
              documentName: longName,
              sectionPath: "Policy > Review",
              chunkIndex: 0,
              snippet: "A long snippet with **literal Markdown** and enough evidence to wrap without being hidden.",
              score: 0.91,
              scoreType: "reranker",
            },
            {
              sourceType: "SESSION_FILE",
              sourceId: "file-2",
              documentId: "doc-2",
              documentName: "notes.txt",
              sectionPath: "chunk[1]",
              chunkIndex: 1,
              snippet: "Second source snippet.",
              score: 0.82,
              scoreType: "retrieval",
            },
          ],
        },
      }),
    ];

    const { container } = render(<AgentChatHistory messages={messages} />);

    const inlineTags = screen.getAllByRole("button", { name: /Citation \d:/ });
    expect(inlineTags.map((tag) => tag.textContent)).toEqual(["[1]", "[2]"]);

    const sourceCards = Array.from(
      container.querySelectorAll<HTMLElement>("[id^='citation-source-assistant-citations-']"),
    );
    expect(sourceCards.map((card) => card.dataset.citationIndex)).toEqual(["1", "2"]);
    expect(sourceCards.map((card) => card.dataset.documentName)).toEqual([
      longName,
      "notes.txt",
    ]);
    expect(screen.getByText(/literal Markdown/).textContent).toContain("**literal Markdown**");
    expect(screen.getByText("Knowledge Base")).toBeDefined();
    expect(screen.getByText("Session File")).toBeDefined();
    expect(screen.getByText("Chunk 0")).toBeDefined();
    expect(screen.getByText("Score 0.91")).toBeDefined();

    fireEvent.click(inlineTags[1]);
    expect(scrollIntoView).toHaveBeenCalledTimes(1);
    expect(scrollIntoView.mock.instances[0]).toBe(sourceCards[1]);
  });

  it("navigates citations by rendered tag index when citations appear out of order", () => {
    const scrollIntoView = vi.fn();
    HTMLElement.prototype.scrollIntoView = scrollIntoView;
    const messages: ChatMessageVO[] = [
      makeMessage({
        id: "assistant-reordered-citations",
        content: "Second source first [2], then first source [1].",
        metadata: {
          citations: [
            {
              sourceType: "KNOWLEDGE_BASE",
              sourceId: "kb-1",
              documentId: "doc-1",
              documentName: "first.md",
              chunkIndex: 0,
              snippet: "First source.",
              score: 0.91,
              scoreType: "reranker",
            },
            {
              sourceType: "KNOWLEDGE_BASE",
              sourceId: "kb-1",
              documentId: "doc-2",
              documentName: "second.md",
              chunkIndex: 1,
              snippet: "Second source.",
              score: 0.87,
              scoreType: "reranker",
            },
          ],
        },
      }),
    ];

    const { container } = render(<AgentChatHistory messages={messages} />);

    const inlineTags = screen.getAllByRole("button", { name: /Citation \d:/ });
    expect(inlineTags.map((tag) => tag.textContent)).toEqual(["[2]", "[1]"]);
    expect(inlineTags.map((tag) => tag.getAttribute("data-citation-index"))).toEqual([
      "2",
      "1",
    ]);

    const sourceCards = Array.from(
      container.querySelectorAll<HTMLElement>(
        "[id^='citation-source-assistant-reordered-citations-']",
      ),
    );
    fireEvent.click(inlineTags[0]);
    fireEvent.click(inlineTags[1]);
    expect(scrollIntoView).toHaveBeenCalledTimes(2);
    expect(scrollIntoView.mock.instances[0]).toBe(sourceCards[1]);
    expect(scrollIntoView.mock.instances[1]).toBe(sourceCards[0]);
  });
});
