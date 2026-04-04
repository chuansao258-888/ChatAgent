export function formatTimestamp(value?: string): string {
  if (!value) {
    return "Not available";
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  return date.toLocaleString("en-SG", {
    year: "numeric",
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
}

export function formatBytes(sizeBytes?: number): string {
  if (!sizeBytes || sizeBytes <= 0) {
    return "0 B";
  }
  const units = ["B", "KB", "MB", "GB"];
  let value = sizeBytes;
  let unitIndex = 0;
  while (value >= 1024 && unitIndex < units.length - 1) {
    value /= 1024;
    unitIndex += 1;
  }
  return `${value.toFixed(value >= 10 || unitIndex === 0 ? 0 : 1)} ${units[unitIndex]}`;
}

export function statusTone(
  status?: string,
): "green" | "orange" | "red" | "blue" | "default" {
  switch ((status ?? "").toUpperCase()) {
    case "ACTIVE":
    case "COMPLETED":
      return "green";
    case "PENDING":
    case "PARSING_OCR_PENDING":
      return "blue";
    case "ARCHIVED":
      return "orange";
    case "FAILED":
    case "REJECTED":
      return "red";
    default:
      return "default";
  }
}

export function intentKindTone(kind?: string): string {
  switch ((kind ?? "").toUpperCase()) {
    case "KB":
      return "blue";
    case "TOOL":
      return "gold";
    case "SYSTEM":
      return "purple";
    case "CLARIFY":
      return "cyan";
    default:
      return "default";
  }
}

export function intentLevelTone(level?: string): string {
  switch ((level ?? "").toUpperCase()) {
    case "DOMAIN":
      return "geekblue";
    case "CATEGORY":
      return "green";
    case "TOPIC":
      return "magenta";
    default:
      return "default";
  }
}

export function scopePolicyLabel(policy?: string): string {
  switch ((policy ?? "").toUpperCase()) {
    case "STRICT":
      return "Strict";
    case "FALLBACK_ALLOWED":
      return "Fallback Allowed";
    default:
      return "Not set";
  }
}
