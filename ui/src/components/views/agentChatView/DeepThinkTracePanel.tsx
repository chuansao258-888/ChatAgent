import React, { useState } from "react";
import {
  CheckCircleOutlined,
  CloseCircleOutlined,
  DownOutlined,
  ExclamationCircleOutlined,
  RightOutlined,
  ThunderboltOutlined,
  SyncOutlined,
  WarningOutlined,
} from "@ant-design/icons";
import type { AgentTraceMetadata } from "../../../types";

interface DeepThinkTracePanelProps {
  trace: AgentTraceMetadata;
}

const StepStatusIcon: React.FC<{ status?: string }> = ({ status }) => {
  switch (status) {
    case "COMPLETED":
      return <CheckCircleOutlined className="text-emerald-400" />;
    case "PARTIAL":
      return <WarningOutlined className="text-amber-400" />;
    case "FAILED":
      return <CloseCircleOutlined className="text-rose-400" />;
    case "SKIPPED":
      return (
        <span className="text-xs text-slate-500" title="Skipped">
          ⏭️
        </span>
      );
    default:
      return <CheckCircleOutlined className="text-slate-500" />;
  }
};

const DeepThinkTracePanel: React.FC<DeepThinkTracePanelProps> = ({ trace }) => {
  const [expanded, setExpanded] = useState(false);

  const planning = trace.planning;
  const execution = trace.execution;
  const reflection = trace.reflection;
  const verification = trace.verification;

  // Build a short summary for the collapsed header
  const headerParts: string[] = [];
  if (planning) {
    headerParts.push(`${planning.stepCount ?? 0} 个步骤`);
  }
  if (execution) {
    headerParts.push(`${execution.totalToolCalls ?? 0} 次工具调用`);
  }
  if (verification) {
    headerParts.push(verification.passed ? "验证通过" : "验证发现问题");
  }
  const headerSummary = headerParts.length > 0 ? headerParts.join(" · ") : "深度思考";

  return (
    <div className="mt-3 rounded-section border border-white/6 bg-white/[0.02] shadow-chat-panel">
      {/* Header toggle */}
      <div
        className="flex cursor-pointer items-center gap-2 px-4 py-2.5 text-sm transition-colors hover:bg-white/[0.02]"
        onClick={() => setExpanded((v) => !v)}
      >
        {expanded ? (
          <DownOutlined className="text-xs text-slate-500" />
        ) : (
          <RightOutlined className="text-xs text-slate-500" />
        )}
        <span className="text-slate-400">🧠</span>
        <span className="font-medium text-slate-300">深度思考详情</span>
        <span className="text-slate-500">·</span>
        <span className="text-xs text-slate-400">{headerSummary}</span>
      </div>

      {expanded ? (
        <div className="border-t border-white/6 px-4 py-3 space-y-3">
          {/* Planning section */}
          {planning ? (
            <div>
              <div className="mb-1.5 flex items-center gap-2 text-xs uppercase tracking-wider text-slate-400">
                <span>📋</span>
                <span>规划</span>
                <span className="text-slate-600">
                  {planning.stepCount ?? 0} 个步骤
                </span>
              </div>
              {planning.goal ? (
                <p className="mb-2 text-xs text-slate-500">
                  目标：{planning.goal}
                </p>
              ) : null}
              {planning.steps && planning.steps.length > 0 ? (
                <div className="space-y-1 pl-2">
                  {planning.steps.map((step) => (
                    <div
                      key={step.id ?? ""}
                      className="flex items-center gap-2 text-xs"
                    >
                      <StepStatusIcon status={step.status} />
                      <span className="font-mono text-slate-500">
                        {step.id}
                      </span>
                      <span className="text-slate-300">{step.title}</span>
                    </div>
                  ))}
                </div>
              ) : null}
            </div>
          ) : null}

          {/* Execution section */}
          {execution ? (
            <div>
              <div className="mb-1.5 flex items-center gap-2 text-xs uppercase tracking-wider text-slate-400">
                <ThunderboltOutlined className="text-amber-400" />
                <span>执行</span>
              </div>
              <div className="pl-2 text-xs text-slate-300">
                {execution.toolsUsed && execution.toolsUsed.length > 0 ? (
                  <span>
                    使用了{" "}
                    <span className="font-mono text-sky-300">
                      {execution.toolsUsed.join(", ")}
                    </span>{" "}
                    ({execution.totalToolCalls ?? 0} 次调用)
                  </span>
                ) : (
                  <span className="text-slate-500">
                    {execution.totalToolCalls ?? 0} 次工具调用
                  </span>
                )}
              </div>
              {execution.stepSummaries && execution.stepSummaries.length > 0 ? (
                <div className="mt-1.5 space-y-1 pl-2">
                  {execution.stepSummaries.map((summary) => (
                    <div
                      key={summary.stepId ?? ""}
                      className="text-xs text-slate-400"
                    >
                      <span className="font-mono text-slate-500">
                        {summary.stepId}
                      </span>
                      {summary.conclusion ? (
                        <>
                          {" "}
                          <span className="text-slate-500">—</span>{" "}
                          <span className="text-slate-300">
                            {summary.conclusion}
                          </span>
                        </>
                      ) : null}
                      {(summary.toolCallCount ?? 0) > 0 ? (
                        <span className="ml-1 text-slate-600">
                          ({summary.toolCallCount} 次调用)
                        </span>
                      ) : null}
                    </div>
                  ))}
                </div>
              ) : null}
            </div>
          ) : null}

          {/* Reflection section */}
          {reflection ? (
            <div>
              <div className="mb-1.5 flex items-center gap-2 text-xs uppercase tracking-wider text-slate-400">
                <SyncOutlined className="text-blue-400" />
                <span>反思</span>
              </div>
              <div className="pl-2 text-xs text-slate-300">
                {reflection.rounds} 轮
                {reflection.status ? (
                  <span className="ml-1 text-slate-500">
                    —{" "}
                    {reflection.status === "CONTINUE"
                      ? "继续执行"
                      : reflection.status === "REVISED"
                        ? "已修订计划"
                        : reflection.status === "SKIPPED"
                          ? "已跳过"
                          : reflection.status}
                  </span>
                ) : null}
                {reflection.summary ? (
                  <p className="mt-0.5 text-slate-400">{reflection.summary}</p>
                ) : null}
              </div>
            </div>
          ) : null}

          {/* Verification section */}
          {verification ? (
            <div>
              <div className="mb-1.5 flex items-center gap-2 text-xs uppercase tracking-wider text-slate-400">
                {verification.passed ? (
                  <CheckCircleOutlined className="text-emerald-400" />
                ) : (
                  <ExclamationCircleOutlined className="text-amber-400" />
                )}
                <span>验证</span>
              </div>
              <div className="pl-2 text-xs text-slate-300">
                {verification.passed ? (
                  <span className="text-emerald-300">
                    通过 ({verification.issueCount ?? 0} 个问题)
                  </span>
                ) : (
                  <span className="text-amber-300">
                    发现 {verification.issueCount ?? 0} 个问题
                  </span>
                )}
                {verification.issues && verification.issues.length > 0 ? (
                  <div className="mt-1 space-y-0.5">
                    {verification.issues.map((issue, index) => (
                      <div key={index} className="text-slate-400">
                        <span className="font-mono text-amber-400/80">
                          {issue.type}
                        </span>
                        {issue.claim ? (
                          <>
                            {" "}
                            <span className="text-slate-500">—</span>{" "}
                            <span>{issue.claim}</span>
                          </>
                        ) : null}
                      </div>
                    ))}
                  </div>
                ) : null}
              </div>
            </div>
          ) : null}
        </div>
      ) : null}
    </div>
  );
};

export default DeepThinkTracePanel;
