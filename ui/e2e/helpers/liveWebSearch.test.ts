import { describe, expect, it } from "vitest";
import {
  buildSearxngSearchUrl,
  classifyLiveWebSearchResultUrl,
  containsLiveWebSearchFixtureFingerprint,
  summarizeSearxngPreflightPayload,
} from "./liveWebSearch";

describe("live web-search preflight helpers", () => {
  it("builds a SearXNG JSON search URL from either a base URL or /search URL", () => {
    const fromBase = buildSearxngSearchUrl(
      "http://localhost:8888",
      "current public status",
    );
    const fromSearch = buildSearxngSearchUrl(
      "http://localhost:8888/search",
      "current public status",
    );

    expect(fromBase).toBe(fromSearch);
    expect(fromBase).toContain("/search?");
    expect(fromBase).toContain("format=json");
  });

  it("summarizes public result domains without exposing raw provider payloads", () => {
    const summary = summarizeSearxngPreflightPayload({
      results: [
        {
          title: "OpenAI",
          url: "https://openai.com/news/",
          content: "Public news page.",
          engine: "bing",
        },
        {
          title: "Wikipedia",
          url: "https://en.wikipedia.org/wiki/OpenAI",
          content: "Public encyclopedia page.",
          engine: "duckduckgo",
        },
      ],
    });

    expect(summary).toEqual({
      query: "OpenAI official site current public information",
      resultCount: 2,
      publicResultCount: 2,
      publicDomains: ["openai.com", "en.wikipedia.org"],
      fixtureRejectionPassed: true,
    });
  });

  it("rejects fixture fingerprints before accepting any result", () => {
    expect(
      containsLiveWebSearchFixtureFingerprint({
        results: [
          {
            url: "https://openai.com/news/",
            content: "E2E-WEB-SEARCH-BEACON-ORCHARD-GREEN",
            engine: "bing",
          },
        ],
      }),
    ).toBe(true);
    expect(() =>
      summarizeSearxngPreflightPayload({
        results: [
          {
            url: "https://openai.com/news/",
            content: "fixture payload",
            engine: "local-fixture",
          },
        ],
      }),
    ).toThrow(/fixture fingerprints/i);
  });

  it("rejects non-public and reserved result URLs", () => {
    const rejectedUrls = [
      "https://example.test/e2e/beacon-orchard-status",
      "https://example.com/demo",
      "http://localhost:8080/result",
      "http://127.0.0.1:8888/result",
      "http://0.0.0.0/result",
      "http://10.0.0.5/result",
      "http://100.64.1.2/result",
      "http://172.20.1.4/result",
      "http://192.168.1.20/result",
      "http://198.18.0.10/result",
      "ftp://openai.com/result",
    ];

    for (const url of rejectedUrls) {
      expect(classifyLiveWebSearchResultUrl(url).ok, url).toBe(false);
    }
    expect(
      classifyLiveWebSearchResultUrl("https://www.reuters.com/technology/").ok,
    ).toBe(true);
    expect(classifyLiveWebSearchResultUrl("https://fda.gov/news-events").ok)
      .toBe(true);
    expect(classifyLiveWebSearchResultUrl("http://[fd00::1]/result").ok)
      .toBe(false);
    expect(classifyLiveWebSearchResultUrl("http://[fe90::1]/result").ok)
      .toBe(false);
  });
});
