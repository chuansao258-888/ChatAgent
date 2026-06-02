import { render, screen, fireEvent } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import type { AgentTraceMetadata } from "../../../types";
import DeepThinkTracePanel from "./DeepThinkTracePanel.tsx";

const sampleTrace: AgentTraceMetadata = {
  mode: "DEEPTHINK",
  planning: {
    goal: "分析项目文档",
    stepCount: 2,
    steps: [
      { id: "S1", title: "收集信息", status: "COMPLETED" },
      { id: "S2", title: "分析对比", status: "COMPLETED" },
    ],
  },
  execution: {
    toolsUsed: ["knowledgeQuery", "webSearch"],
    totalToolCalls: 4,
    stepSummaries: [
      { stepId: "S1", conclusion: "找到了相关资料", toolCallCount: 2 },
      { stepId: "S2", conclusion: "完成了来源对比", toolCallCount: 2 },
    ],
  },
  reflection: {
    rounds: 1,
    status: "CONTINUE",
    summary: "覆盖目标 3/4",
  },
  verification: {
    passed: true,
    issueCount: 0,
    issues: [],
  },
};

describe("DeepThinkTracePanel", () => {
  it("renders collapsed by default with header summary", () => {
    render(<DeepThinkTracePanel trace={sampleTrace} />);

    expect(screen.getByText("深度思考详情")).toBeDefined();
    expect(screen.getByText(/2 个步骤/)).toBeDefined();
    expect(screen.getByText(/验证通过/)).toBeDefined();
  });

  it("expands to show all sections on click", () => {
    render(<DeepThinkTracePanel trace={sampleTrace} />);

    fireEvent.click(screen.getByText("深度思考详情"));

    // Planning section
    expect(screen.getByText("规划")).toBeDefined();
    expect(screen.getByText("收集信息")).toBeDefined();
    expect(screen.getByText("分析对比")).toBeDefined();

    // Execution section
    expect(screen.getByText("执行")).toBeDefined();
    expect(screen.getByText(/knowledgeQuery, webSearch/)).toBeDefined();
    expect(screen.getByText(/4 次调用/)).toBeDefined();

    // Reflection section
    expect(screen.getByText("反思")).toBeDefined();
    expect(screen.getByText(/1 轮/)).toBeDefined();
    expect(screen.getByText(/继续执行/)).toBeDefined();

    // Verification section (use getAllByText since header also contains "验证")
    const verificationElements = screen.getAllByText(/验证/);
    expect(verificationElements.length).toBeGreaterThanOrEqual(2);
  });

  it("shows step status icons for different statuses", () => {
    const traceWithFailed: AgentTraceMetadata = {
      ...sampleTrace,
      planning: {
        goal: "分析项目文档",
        stepCount: 3,
        steps: [
          { id: "S1", title: "Step 1", status: "COMPLETED" },
          { id: "S2", title: "Step 2", status: "FAILED" },
          { id: "S3", title: "Step 3", status: "SKIPPED" },
        ],
      },
    };

    render(<DeepThinkTracePanel trace={traceWithFailed} />);
    fireEvent.click(screen.getByText("深度思考详情"));

    expect(screen.getByText("Step 1")).toBeDefined();
    expect(screen.getByText("Step 2")).toBeDefined();
    expect(screen.getByText("Step 3")).toBeDefined();
  });

  it("renders verification issues when present", () => {
    const traceWithIssues: AgentTraceMetadata = {
      ...sampleTrace,
      verification: {
        passed: false,
        issueCount: 2,
        issues: [
          { type: "UNSUPPORTED_CLAIM", claim: "缺少来源的主张" },
          { type: "MISSING_SOURCE", claim: "关键数据未验证" },
        ],
      },
    };

    render(<DeepThinkTracePanel trace={traceWithIssues} />);
    fireEvent.click(screen.getByText("深度思考详情"));

    expect(screen.getByText(/发现 2 个问题/)).toBeDefined();
    expect(screen.getByText("UNSUPPORTED_CLAIM")).toBeDefined();
    expect(screen.getByText("缺少来源的主张")).toBeDefined();
    expect(screen.getByText("MISSING_SOURCE")).toBeDefined();
  });

  it("renders reflection REVISED status", () => {
    const traceWithRevision: AgentTraceMetadata = {
      ...sampleTrace,
      reflection: {
        rounds: 1,
        status: "REVISED",
        summary: "补充了来源验证",
      },
    };

    render(<DeepThinkTracePanel trace={traceWithRevision} />);
    fireEvent.click(screen.getByText("深度思考详情"));

    expect(screen.getByText(/已修订计划/)).toBeDefined();
    expect(screen.getByText("补充了来源验证")).toBeDefined();
  });

  it("renders reflection SKIPPED status", () => {
    const traceWithSkipped: AgentTraceMetadata = {
      ...sampleTrace,
      reflection: {
        rounds: 0,
        status: "SKIPPED",
        summary: "reflection disabled",
      },
    };

    render(<DeepThinkTracePanel trace={traceWithSkipped} />);
    fireEvent.click(screen.getByText("深度思考详情"));

    expect(screen.getByText(/已跳过/)).toBeDefined();
  });

  it("omits sections when trace data is absent", () => {
    const minimalTrace: AgentTraceMetadata = {
      mode: "DEEPTHINK",
    };

    render(<DeepThinkTracePanel trace={minimalTrace} />);
    fireEvent.click(screen.getByText("深度思考详情"));

    // Only the expanded container should be visible, no section headings
    expect(screen.queryByText("规划")).toBeNull();
    expect(screen.queryByText("执行")).toBeNull();
    expect(screen.queryByText("反思")).toBeNull();
    expect(screen.queryByText("验证")).toBeNull();
  });

  it("collapses back on second click", () => {
    render(<DeepThinkTracePanel trace={sampleTrace} />);

    fireEvent.click(screen.getByText("深度思考详情"));
    expect(screen.getByText("规划")).toBeDefined();

    fireEvent.click(screen.getByText("深度思考详情"));
    // Sections should be gone after collapse
    expect(screen.queryByText("规划")).toBeNull();
  });
});
