import React from "react";
import { Tooltip } from "antd";
import type { CitationMetadata } from "../../../types";

interface CitationInlineTagProps {
  citation: CitationMetadata;
  index: number;
  onNavigate?: () => void;
}

const buildLabel = (citation: CitationMetadata): string => {
  const parts = [citation.documentName || citation.documentId || "Unknown source"];
  if (citation.sectionPath) {
    parts.push(citation.sectionPath);
  }
  return parts.join(" > ");
};

const CitationInlineTag: React.FC<CitationInlineTagProps> = ({
  citation,
  index,
  onNavigate,
}) => (
  <Tooltip
    placement="top"
    title={
      <div className="max-w-xs space-y-1 text-xs leading-5">
        <div className="font-semibold text-[#f5e3c2]">{buildLabel(citation)}</div>
        {citation.snippet ? (
          <div className="text-slate-200">{citation.snippet}</div>
        ) : null}
      </div>
    }
  >
    <button
      type="button"
      onClick={onNavigate}
      aria-label={`Citation ${index}: ${buildLabel(citation)}`}
      data-citation-index={index}
      className="mx-0.5 inline-flex items-center whitespace-nowrap rounded-full border border-[var(--citation-border)] bg-[var(--citation-bg)] px-2 py-0.5 align-baseline text-[11px] font-semibold leading-5 text-[var(--citation-text)] transition hover:border-[#dca35d]/55 hover:bg-[var(--citation-bg-hover)]"
    >
      [{index}]
    </button>
  </Tooltip>
);

export default CitationInlineTag;
