interface BrowserLocation {
  hostname: string;
  protocol: string;
}

const LOOPBACK_HOSTS = new Set(["localhost", "127.0.0.1", "::1"]);

export function resolveApiBaseUrl(
  configuredUrl: string | undefined,
  browserLocation: BrowserLocation | undefined =
    typeof window === "undefined" ? undefined : window.location,
): string {
  const configured = configuredUrl?.trim();
  if (configured) {
    return configured.replace(/\/$/, "");
  }

  if (browserLocation && LOOPBACK_HOSTS.has(browserLocation.hostname)) {
    const host = browserLocation.hostname === "::1" ? "[::1]" : browserLocation.hostname;
    return `${browserLocation.protocol}//${host}:8080/api`;
  }

  return "http://localhost:8080/api";
}
