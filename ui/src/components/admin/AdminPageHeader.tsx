import { Typography } from "antd";
import type { ReactNode } from "react";

interface AdminPageHeaderProps {
  eyebrow: string;
  title: string;
  description?: ReactNode;
  meta?: ReactNode;
  actions?: ReactNode;
}

export default function AdminPageHeader({
  eyebrow,
  title,
  description,
  meta,
  actions,
}: AdminPageHeaderProps) {
  return (
    <div className="flex flex-col gap-4 md:flex-row md:items-end md:justify-between">
      <div>
        <div className="text-xs uppercase tracking-[0.28em] text-white/40">
          {eyebrow}
        </div>
        <Typography.Title level={2} className="!mb-0 !mt-2 !text-white">
          {title}
        </Typography.Title>
        {description ? (
          <Typography.Text className="mt-1.5 block max-w-3xl !text-white/60">
            {description}
          </Typography.Text>
        ) : null}
        {meta ? <div className="mt-2">{meta}</div> : null}
      </div>

      {actions ? (
        <div className="flex flex-wrap items-center gap-3 md:justify-end">
          {actions}
        </div>
      ) : null}
    </div>
  );
}
