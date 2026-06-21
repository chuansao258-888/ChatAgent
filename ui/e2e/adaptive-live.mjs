import { request as playwrightRequest } from "@playwright/test";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import path from "node:path";
import pg from "pg";

const ASSISTANT_ID = "3f9f84f7-2df0-4a5f-9c85-9f2d9b7aaf10";
const API_BASE_URL = process.env.PLAYWRIGHT_API_BASE_URL ?? "http://localhost:8080";
const UI_BASE_URL = process.env.PLAYWRIGHT_UI_BASE_URL ?? "http://127.0.0.1:5173";
const DRIVER_URL = process.env.PLAYWRIGHT_DRIVER_URL ?? "http://127.0.0.1:7878";
const MCP_ENDPOINT_URL = "http://localhost:8090/mcp";
const AUTH_DIR = path.resolve(process.cwd(), ".auth");
const USERS_PATH = path.join(AUTH_DIR, "e2e-users.json");
const MANIFEST_PATH = path.join(AUTH_DIR, "adaptive-live.json");
const command = process.argv[2] ?? "setup";
const commandArg = process.argv[3];

function unique(values) {
  return [...new Set(values.filter(Boolean))];
}

function resolveDbConfig() {
  const jdbc = process.env.CHATAGENT_DB_URL?.trim() || "jdbc:postgresql://localhost:5432/chatagent";
  const url = new URL(jdbc.replace(/^jdbc:/, ""));
  return {
    host: url.hostname,
    port: Number(url.port || 5432),
    database: url.pathname.replace(/^\//, "") || "chatagent",
    user: process.env.CHATAGENT_DB_USERNAME?.trim() || url.username || "app",
    password: process.env.CHATAGENT_DB_PASSWORD ?? url.password,
  };
}

async function withDb(callback) {
  const client = new pg.Client({ ...resolveDbConfig(), connectionTimeoutMillis: 5_000 });
  await client.connect();
  try {
    return await callback(client);
  } finally {
    await client.end();
  }
}

async function parseEnvelope(response) {
  const envelope = await response.json();
  if (!response.ok() || envelope.code !== 200) {
    throw new Error(`API ${response.status()} ${response.url()}: ${envelope.message ?? "unknown error"}`);
  }
  return envelope.data;
}

async function api(context, method, requestPath, token, data, multipart) {
  const response = await context.fetch(requestPath, {
    method,
    data,
    multipart,
    headers: token ? { Authorization: `Bearer ${token}` } : undefined,
  });
  return parseEnvelope(response);
}

async function login(context, user) {
  return api(context, "POST", "/api/auth/login", undefined, user);
}

async function waitForKnowledgeDocument(context, token, knowledgeBaseId) {
  const deadline = Date.now() + 300_000;
  let documents = [];
  while (Date.now() < deadline) {
    documents = await api(
      context,
      "GET",
      `/api/admin/knowledge-bases/${knowledgeBaseId}/documents`,
      token,
    );
    if (documents.length === 1 && documents[0].parseStatus === "COMPLETED") {
      return documents[0];
    }
    if (documents.some((document) => document.parseStatus === "FAILED")) {
      throw new Error(`Knowledge ingestion failed for ${knowledgeBaseId}.`);
    }
    await new Promise((resolve) => setTimeout(resolve, 1_000));
  }
  throw new Error(`Knowledge ingestion timed out for ${knowledgeBaseId}: ${JSON.stringify(documents)}`);
}

async function waitForSessionFile(context, token, sessionId, sessionFileId) {
  const deadline = Date.now() + 300_000;
  let files = [];
  while (Date.now() < deadline) {
    files = await api(
      context,
      "GET",
      `/api/chat-sessions/${sessionId}/files`,
      token,
    );
    const sessionFile = files.find((file) => file.id === sessionFileId);
    if (sessionFile?.parseStatus === "COMPLETED") {
      return sessionFile;
    }
    if (sessionFile?.parseStatus === "FAILED") {
      throw new Error(`Session-file ingestion failed for ${sessionFileId}.`);
    }
    await new Promise((resolve) => setTimeout(resolve, 1_000));
  }
  throw new Error(`Session-file ingestion timed out for ${sessionFileId}: ${JSON.stringify(files)}`);
}

async function driver(method, requestPath, body) {
  const response = await fetch(`${DRIVER_URL}${requestPath}`, {
    method,
    headers: body ? { "content-type": "application/json" } : undefined,
    body: body ? JSON.stringify(body) : undefined,
  });
  const payload = await response.json();
  if (!response.ok || payload.error) {
    throw new Error(`Driver ${response.status} ${requestPath}: ${payload.error ?? "unknown error"}`);
  }
  return payload;
}

async function waitForSessionFileInDb(sessionId, filename, markers) {
  const deadline = Date.now() + 300_000;
  let lastState = null;
  while (Date.now() < deadline) {
    lastState = await withDb(async (client) => {
      const fileResult = await client.query(
        `SELECT id::text AS id,
                filename,
                original_filename AS "originalFilename",
                parse_status AS "parseStatus"
           FROM chat_session_file
          WHERE session_id = $1::uuid
            AND original_filename = $2
          ORDER BY created_at DESC
          LIMIT 1`,
        [sessionId, filename],
      );
      const file = fileResult.rows[0];
      if (!file) {
        return null;
      }
      const chunkResult = await client.query(
        `SELECT content
           FROM file_chunk
          WHERE session_file_id = $1::uuid
          ORDER BY chunk_index ASC`,
        [file.id],
      );
      const text = chunkResult.rows.map((row) => row.content ?? "").join("\n");
      return {
        ...file,
        markerMatches: markers.map((marker) => ({ marker, found: text.includes(marker) })),
      };
    });
    if (lastState?.parseStatus === "FAILED" || lastState?.parseStatus === "REJECTED") {
      throw new Error(`Session-file ingestion failed for ${filename}: ${lastState.parseStatus}`);
    }
    if (
      lastState?.parseStatus === "COMPLETED" &&
      lastState.markerMatches.every((match) => match.found)
    ) {
      return lastState;
    }
    await new Promise((resolve) => setTimeout(resolve, 1_000));
  }
  throw new Error(`Session-file ingestion timed out for ${filename}: ${JSON.stringify(lastState)}`);
}

async function createKnowledgeBase(context, token, name, marker, body) {
  const id = await api(context, "POST", "/api/admin/knowledge-bases", token, {
    name,
    description: "Temporary adaptive headed E2E evidence.",
  });
  await api(
    context,
    "POST",
    `/api/admin/knowledge-bases/${id}/documents/upload`,
    token,
    undefined,
    {
      file: {
        name: `${name.replace(/[^a-zA-Z0-9]/g, "-")}.md`,
        mimeType: "text/markdown",
        buffer: Buffer.from(`# Adaptive Work Brief\n\n${body}\n\nEvidence marker: ${marker}\n`),
      },
    },
  );
  const document = await waitForKnowledgeDocument(context, token, id);
  return { id, documentId: document.id, filename: document.filename };
}

function sessionFileFixture(manifest, group) {
  const { scenario, suffix } = manifest;
  if (group === "group-a") {
    return {
      filename: `meridian-room-card-${suffix}.md`,
      markers: [scenario.groupAFileRoom, scenario.groupAFileMarker],
      content:
        `# Meridian floor card\n\n` +
        `The current working room printed on the local floor card is ${scenario.groupAFileRoom}.\n\n` +
        `The access phrase is ${scenario.groupAFileMarker}. The morning coordination window opens at 08:40.\n`,
    };
  }
  if (group === "group-b") {
    return {
      filename: `kestrel-dock-note-${suffix}.md`,
      markers: [scenario.groupBOldLocker, scenario.groupBFileMarker],
      content:
        `# Kestrel dock note\n\n` +
        `The staging locker listed on the dock note is ${scenario.groupBOldLocker}.\n\n` +
        `The check phrase is ${scenario.groupBFileMarker}. The unloading window starts at 14:20.\n`,
    };
  }
  throw new Error(`Unknown adaptive group: ${group}`);
}

async function attachSessionFile(manifest, group, sessionId) {
  if (!sessionId) {
    throw new Error("attach-session requires a chat session id.");
  }
  const existing = (manifest.sessionUploads ?? []).find(
    (upload) => upload.group === group && upload.sessionId === sessionId,
  );
  if (existing) {
    return existing;
  }

  const fixture = sessionFileFixture(manifest, group);
  await driver("POST", "/goto", { url: `${UI_BASE_URL}/chat/${sessionId}` });
  await driver("POST", "/act", {
    locator: { css: "input[type='file']" },
    action: "setInputFiles",
    files: [
      {
        name: fixture.filename,
        mimeType: "text/markdown",
        base64: Buffer.from(fixture.content).toString("base64"),
      },
    ],
  });
  const sessionFile = await waitForSessionFileInDb(sessionId, fixture.filename, fixture.markers);
  const result = {
    group,
    sessionId,
    sessionFileId: sessionFile.id,
    filename: fixture.filename,
    markers: fixture.markers,
    parseStatus: sessionFile.parseStatus,
    uploadPath: "visible-browser",
  };
  manifest.sessionUploads = [...(manifest.sessionUploads ?? []), result];
  await writeFile(MANIFEST_PATH, `${JSON.stringify(manifest, null, 2)}\n`);
  return result;
}

async function createIntentNode(context, token, body) {
  const response = await api(
    context,
    "POST",
    "/api/admin/assistant/intent-tree/nodes",
    token,
    body,
  );
  return response.nodeId;
}

async function cleanupExisting() {
  try {
    const manifest = JSON.parse(await readFile(MANIFEST_PATH, "utf8"));
    await cleanup(manifest);
  } catch (error) {
    if (error?.code !== "ENOENT") {
      throw error;
    }
  }
}

async function setup() {
  await cleanupExisting();
  const users = JSON.parse(await readFile(USERS_PATH, "utf8"));
  const stamp = new Date().toISOString().replace(/\D/g, "").slice(4, 14);
  const suffix = stamp.slice(-8).toUpperCase();
  const scenario = {
    label: "Meridian payroll cutover",
    groupAProject: `Meridian-${suffix}`,
    groupAInitialOwner: `Avery-${suffix}`,
    groupACurrentOwner: `Jordan-${suffix}`,
    groupAOldRoom: `Quartz-${suffix}`,
    groupAFileRoom: `Linden-${suffix}`,
    groupACurrentRoom: `Nova-${suffix}`,
    groupAKbMarker: `ADAPT-A-KB-${suffix}`,
    groupAUnboundMarker: `ADAPT-A-UNBOUND-${suffix}`,
    groupAFileMarker: `ADAPT-A-FILE-${suffix}`,
    groupBProject: `Kestrel-${suffix}`,
    groupBInitialOwner: `Samir-${suffix}`,
    groupBCurrentOwner: `Noelle-${suffix}`,
    groupBOldLocker: `Crate-${suffix}`,
    groupBCurrentLocker: `Vault-${suffix}`,
    groupBKbMarker: `ADAPT-B-KB-${suffix}`,
    groupBFileMarker: `ADAPT-B-FILE-${suffix}`,
  };
  const context = await playwrightRequest.newContext({ baseURL: API_BASE_URL });
  const auth = await login(context, users.admin);
  const token = auth.accessToken;
  const originalTree = await api(
    context,
    "GET",
    "/api/admin/assistant/intent-tree",
    token,
  );
  const originalBindings = (
    await api(context, "GET", "/api/admin/assistant/knowledge-bases", token)
  ).map((binding) => binding.id);
  const originalAgent = await withDb(async (client) => {
    const result = await client.query(
      "SELECT active_intent_version, allowed_tools FROM agent WHERE id = $1::uuid",
      [ASSISTANT_ID],
    );
    if (!result.rows[0]) throw new Error("System assistant not found.");
    return result.rows[0];
  });

  const kbA = await createKnowledgeBase(
    context,
    token,
    `E2E Adaptive ${suffix} Meridian Runbook`,
    scenario.groupAKbMarker,
    `For ${scenario.groupAProject}, the payroll cutover approval code is ${scenario.groupAKbMarker}. ` +
      "The escalation contact is Dana Brooks. The final readiness risk is an unconfirmed payroll export checksum.",
  );
  const kbUnbound = await createKnowledgeBase(
    context,
    token,
    `E2E Adaptive ${suffix} Independent Archive`,
    scenario.groupAUnboundMarker,
    `The independent Falcon ledger archive records reconciliation marker ${scenario.groupAUnboundMarker}.`,
  );
  const kbB = await createKnowledgeBase(
    context,
    token,
    `E2E Adaptive ${suffix} Kestrel Readiness`,
    scenario.groupBKbMarker,
    `For ${scenario.groupBProject}, the warehouse readiness code is ${scenario.groupBKbMarker}. ` +
      "The carrier contact is Robin Hale. The current open risk is a missing dock confirmation.",
  );
  const knowledgeBaseIds = [kbA.id, kbUnbound.id, kbB.id];
  await api(context, "PUT", "/api/admin/assistant/knowledge-bases", token, {
    knowledgeBaseIds: unique([...originalBindings, ...knowledgeBaseIds]),
  });

  const serverSlug = `e2e_adaptive_weather_${suffix.toLowerCase()}`;
  const mcpServerId = await api(context, "POST", "/api/admin/mcp-servers", token, {
    slug: serverSlug,
    name: `E2E Adaptive Weather ${suffix}`,
    description: "Temporary adaptive headed MCP server.",
    protocol: "HTTP",
    authType: "NONE",
    endpointUrl: MCP_ENDPOINT_URL,
  });
  const testResult = await api(
    context,
    "POST",
    `/api/admin/mcp-servers/${mcpServerId}/test`,
    token,
  );
  if (!testResult.success) throw new Error(`Adaptive MCP test failed: ${testResult.errorMessage}`);
  const syncResult = await api(
    context,
    "POST",
    `/api/admin/mcp-servers/${mcpServerId}/sync`,
    token,
  );
  if (!syncResult.success) throw new Error(`Adaptive MCP sync failed: ${syncResult.errorMessage}`);
  const activeTools = syncResult.activeTools;
  const currentDateTimeTool = activeTools.find(
    (tool) => tool.remoteOriginalName === "get_current_datetime",
  )?.exposedModelName;
  const convertTimeTool = activeTools.find(
    (tool) => tool.remoteOriginalName === "convert_time",
  )?.exposedModelName;
  if (!currentDateTimeTool || !convertTimeTool) {
    throw new Error("Adaptive MCP did not expose both time tools.");
  }
  const originalAllowedTools = Array.isArray(originalAgent.allowed_tools)
    ? originalAgent.allowed_tools
    : [];
  await withDb((client) =>
    client.query(
      "UPDATE agent SET allowed_tools = $2::jsonb, updated_at = NOW() WHERE id = $1::uuid",
      [
        ASSISTANT_ID,
        JSON.stringify(
          unique([
            ...originalAllowedTools,
            "SessionFileSearchTool",
            "dataBaseTool",
            ...activeTools.map((tool) => tool.exposedModelName),
          ]),
        ),
      ],
    ),
  );

  const intentPrefix = `E2E Intent Adaptive ${suffix}`;
  const rootName = `${intentPrefix} Payroll Command`;
  const alternateRootName = `${intentPrefix} Operations Archive`;
  const rootId = await createIntentNode(context, token, {
    nodeLevel: "DOMAIN",
    name: rootName,
    description: "Adaptive headed payroll command domain.",
    examples: ["payroll cutover", "approval code", "current room", "Singapore time"],
    enabled: true,
    sortOrder: 1700,
  });
  const categoryId = await createIntentNode(context, token, {
    parentId: rootId,
    nodeLevel: "CATEGORY",
    name: `${intentPrefix} Cutover Readiness`,
    examples: ["approval code", "cutover note", "room card", "timezone check"],
    enabled: true,
    sortOrder: 0,
  });
  const kbTopicId = await createIntentNode(context, token, {
    parentId: categoryId,
    nodeLevel: "TOPIC",
    name: `${intentPrefix} Payroll Runbook`,
    examples: ["What approval code is in the payroll runbook?", "Who is the escalation contact?"],
    intentKind: "KB",
    scopePolicy: "STRICT",
    enabled: true,
    sortOrder: 0,
  });
  await api(
    context,
    "PUT",
    `/api/admin/assistant/intent-tree/nodes/${kbTopicId}/knowledge-bases`,
    token,
    { knowledgeBaseIds: [kbA.id] },
  );
  await createIntentNode(context, token, {
    parentId: categoryId,
    nodeLevel: "TOPIC",
    name: `${intentPrefix} Session Brief`,
    examples: ["What did the attachment say?", "Check the room card I uploaded."],
    intentKind: "TOOL",
    allowedTools: ["SessionFileSearchTool"],
    enabled: true,
    sortOrder: 1,
  });
  await createIntentNode(context, token, {
    parentId: categoryId,
    nodeLevel: "TOPIC",
    name: `${intentPrefix} Time Coordination`,
    examples: ["What time is it in Singapore?", "Convert Singapore time to New York."],
    intentKind: "TOOL",
    allowedTools: [currentDateTimeTool, convertTimeTool],
    enabled: true,
    sortOrder: 2,
  });
  await createIntentNode(context, token, {
    nodeLevel: "DOMAIN",
    name: alternateRootName,
    description: "Adaptive clarification alternative.",
    examples: ["operations", "status"],
    enabled: true,
    sortOrder: 1701,
  });
  const activeVersion = await api(
    context,
    "POST",
    "/api/admin/assistant/intent-tree/publish",
    token,
  );

  const manifest = {
    createdAt: new Date().toISOString(),
    suffix,
    assistantId: ASSISTANT_ID,
    scenario,
    kbA,
    kbUnbound,
    kbB,
    knowledgeBaseIds,
    mcpServerId,
    serverSlug,
    activeTools,
    currentDateTimeTool,
    convertTimeTool,
    intentPrefix,
    rootName,
    alternateRootName,
    activeVersion,
    originalActiveVersion: originalAgent.active_intent_version,
    originalBindings,
    originalAllowedTools,
    sessionUploads: [],
    dualSourceChecks: {
      groupA: {
        knowledgeBaseFacts: [scenario.groupAKbMarker, "Dana Brooks"],
        sessionFileFacts: [scenario.groupAFileRoom, scenario.groupAFileMarker],
        forbiddenFacts: [scenario.groupBKbMarker, scenario.groupBFileMarker],
      },
      groupB: {
        knowledgeBaseFacts: [scenario.groupBKbMarker, "Robin Hale"],
        sessionFileFacts: [scenario.groupBOldLocker, scenario.groupBFileMarker],
        forbiddenFacts: [scenario.groupAKbMarker, scenario.groupAFileMarker],
      },
    },
  };
  await mkdir(AUTH_DIR, { recursive: true });
  await writeFile(MANIFEST_PATH, `${JSON.stringify(manifest, null, 2)}\n`);
  await context.dispose();
  return manifest;
}

async function switchNoIntent(manifest) {
  await withDb((client) =>
    client.query(
      "UPDATE agent SET active_intent_version = NULL, updated_at = NOW() WHERE id = $1::uuid",
      [ASSISTANT_ID],
    ),
  );
  return { activeIntentVersion: null, scenario: manifest.scenario };
}

async function cleanup(manifest) {
  const users = JSON.parse(await readFile(USERS_PATH, "utf8"));
  const context = await playwrightRequest.newContext({ baseURL: API_BASE_URL });
  const auth = await login(context, users.admin);
  const token = auth.accessToken;
  const normalAuth = await login(context, users.normal);
  for (const upload of manifest.sessionUploads ?? []) {
    await api(
      context,
      "DELETE",
      `/api/chat-sessions/${upload.sessionId}/files/${upload.sessionFileId}`,
      normalAuth.accessToken,
    ).catch(() => undefined);
  }
  await withDb(async (client) => {
    await client.query("BEGIN");
    try {
      await client.query(
        "UPDATE agent SET active_intent_version = $2, allowed_tools = $3::jsonb, updated_at = NOW() WHERE id = $1::uuid",
        [ASSISTANT_ID, manifest.originalActiveVersion ?? null, JSON.stringify(manifest.originalAllowedTools ?? [])],
      );
      await client.query(
        "DELETE FROM intent_node WHERE agent_id = $1::uuid AND name LIKE $2",
        [ASSISTANT_ID, `${manifest.intentPrefix}%`],
      );
      await client.query("COMMIT");
    } catch (error) {
      await client.query("ROLLBACK");
      throw error;
    }
  });
  await api(context, "PUT", "/api/admin/assistant/knowledge-bases", token, {
    knowledgeBaseIds: manifest.originalBindings ?? [],
  });
  for (const knowledgeBaseId of manifest.knowledgeBaseIds ?? []) {
    await api(
      context,
      "DELETE",
      `/api/admin/knowledge-bases/${knowledgeBaseId}`,
      token,
    ).catch(() => undefined);
  }
  if (manifest.mcpServerId) {
    await api(
      context,
      "DELETE",
      `/api/admin/mcp-servers/${manifest.mcpServerId}?force=true`,
      token,
    ).catch(() => undefined);
  }
  await context.dispose();
  return { cleaned: true };
}

let result;
if (command === "setup") {
  result = await setup();
} else {
  const manifest = JSON.parse(await readFile(MANIFEST_PATH, "utf8"));
  if (command === "attach-group-a") {
    result = await attachSessionFile(manifest, "group-a", commandArg);
  } else if (command === "attach-group-b") {
    result = await attachSessionFile(manifest, "group-b", commandArg);
  } else if (command === "switch-no-intent") {
    result = await switchNoIntent(manifest);
  } else if (command === "cleanup") {
    result = await cleanup(manifest);
  } else if (command === "show") {
    result = manifest;
  } else {
    throw new Error(`Unknown adaptive-live command: ${command}`);
  }
}

console.log(JSON.stringify(result, null, 2));
