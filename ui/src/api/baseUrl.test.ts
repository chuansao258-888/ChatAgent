import { describe, expect, it } from "vitest";
import { resolveApiBaseUrl } from "./baseUrl.ts";

describe("resolveApiBaseUrl", () => {
  it("keeps a 127.0.0.1 UI and API on the same loopback site", () => {
    expect(
      resolveApiBaseUrl(undefined, {
        hostname: "127.0.0.1",
        protocol: "http:",
      }),
    ).toBe("http://127.0.0.1:8080/api");
  });

  it("preserves the localhost development default", () => {
    expect(
      resolveApiBaseUrl(undefined, {
        hostname: "localhost",
        protocol: "http:",
      }),
    ).toBe("http://localhost:8080/api");
  });

  it("prefers an explicitly configured API URL", () => {
    expect(
      resolveApiBaseUrl("https://api.example.test/api/", {
        hostname: "127.0.0.1",
        protocol: "http:",
      }),
    ).toBe("https://api.example.test/api");
  });
});
