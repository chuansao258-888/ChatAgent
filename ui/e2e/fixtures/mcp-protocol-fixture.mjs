import http from "node:http";

const port = Number(process.env.E2E_MCP_FIXTURE_PORT ?? 8090);
const sessionId = "e2e-mcp-session";

const tools = [
  {
    name: "get_current_datetime",
    description: "Return a deterministic current datetime for E2E tests.",
    inputSchema: { type: "object", properties: {} },
  },
  {
    name: "convert_time",
    description: "Convert a deterministic time between timezones.",
    inputSchema: {
      type: "object",
      properties: { time: { type: "string" }, timezone: { type: "string" } },
    },
  },
];

function reply(response, id, result) {
  const body = JSON.stringify({ jsonrpc: "2.0", id, result });
  response.writeHead(200, {
    "content-type": "application/json",
    "mcp-session-id": sessionId,
  });
  response.end(body);
}

const server = http.createServer(async (request, response) => {
  if (request.url === "/__mcp-fixture/health") {
    response.writeHead(200, { "content-type": "application/json" });
    response.end(JSON.stringify({ healthy: true, fixture: "mcp-protocol" }));
    return;
  }
  if (request.url !== "/mcp") {
    response.writeHead(404).end();
    return;
  }
  if (request.method === "DELETE") {
    response.writeHead(204).end();
    return;
  }
  const chunks = [];
  for await (const chunk of request) chunks.push(chunk);
  const message = JSON.parse(Buffer.concat(chunks).toString("utf8") || "{}");
  if (message.method === "notifications/initialized") {
    response.writeHead(202).end();
  } else if (message.method === "initialize") {
    reply(response, message.id, {
      protocolVersion: "2025-06-18",
      capabilities: { tools: { listChanged: false } },
      serverInfo: { name: "ChatAgent deterministic MCP", version: "1.0.0" },
    });
  } else if (message.method === "tools/list") {
    reply(response, message.id, { tools });
  } else if (message.method === "tools/call") {
    const name = message.params?.name;
    const text = name === "convert_time"
      ? "2026-06-19T09:30:00 in Asia/Singapore corresponds to 2026-06-18T21:30:00 in America/New_York."
      : "The deterministic current datetime is 2026-07-12T12:00:00+08:00 in Asia/Singapore.";
    reply(response, message.id, { content: [{ type: "text", text }], isError: false });
  } else {
    const body = JSON.stringify({
      jsonrpc: "2.0",
      id: message.id,
      error: { code: -32601, message: "Method not found" },
    });
    response.writeHead(200, { "content-type": "application/json" }).end(body);
  }
});

server.listen(port, "127.0.0.1", () => {
  process.stdout.write(`MCP fixture listening on http://127.0.0.1:${port}\n`);
});

for (const signal of ["SIGINT", "SIGTERM"]) {
  process.on(signal, () => server.close(() => process.exit(0)));
}
