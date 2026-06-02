import type { FC } from "react";
import type { AgentExecutionMode } from "../../../types";

interface ExecutionModeToggleProps {
  executionMode: AgentExecutionMode;
  onExecutionModeChange: (executionMode: AgentExecutionMode) => void;
  disabled?: boolean;
}

const ExecutionModeToggle: FC<ExecutionModeToggleProps> = ({
  executionMode,
  onExecutionModeChange,
  disabled = false,
}) => {
  const isDeepThink = executionMode === "DEEPTHINK";

  return (
    <button
      type="button"
      disabled={disabled}
      aria-pressed={isDeepThink}
      onClick={() => {
        onExecutionModeChange(isDeepThink ? "REACT" : "DEEPTHINK");
      }}
      className={
        isDeepThink
          ? "rounded-full border border-cyan-300/40 bg-cyan-300/14 px-3 py-1.5 text-sm font-medium text-cyan-100 transition disabled:cursor-not-allowed disabled:opacity-50"
          : "rounded-full border border-white/10 bg-white/6 px-3 py-1.5 text-sm font-medium text-slate-300 transition hover:bg-white/10 disabled:cursor-not-allowed disabled:opacity-50"
      }
    >
      {isDeepThink ? "DeepThink" : "ReAct"}
    </button>
  );
};

export default ExecutionModeToggle;
