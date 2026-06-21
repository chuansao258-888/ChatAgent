import http from "node:http";

const port = Number(process.env.E2E_SEARXNG_PORT ?? 8888);
const host = process.env.E2E_SEARXNG_HOST ?? "127.0.0.1";
const marker =
  process.env.E2E_WEB_SEARCH_MARKER ?? "E2E-WEB-SEARCH-BEACON-ORCHARD-GREEN";
const sourceUrl =
  process.env.E2E_WEB_SEARCH_SOURCE_URL ??
  "https://example.test/e2e/beacon-orchard-status";
const emptyQueryNeedle =
  process.env.E2E_WEB_SEARCH_EMPTY_QUERY_NEEDLE ?? "E2E-NO-WEB-RESULTS";

const server = http.createServer((request, response) => {
  const url = new URL(request.url ?? "/", `http://${request.headers.host}`);
  if (url.pathname !== "/search") {
    response.writeHead(404, { "Content-Type": "application/json" });
    response.end(JSON.stringify({ error: "not_found" }));
    return;
  }

  const query = url.searchParams.get("q") ?? "";
  if (emptyQueryNeedle && query.includes(emptyQueryNeedle)) {
    response.writeHead(200, { "Content-Type": "application/json" });
    response.end(
      JSON.stringify({
        results: [],
        suggestions: [],
        numberOfResults: 0,
      }),
    );
    return;
  }

  const body = {
    results: [
      {
        title: "Example Labs Beacon Orchard status",
        url: sourceUrl,
        content: `The latest public status marker is ${marker}. The Beacon Orchard release remains green for the headed E2E fixture.`,
        engine: "local-fixture",
        score: 1.0,
        publishedDate: "2026-06-19T00:00:00Z",
      },
    ],
    suggestions: [],
    numberOfResults: 1,
  };

  response.writeHead(200, { "Content-Type": "application/json" });
  response.end(JSON.stringify(body));
});

server.listen(port, host, () => {
  console.log(`SearXNG fixture listening on http://${host}:${port}/search`);
});

function shutdown() {
  server.close(() => process.exit(0));
}

process.on("SIGTERM", shutdown);
process.on("SIGINT", shutdown);
