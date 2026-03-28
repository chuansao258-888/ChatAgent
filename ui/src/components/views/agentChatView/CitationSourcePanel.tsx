import React from "react";
import { BookOutlined, FileSearchOutlined } from "@ant-design/icons";
import type { CitationMetadata } from "../../../types";

interface CitationSourcePanelProps {
  messageId: string;
  citations: CitationMetadata[];
}

const buildSectionLabel = (citation: CitationMetadata): string => {
  const parts = [citation.documentName || citation.documentId || "Unknown source"];
  if (citation.sectionPath) {
    parts.push(citation.sectionPath);
  }
  return parts.join(" > ");
};

const CitationSourcePanel: React.FC<CitationSourcePanelProps> = ({
  messageId,
  citations,
}) => {
  if (!citations.length) {
    return null;
  }

  return (
    <div className="mt-4 rounded-section border border-[var(--citation-panel-border)] bg-[var(--citation-panel-bg)] px-4 py-4 shadow-chat-panel">
      <div className="mb-3 flex items-center gap-2 text-xs uppercase tracking-[0.22em] text-[#cfa871]">
        <FileSearchOutlined />
        <span>Sources</span>
      </div>
      <div className="space-y-3">
        {citations.map((citation, index) => (
          <div
            key={`${messageId}-${index + 1}`}
            id={`citation-source-${messageId}-${index + 1}`}
            className="rounded-inset border border-white/6 bg-white/[0.03] px-4 py-3"
          >
            <div className="flex items-start gap-3">
              <div className="mt-0.5 flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-[#2b2218] text-[11px] font-semibold text-[#f3d7b2]">
                {index + 1}
              </div>
              <div className="min-w-0 flex-1">
                <div className="flex items-center gap-2 text-sm font-medium text-[#f5e3c2]">
                  <BookOutlined className="text-[#cfa871]" />
                  <span className="truncate">{buildSectionLabel(citation)}</span>
                </div>
                {citation.snippet ? (
                  <p className="mt-2 text-sm leading-6 text-slate-300">
                    {citation.snippet}
                  </p>
                ) : null}
                <div className="mt-2 flex flex-wrap items-center gap-2 text-xs text-slate-400">
                  <span className="rounded-full border border-white/8 bg-white/[0.03] px-2 py-1">
                    {citation.sourceType === "KNOWLEDGE_BASE"
                      ? "Knowledge Base"
                      : "Session File"}
                  </span>
                  {citation.isFallback ? (
                    <span className="rounded-full border border-amber-500/20 bg-amber-500/10 px-2 py-1 text-amber-300">
                      Fallback order
                    </span>
                  ) : citation.scoreType === "filtered" ? (
                    <span className="rounded-full border border-rose-400/20 bg-rose-400/10 px-2 py-1 text-rose-200">
                      Filtered by confidence
                    </span>
                  ) : citation.scoreType === "retrieval" ? (
                    <span className="rounded-full border border-slate-400/20 bg-slate-400/10 px-2 py-1 text-slate-300">
                      Retrieval order
                    </span>
                  ) : null}
                  {typeof citation.chunkIndex === "number" ? (
                    <span>Chunk {citation.chunkIndex}</span>
                  ) : null}
                  {typeof citation.score === "number" ? (
                    <span>Score {citation.score.toFixed(2)}</span>
                  ) : null}
                </div>
              </div>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
};

export default CitationSourcePanel;
