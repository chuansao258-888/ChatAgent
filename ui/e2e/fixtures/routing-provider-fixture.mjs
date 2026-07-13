import http from "node:http";

const port = Number.parseInt(process.env.PLAYWRIGHT_ROUTING_FIXTURE_PORT ?? "8890", 10);
const host = process.env.PLAYWRIGHT_ROUTING_FIXTURE_HOST ?? "127.0.0.1";
const lateDelayMs = Number.parseInt(process.env.PLAYWRIGHT_ROUTING_FIXTURE_LATE_DELAY_MS ?? "2500", 10);

const calls = [];

function nowSeconds() {
  return Math.floor(Date.now() / 1000);
}

function textFrom(value) {
  if (value == null) {
    return "";
  }
  if (typeof value === "string") {
    return value;
  }
  if (Array.isArray(value)) {
    return value.map(textFrom).join("\n");
  }
  if (typeof value === "object") {
    return Object.values(value).map(textFrom).join("\n");
  }
  return String(value);
}

function requestText(body) {
  return textFrom(body?.messages ?? body?.input ?? body);
}

function codeFromText(text) {
  return text.match(/\b(?:ASTER|BIRCH|CEDAR|DUNE|EMBER|FIR)-[A-Z0-9-]+\b/)?.[0] ?? null;
}

function scenarioFromCode(code) {
  if (!code) {
    return "internal";
  }
  if (code.startsWith("ASTER-")) {
    return "success";
  }
  if (code.startsWith("BIRCH-")) {
    return "error";
  }
  if (code.startsWith("CEDAR-")) {
    return "timeout";
  }
  if (code.startsWith("DUNE-")) {
    return "no-content";
  }
  if (code.startsWith("EMBER-")) {
    return "late";
  }
  if (code.startsWith("FIR-")) {
    return "slow-success";
  }
  return "success";
}

function internalResponse(text) {
  if (text.includes("enterprise AI assistant intent-classification expert")) {
    return "NONE";
  }
  if (text.includes("long-term memory extractor")) {
    return '{"memories":[]}';
  }
  if (text.includes("structured memory summarizer")) {
    return '{"summary":"No durable routing fixture facts.","facts":[],"decisions":[],"open_tasks":[],"entities":{"dates":[],"amounts":[],"orderIds":[]}}';
  }
  if (text.includes("rolling memory summarizer")) {
    return "No durable routing fixture facts.";
  }
  return "OK";
}

function isInternalPrompt(text) {
  return text.includes("enterprise AI assistant intent-classification expert")
    || text.includes("long-term memory extractor")
    || text.includes("structured memory summarizer")
    || text.includes("rolling memory summarizer");
}

function recordCall(provider, path, body, scenario, code) {
  calls.push({
    provider,
    path,
    scenario,
    code,
    model: typeof body?.model === "string" ? body.model : null,
    stream: body?.stream === true,
    thinking: body?.thinking?.type ?? null,
    at: new Date().toISOString(),
  });
}

async function parseJson(req) {
  const chunks = [];
  for await (const chunk of req) {
    chunks.push(chunk);
  }
  if (chunks.length === 0) {
    return {};
  }
  return JSON.parse(Buffer.concat(chunks).toString("utf8"));
}

function writeJson(res, status, payload) {
  res.writeHead(status, { "content-type": "application/json" });
  res.end(JSON.stringify(payload));
}

function writeSseHeaders(res) {
  res.writeHead(200, {
    "content-type": "text/event-stream; charset=utf-8",
    "cache-control": "no-cache",
    connection: "keep-alive",
  });
}

function writeDeepSeekChunk(res, code, model) {
  const id = `chatcmpl-${Date.now()}`;
  res.write(`data: ${JSON.stringify({
    id,
    object: "chat.completion.chunk",
    created: nowSeconds(),
    model,
    choices: [
      {
        index: 0,
        delta: { role: "assistant", content: code },
        finish_reason: null,
      },
    ],
  })}\n\n`);
  res.write(`data: ${JSON.stringify({
    id,
    object: "chat.completion.chunk",
    created: nowSeconds(),
    model,
    choices: [
      {
        index: 0,
        delta: {},
        finish_reason: "stop",
      },
    ],
  })}\n\n`);
  res.write("data: [DONE]\n\n");
  res.end();
}

function writeDeepSeekJson(res, content, model) {
  writeJson(res, 200, {
    id: `chatcmpl-${Date.now()}`,
    object: "chat.completion",
    created: nowSeconds(),
    model,
    choices: [
      {
        index: 0,
        message: { role: "assistant", content },
        finish_reason: "stop",
      },
    ],
    usage: {
      prompt_tokens: 1,
      completion_tokens: 1,
      total_tokens: 2,
    },
  });
}

function writeAnthropicTextEvents(res, code, model) {
  const message = {
    id: `msg_${Date.now()}`,
    type: "message",
    role: "assistant",
    model,
    content: [],
    stop_reason: null,
    stop_sequence: null,
    usage: { input_tokens: 1, output_tokens: 1 },
  };
  res.write(`event: message_start\ndata: ${JSON.stringify({ type: "message_start", message })}\n\n`);
  res.write(`event: content_block_start\ndata: ${JSON.stringify({
    type: "content_block_start",
    index: 0,
    content_block: { type: "text", text: "" },
  })}\n\n`);
  res.write(`event: content_block_delta\ndata: ${JSON.stringify({
    type: "content_block_delta",
    index: 0,
    delta: { type: "text_delta", text: code },
  })}\n\n`);
  res.write(`event: content_block_stop\ndata: ${JSON.stringify({ type: "content_block_stop", index: 0 })}\n\n`);
  res.write(`event: message_delta\ndata: ${JSON.stringify({
    type: "message_delta",
    delta: { stop_reason: "end_turn", stop_sequence: null },
    usage: { output_tokens: 1 },
  })}\n\n`);
  res.write(`event: message_stop\ndata: ${JSON.stringify({ type: "message_stop" })}\n\n`);
  res.end();
}

function writeAnthropicTextStream(res, code, model) {
  writeSseHeaders(res);
  writeAnthropicTextEvents(res, code, model);
}

function writeAnthropicNoContentStream(res) {
  writeSseHeaders(res);
  // NO_CONTENT means completion before any valid parsed provider chunk.
  // A message_start/usage-only event is metadata and therefore a valid
  // transport-first signal; emitting it here would intentionally mean SUCCESS.
  res.end();
}

function handleAnthropic(body, res, path) {
  const text = requestText(body);
  const code = codeFromText(text);
  const scenario = isInternalPrompt(text) ? "internal" : scenarioFromCode(code);
  const model = body?.model ?? "glm-5.2";
  recordCall("anthropic", path, body, scenario, code);

  if (scenario === "internal") {
    writeAnthropicTextStream(res, internalResponse(text), model);
    return;
  }
  if (scenario === "error") {
    writeJson(res, 500, { error: { message: "routing fixture primary error" } });
    return;
  }
  if (scenario === "timeout") {
    setTimeout(() => {
      if (!res.writableEnded) {
        res.end();
      }
    }, 10_000);
    return;
  }
  if (scenario === "no-content") {
    writeAnthropicNoContentStream(res);
    return;
  }
  if (scenario === "late") {
    writeSseHeaders(res);
    setTimeout(() => {
      if (!res.writableEnded) {
        writeAnthropicTextEvents(res, `PRIMARY-LATE-LEAK-${code}`, model);
      }
    }, lateDelayMs);
    return;
  }
  if (scenario === "slow-success") {
    writeSseHeaders(res);
    setTimeout(() => {
      if (!res.writableEnded) {
        writeAnthropicTextEvents(res, code, model);
      }
    }, 800);
    return;
  }

  writeAnthropicTextStream(res, code, model);
}

function handleDeepSeek(body, res, path) {
  const text = requestText(body);
  const code = codeFromText(text);
  const scenario = isInternalPrompt(text) ? "internal" : scenarioFromCode(code);
  const model = body?.model ?? "deepseek-v4-flash";
  recordCall("deepseek", path, body, scenario, code);
  const content = scenario === "internal" ? internalResponse(text) : code;

  if (body?.stream === true) {
    writeSseHeaders(res);
    writeDeepSeekChunk(res, content, model);
    return;
  }
  writeDeepSeekJson(res, content, model);
}

function isDeepSeekPath(path) {
  return path === "/chat/completions" || path === "/v1/chat/completions" || path === "/beta/chat/completions";
}

function isAnthropicPath(path) {
  return path === "/v1/messages" || path === "/messages";
}

const server = http.createServer(async (req, res) => {
  const path = new URL(req.url ?? "/", `http://${req.headers.host}`).pathname;
  try {
    if (req.method === "GET" && path === "/__routing-fixture/health") {
      writeJson(res, 200, { status: "ok" });
      return;
    }
    if (req.method === "GET" && path === "/__routing-fixture/state") {
      writeJson(res, 200, { calls });
      return;
    }
    if (req.method === "POST" && path === "/__routing-fixture/reset") {
      calls.length = 0;
      writeJson(res, 200, { reset: true });
      return;
    }
    if (req.method === "POST" && isAnthropicPath(path)) {
      handleAnthropic(await parseJson(req), res, path);
      return;
    }
    if (req.method === "POST" && isDeepSeekPath(path)) {
      handleDeepSeek(await parseJson(req), res, path);
      return;
    }
    writeJson(res, 404, { error: `No routing fixture route for ${req.method} ${path}` });
  } catch (error) {
    writeJson(res, 500, { error: error instanceof Error ? error.message : String(error) });
  }
});

server.listen(port, host, () => {
  console.log(`routing-provider-fixture listening at http://${host}:${port}`);
});
