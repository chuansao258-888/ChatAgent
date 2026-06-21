import pg, { type Client as PgClient, type QueryResultRow } from "pg";

interface DbConnectionConfig {
  host: string;
  port: number;
  database: string;
  user: string;
  password?: string;
}

export interface MemoryMarkerEvidence {
  [marker: string]: boolean;
}

export interface MemorySummarySegmentEvidence {
  id: string;
  seqStartNo: number;
  seqEndNo: number;
  turnCount: number;
  status: string;
  markers: MemoryMarkerEvidence;
}

export interface MemorySummaryEvidence {
  summarizedUntilSeqNo: number;
  segmentCount: number;
  markers: MemoryMarkerEvidence;
  segments: MemorySummarySegmentEvidence[];
}

export interface MemoryExtractionEvidence {
  id: string;
  seqStartNo: number;
  seqEndNo: number;
  status: string;
  hasError: boolean;
}

export interface MemoryItemEvidence {
  id: string;
  type: string;
  status: string;
  indexStatus: string;
  markers: MemoryMarkerEvidence;
}

export interface MemoryEvidence {
  sessionId: string;
  summary: MemorySummaryEvidence | null;
  extractions: MemoryExtractionEvidence[];
  items: MemoryItemEvidence[];
}

export interface TurnCompletionEvidence {
  turnSeq: number;
  lastCompletedTurnSeq: number;
  allMessagesCompleted: boolean;
}

export interface RagChunkEvidence {
  id: string;
  chunkIndex: number;
  sectionPath: string | null;
  engineId: string | null;
  modelId: string | null;
  degraded: boolean | null;
  markers: MemoryMarkerEvidence;
}

export interface KnowledgeDocumentEvidence {
  id: string;
  filename: string;
  parseStatus: string;
  indexed: boolean;
  markers: MemoryMarkerEvidence;
  chunks: RagChunkEvidence[];
}

export interface KnowledgeBaseEvidence {
  knowledgeBaseId: string;
  documents: KnowledgeDocumentEvidence[];
}

export interface KnowledgeBaseBindingEvidence {
  knowledgeBaseId: string;
  assistantBindingCount: number;
  intentBindings: Array<{
    nodeId: string;
    version: number;
    status: string;
  }>;
}

export interface SessionFileEvidence {
  id: string;
  sessionId: string;
  filename: string;
  parseStatus: string;
  markers: MemoryMarkerEvidence;
  chunks: RagChunkEvidence[];
}

export interface ToolCallNameEvidence {
  id: string;
  name: string;
}

export interface ToolResponseNameEvidence {
  id: string;
  name: string;
  status: string | null;
  errorCode: string | null;
  truncated: boolean | null;
}

export interface ToolCallEvidence {
  sessionId: string;
  turnId: string;
  calls: ToolCallNameEvidence[];
  responses: ToolResponseNameEvidence[];
}

export function hasCompletedToolEvidence(
  evidence: ToolCallEvidence,
  expectedToolNames: Iterable<string>,
): boolean {
  return [...expectedToolNames].every((toolName) =>
    evidence.calls.some((call) =>
      evidence.responses.some(
        (response) =>
          response.id === call.id &&
          response.name === call.name &&
          call.name === toolName &&
          (response.status === null || response.status === "ok"),
      ),
    ),
  );
}

export interface AssistantAllowedToolsEvidence {
  assistantId: string;
  allowedTools: string[];
}

export interface ActiveIntentNodeEvidence {
  id: string;
  parentId: string | null;
  version: number;
  name: string;
  intentKind: string | null;
  scopePolicy: string | null;
  allowedTools: string[];
  knowledgeBaseIds: string[];
}

export interface ActiveIntentRuntimeEvidence {
  assistantId: string;
  activeVersion: number | null;
  assistantAllowedTools: string[];
  nodes: ActiveIntentNodeEvidence[];
}

export interface IntentCleanupEvidence {
  assistantId: string;
  restoredActiveVersion: number | null;
  deletedVersions: number[];
  deletedNodeCount: number;
}

interface SummaryRow extends QueryResultRow {
  summarized_until_seq_no: string;
  segment_count: number;
  synopsis: string;
  structured_summary: string;
  anchored_entities: string;
}

interface SummarySegmentRow extends QueryResultRow {
  id: string;
  seq_start_no: string;
  seq_end_no: string;
  turn_count: number;
  status: string;
  segment_summary: string;
  structured_summary: string;
  anchored_entities: string;
}

interface ExtractionRow extends QueryResultRow {
  id: string;
  seq_start_no: string;
  seq_end_no: string;
  status: string;
  has_error: boolean;
}

interface MemoryItemRow extends QueryResultRow {
  id: string;
  type: string;
  status: string;
  index_status: string;
  content: string;
}

interface TurnCompletionRow extends QueryResultRow {
  turn_seq: string;
  last_completed_turn_seq: string;
  all_messages_completed: boolean;
}

interface RagDocumentRow extends QueryResultRow {
  id: string;
  filename: string;
  parse_status: string;
  indexed: boolean;
}

interface RagChunkRow extends QueryResultRow {
  id: string;
  parent_id: string;
  chunk_index: number;
  section_path: string | null;
  engine_id: string | null;
  model_id: string | null;
  degraded: boolean | null;
  content: string;
}

interface KnowledgeBaseBindingCountRow extends QueryResultRow {
  binding_count: number;
}

interface IntentKnowledgeBaseBindingRow extends QueryResultRow {
  node_id: string;
  version: number;
  status: string;
}

interface SessionFileRow extends QueryResultRow {
  id: string;
  session_id: string;
  filename: string;
  parse_status: string;
}

interface ToolMessageRow extends QueryResultRow {
  role: string;
  metadata: string | null;
}

interface AssistantAllowedToolsRow extends QueryResultRow {
  id: string;
  allowed_tools: string | null;
}

interface ActiveIntentNodeRow extends QueryResultRow {
  id: string;
  parent_id: string | null;
  version: number;
  name: string;
  intent_kind: string | null;
  scope_policy: string | null;
  allowed_tools: string | null;
  knowledge_base_ids: string[] | null;
}

function parseJdbcPostgresUrl(value: string): URL {
  const normalized = value.startsWith("jdbc:") ? value.slice(5) : value;
  return new URL(normalized);
}

function resolveDbConfig(): DbConnectionConfig {
  const url = parseJdbcPostgresUrl(
    process.env.CHATAGENT_DB_URL?.trim() ||
      "jdbc:postgresql://localhost:5432/chatagent",
  );

  const password =
    (process.env.CHATAGENT_DB_PASSWORD ?? url.password) || undefined;

  return {
    host: url.hostname,
    port: Number(url.port || 5432),
    database: url.pathname.replace(/^\//, "") || "chatagent",
    user: process.env.CHATAGENT_DB_USERNAME?.trim() || url.username || "app",
    password,
  };
}

function createDbClient(): PgClient {
  const { Client } = pg;
  return new Client({
    ...resolveDbConfig(),
    connectionTimeoutMillis: 5_000,
  });
}

async function withDbClient<T>(
  operation: string,
  callback: (client: PgClient) => Promise<T>,
): Promise<T> {
  const client = createDbClient();
  try {
    await client.connect();
    return await callback(client);
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    throw new Error(
      `Unable to ${operation}. Ensure CHATAGENT_DB_URL, CHATAGENT_DB_USERNAME, and CHATAGENT_DB_PASSWORD point to the local test database. Cause: ${message}`,
    );
  } finally {
    await client.end().catch(() => undefined);
  }
}

function toNumber(value: string | number): number {
  return typeof value === "number" ? value : Number(value);
}

function markerEvidence(
  markers: string[],
  textParts: Array<string | null | undefined>,
): MemoryMarkerEvidence {
  const searchable = textParts.filter(Boolean).join("\n");
  return Object.fromEntries(
    markers.map((marker) => [marker, searchable.includes(marker)]),
  );
}

function normalizeMarkers(markers: string[]): string[] {
  const normalized = [...new Set(markers.map((marker) => marker.trim()))].filter(
    Boolean,
  );
  if (normalized.length === 0) {
    throw new Error("Memory evidence requires at least one non-empty marker.");
  }
  return normalized;
}

function normalizeToolNames(toolNames: string[]): string[] {
  return [...new Set(toolNames.map((toolName) => toolName.trim()))].filter(
    Boolean,
  );
}

function readObjectMetadata(value: string | null): Record<string, unknown> {
  if (!value) {
    return {};
  }
  try {
    const parsed = JSON.parse(value) as unknown;
    return parsed && typeof parsed === "object" && !Array.isArray(parsed)
      ? (parsed as Record<string, unknown>)
      : {};
  } catch {
    return {};
  }
}

function parseStringArrayJson(value: string | null): string[] {
  if (!value) {
    return [];
  }
  try {
    const parsed = JSON.parse(value) as unknown;
    return Array.isArray(parsed)
      ? parsed.filter((item): item is string => typeof item === "string")
      : [];
  } catch {
    return [];
  }
}

function readStringProperty(value: unknown, property: string): string | null {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    return null;
  }
  const candidate = (value as Record<string, unknown>)[property];
  return typeof candidate === "string" ? candidate : null;
}

function readBooleanProperty(value: unknown, property: string): boolean | null {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    return null;
  }
  const candidate = (value as Record<string, unknown>)[property];
  return typeof candidate === "boolean" ? candidate : null;
}

function parseToolResponseEnvelope(responseData: unknown): {
  status: string | null;
  errorCode: string | null;
  truncated: boolean | null;
} {
  if (typeof responseData !== "string") {
    return { status: null, errorCode: null, truncated: null };
  }
  try {
    const parsed = JSON.parse(responseData) as unknown;
    return {
      status: readStringProperty(parsed, "status"),
      errorCode: readStringProperty(parsed, "errorCode"),
      truncated: readBooleanProperty(parsed, "truncated"),
    };
  } catch {
    return { status: null, errorCode: null, truncated: null };
  }
}

export async function promoteUserToAdmin(username: string): Promise<void> {
  await withDbClient("promote the generated admin fixture", async (client) => {
    const result = await client.query(
      "UPDATE t_user SET role = 'admin', updated_at = NOW() WHERE username = $1 AND deleted = FALSE RETURNING id",
      [username],
    );
    if (result.rowCount !== 1) {
      throw new Error("expected exactly one generated user row to be promoted");
    }
  });
}

export async function readToolCallEvidence(
  sessionId: string,
  turnId: string,
): Promise<ToolCallEvidence> {
  return withDbClient("read redacted tool-call evidence", async (client) => {
    const result = await client.query<ToolMessageRow>(
      `SELECT role,
              metadata::text AS metadata
         FROM chat_message
        WHERE session_id = $1
          AND turn_id = $2
        ORDER BY seq_no`,
      [sessionId, turnId],
    );

    const calls: ToolCallNameEvidence[] = [];
    const responses: ToolResponseNameEvidence[] = [];
    for (const row of result.rows) {
      const metadata = readObjectMetadata(row.metadata);
      const toolCalls = metadata.toolCalls;
      if (Array.isArray(toolCalls)) {
        for (const call of toolCalls) {
          const id = readStringProperty(call, "id");
          const name = readStringProperty(call, "name");
          if (id && name) {
            calls.push({ id, name });
          }
        }
      }
      const toolResponse = metadata.toolResponse;
      const id = readStringProperty(toolResponse, "id");
      const name = readStringProperty(toolResponse, "name");
      if (id && name) {
        const envelope = parseToolResponseEnvelope(
          (toolResponse as Record<string, unknown>).responseData,
        );
        responses.push({
          id,
          name,
          status: envelope.status,
          errorCode: envelope.errorCode,
          truncated: envelope.truncated,
        });
      }
    }

    return {
      sessionId,
      turnId,
      calls,
      responses,
    };
  });
}

export async function readAssistantAllowedTools(
  assistantId: string,
): Promise<AssistantAllowedToolsEvidence> {
  return withDbClient("read assistant allowed-tools evidence", async (client) => {
    const result = await client.query<AssistantAllowedToolsRow>(
      `SELECT id::text,
              allowed_tools::text AS allowed_tools
         FROM agent
        WHERE id = CAST($1 AS uuid)`,
      [assistantId],
    );
    const row = result.rows[0];
    if (!row) {
      throw new Error(`Assistant not found: ${assistantId}`);
    }
    return {
      assistantId: row.id,
      allowedTools: parseStringArrayJson(row.allowed_tools),
    };
  });
}

export async function setAssistantAllowedTools(
  assistantId: string,
  allowedTools: string[],
): Promise<AssistantAllowedToolsEvidence> {
  const normalized = normalizeToolNames(allowedTools);
  return withDbClient("set assistant allowed-tools evidence", async (client) => {
    const result = await client.query<AssistantAllowedToolsRow>(
      `UPDATE agent
          SET allowed_tools = $2::jsonb,
              updated_at = NOW()
        WHERE id = CAST($1 AS uuid)
        RETURNING id::text,
                  allowed_tools::text AS allowed_tools`,
      [assistantId, JSON.stringify(normalized)],
    );
    const row = result.rows[0];
    if (!row) {
      throw new Error(`Assistant not found: ${assistantId}`);
    }
    return {
      assistantId: row.id,
      allowedTools: parseStringArrayJson(row.allowed_tools),
    };
  });
}

export async function readActiveIntentRuntimeEvidence(
  assistantId: string,
): Promise<ActiveIntentRuntimeEvidence> {
  return withDbClient("read redacted active intent runtime evidence", async (client) => {
    const assistantResult = await client.query<
      QueryResultRow & {
        id: string;
        active_intent_version: number | null;
        allowed_tools: string | null;
      }
    >(
      `SELECT id::text,
              active_intent_version,
              allowed_tools::text AS allowed_tools
         FROM agent
        WHERE id = CAST($1 AS uuid)`,
      [assistantId],
    );
    const assistant = assistantResult.rows[0];
    if (!assistant) {
      throw new Error(`Assistant not found: ${assistantId}`);
    }

    let rows: ActiveIntentNodeRow[] = [];
    if (assistant.active_intent_version != null) {
      const nodeResult = await client.query<ActiveIntentNodeRow>(
        `SELECT node.id::text,
                node.parent_id::text,
                node.version,
                node.name,
                node.intent_kind,
                node.scope_policy,
                node.allowed_tools::text AS allowed_tools,
                COALESCE(
                  ARRAY_AGG(binding.knowledge_base_id::text)
                    FILTER (WHERE binding.knowledge_base_id IS NOT NULL),
                  ARRAY[]::text[]
                ) AS knowledge_base_ids
           FROM intent_node node
           LEFT JOIN intent_knowledge_base binding ON binding.intent_node_id = node.id
          WHERE node.agent_id = CAST($1 AS uuid)
            AND node.version = $2
            AND node.status = 'PUBLISHED'
          GROUP BY node.id
          ORDER BY node.sort_order, node.id`,
        [assistantId, assistant.active_intent_version],
      );
      rows = nodeResult.rows;
    }

    return {
      assistantId: assistant.id,
      activeVersion: assistant.active_intent_version,
      assistantAllowedTools: parseStringArrayJson(assistant.allowed_tools),
      nodes: rows.map((row) => ({
        id: row.id,
        parentId: row.parent_id,
        version: row.version,
        name: row.name,
        intentKind: row.intent_kind,
        scopePolicy: row.scope_policy,
        allowedTools: parseStringArrayJson(row.allowed_tools),
        knowledgeBaseIds: row.knowledge_base_ids ?? [],
      })),
    };
  });
}

export async function setActiveIntentVersion(
  assistantId: string,
  activeVersion: number | null,
): Promise<ActiveIntentRuntimeEvidence> {
  return withDbClient("set active intent version evidence", async (client) => {
    const result = await client.query<QueryResultRow & { id: string }>(
      `UPDATE agent
          SET active_intent_version = $2,
              updated_at = NOW()
        WHERE id = CAST($1 AS uuid)
        RETURNING id::text AS id`,
      [assistantId, activeVersion],
    );
    if (!result.rows[0]) {
      throw new Error(`Assistant not found: ${assistantId}`);
    }
    return readActiveIntentRuntimeEvidence(assistantId);
  });
}

export async function cleanupGeneratedIntentArtifacts(
  assistantId: string,
  generatedNamePrefix: string,
  generatedVersions: number[],
  originalActiveVersion: number | null,
): Promise<IntentCleanupEvidence> {
  const versions = [...new Set(generatedVersions)].sort((left, right) => left - right);
  if (!generatedNamePrefix.startsWith("E2E Intent ")) {
    throw new Error("Generated intent cleanup requires the E2E Intent prefix.");
  }
  if (originalActiveVersion != null && versions.includes(originalActiveVersion)) {
    throw new Error("Refusing to delete the original active intent version.");
  }

  return withDbClient("clean generated intent artifacts", async (client) => {
    await client.query("BEGIN");
    try {
      const publishedResult = versions.length
        ? await client.query<QueryResultRow & { version: number; generated_count: number }>(
            `SELECT version,
                    COUNT(*) FILTER (WHERE name LIKE $3)::int AS generated_count
               FROM intent_node
              WHERE agent_id = CAST($1 AS uuid)
                AND version = ANY($2::int[])
              GROUP BY version`,
            [assistantId, versions, `${generatedNamePrefix}%`],
          )
        : { rows: [] };
      const verifiedVersions = new Set(
        publishedResult.rows
          .filter((row) => row.generated_count > 0)
          .map((row) => row.version),
      );
      if (versions.some((version) => !verifiedVersions.has(version))) {
        throw new Error("Refusing to delete an intent version without generated marker nodes.");
      }

      await client.query(
        `UPDATE agent
            SET active_intent_version = $2,
                updated_at = NOW()
          WHERE id = CAST($1 AS uuid)`,
        [assistantId, originalActiveVersion],
      );

      const candidateResult = await client.query<QueryResultRow & { id: string }>(
        `SELECT id::text
           FROM intent_node
          WHERE agent_id = CAST($1 AS uuid)
            AND (
              version = ANY($2::int[])
              OR (version = 0 AND name LIKE $3)
            )`,
        [assistantId, versions, `${generatedNamePrefix}%`],
      );
      const nodeIds = candidateResult.rows.map((row) => row.id);
      if (nodeIds.length > 0) {
        await client.query(
          `DELETE FROM intent_knowledge_base
            WHERE intent_node_id = ANY($1::uuid[])`,
          [nodeIds],
        );
        await client.query(
          `DELETE FROM intent_node
            WHERE id = ANY($1::uuid[])`,
          [nodeIds],
        );
      }
      await client.query("COMMIT");
      return {
        assistantId,
        restoredActiveVersion: originalActiveVersion,
        deletedVersions: versions,
        deletedNodeCount: nodeIds.length,
      };
    } catch (error) {
      await client.query("ROLLBACK");
      throw error;
    }
  });
}

export function preservedActiveIntentVersion(
  activeVersion: number | null,
  generatedVersions: number[],
): number | null {
  return activeVersion != null && !generatedVersions.includes(activeVersion)
    ? activeVersion
    : null;
}

export async function cleanupStaleGeneratedIntentArtifacts(
  assistantId: string,
  generatedNamePrefix: string,
): Promise<IntentCleanupEvidence> {
  if (!generatedNamePrefix.startsWith("E2E Intent ")) {
    throw new Error("Stale intent cleanup requires the E2E Intent prefix.");
  }
  const discovery = await withDbClient(
    "discover stale generated intent artifacts",
    async (client) => {
      const assistantResult = await client.query<
        QueryResultRow & { active_intent_version: number | null }
      >(
        `SELECT active_intent_version
           FROM agent
          WHERE id = CAST($1 AS uuid)`,
        [assistantId],
      );
      if (!assistantResult.rows[0]) {
        throw new Error(`Assistant not found: ${assistantId}`);
      }
      const versionResult = await client.query<
        QueryResultRow & { version: number }
      >(
        `SELECT DISTINCT version
           FROM intent_node
          WHERE agent_id = CAST($1 AS uuid)
            AND version > 0
            AND name LIKE $2
          ORDER BY version`,
        [assistantId, `${generatedNamePrefix}%`],
      );
      return {
        activeVersion: assistantResult.rows[0].active_intent_version,
        generatedVersions: versionResult.rows.map((row) => row.version),
      };
    },
  );

  return cleanupGeneratedIntentArtifacts(
    assistantId,
    generatedNamePrefix,
    discovery.generatedVersions,
    preservedActiveIntentVersion(
      discovery.activeVersion,
      discovery.generatedVersions,
    ),
  );
}

export async function waitForToolCallEvidence(
  sessionId: string,
  turnId: string,
  expectedToolNames: string[],
  options: { timeoutMs?: number; intervalMs?: number } = {},
): Promise<ToolCallEvidence> {
  const expected = new Set(expectedToolNames);
  const timeoutMs = options.timeoutMs ?? 120_000;
  const intervalMs = options.intervalMs ?? 1_000;
  const deadline = Date.now() + timeoutMs;
  let lastEvidence = await readToolCallEvidence(sessionId, turnId);

  while (Date.now() < deadline) {
    if (hasCompletedToolEvidence(lastEvidence, expected)) {
      return lastEvidence;
    }
    await new Promise((resolve) => setTimeout(resolve, intervalMs));
    lastEvidence = await readToolCallEvidence(sessionId, turnId);
  }

  throw new Error(
    `Timed out waiting for redacted tool-call evidence: ${JSON.stringify(lastEvidence)}`,
  );
}

export async function readMemoryEvidence(
  username: string,
  sessionId: string,
  requestedMarkers: string[],
): Promise<MemoryEvidence> {
  const markers = normalizeMarkers(requestedMarkers);
  return withDbClient("read redacted memory evidence", async (client) => {
    const summaryResult = await client.query<SummaryRow>(
          `SELECT summarized_until_seq_no::text,
                  segment_count,
                  synopsis,
                  structured_summary::text,
                  anchored_entities::text
             FROM chat_session_summary
            WHERE session_id = $1`,
          [sessionId],
        );
    const segmentResult = await client.query<SummarySegmentRow>(
          `SELECT id::text,
                  seq_start_no::text,
                  seq_end_no::text,
                  turn_count,
                  status,
                  segment_summary,
                  structured_summary::text,
                  anchored_entities::text
             FROM chat_session_summary_segment
            WHERE session_id = $1
            ORDER BY seq_start_no, seq_end_no`,
          [sessionId],
        );
    const extractionResult = await client.query<ExtractionRow>(
          `SELECT log.id::text,
                  log.seq_start_no::text,
                  log.seq_end_no::text,
                  log.status,
                  (log.error_message IS NOT NULL) AS has_error
             FROM memory_extraction_log log
             JOIN t_user usr ON usr.id = log.user_id
            WHERE usr.username = $1
              AND log.session_id = $2
            ORDER BY log.seq_start_no, log.seq_end_no`,
          [username, sessionId],
        );
    const itemResult = await client.query<MemoryItemRow>(
          `SELECT item.id::text,
                  item.type,
                  item.status,
                  item.index_status,
                  item.content
             FROM memory_item item
             JOIN t_user usr ON usr.id = item.user_id
            WHERE usr.username = $1
              AND item.source ->> 'session_id' = $2
            ORDER BY item.created_at, item.id`,
          [username, sessionId],
        );

    const segments = segmentResult.rows.map((row) => ({
      id: row.id,
      seqStartNo: toNumber(row.seq_start_no),
      seqEndNo: toNumber(row.seq_end_no),
      turnCount: row.turn_count,
      status: row.status,
      markers: markerEvidence(markers, [
        row.segment_summary,
        row.structured_summary,
        row.anchored_entities,
      ]),
    }));

    const summaryRow = summaryResult.rows[0];
    const summary = summaryRow
      ? {
          summarizedUntilSeqNo: toNumber(summaryRow.summarized_until_seq_no),
          segmentCount: summaryRow.segment_count,
          markers: markerEvidence(markers, [
            summaryRow.synopsis,
            summaryRow.structured_summary,
            summaryRow.anchored_entities,
          ]),
          segments,
        }
      : null;

    return {
      sessionId,
      summary,
      extractions: extractionResult.rows.map((row) => ({
        id: row.id,
        seqStartNo: toNumber(row.seq_start_no),
        seqEndNo: toNumber(row.seq_end_no),
        status: row.status,
        hasError: row.has_error,
      })),
      items: itemResult.rows.map((row) => ({
        id: row.id,
        type: row.type,
        status: row.status,
        indexStatus: row.index_status,
        markers: markerEvidence(markers, [row.content]),
      })),
    };
  });
}

export async function waitForMemoryEvidence(
  username: string,
  sessionId: string,
  markers: string[],
  predicate: (evidence: MemoryEvidence) => boolean,
  options: { timeoutMs?: number; intervalMs?: number } = {},
): Promise<MemoryEvidence> {
  const timeoutMs = options.timeoutMs ?? 180_000;
  const intervalMs = options.intervalMs ?? 1_000;
  const deadline = Date.now() + timeoutMs;
  let lastEvidence = await readMemoryEvidence(username, sessionId, markers);

  while (Date.now() < deadline) {
    if (predicate(lastEvidence)) {
      return lastEvidence;
    }
    await new Promise((resolve) => setTimeout(resolve, intervalMs));
    lastEvidence = await readMemoryEvidence(username, sessionId, markers);
  }

  throw new Error(
    `Timed out waiting for redacted memory evidence: ${JSON.stringify(lastEvidence)}`,
  );
}

export async function readTurnCompletion(
  sessionId: string,
  turnId: string,
): Promise<TurnCompletionEvidence | null> {
  return withDbClient("read turn completion evidence", async (client) => {
    const result = await client.query<TurnCompletionRow>(
      `SELECT MIN(message.turn_seq)::text AS turn_seq,
              session.last_completed_turn_seq::text AS last_completed_turn_seq,
              BOOL_AND(message.turn_completed) AS all_messages_completed
         FROM chat_message message
         JOIN chat_session session ON session.id = message.session_id
        WHERE message.session_id = $1
          AND message.turn_id = $2
        GROUP BY session.last_completed_turn_seq`,
      [sessionId, turnId],
    );
    const row = result.rows[0];
    return row
      ? {
          turnSeq: toNumber(row.turn_seq),
          lastCompletedTurnSeq: toNumber(row.last_completed_turn_seq),
          allMessagesCompleted: row.all_messages_completed,
        }
      : null;
  });
}

export async function waitForTurnCompletion(
  sessionId: string,
  turnId: string,
  options: { timeoutMs?: number; intervalMs?: number } = {},
): Promise<TurnCompletionEvidence> {
  const timeoutMs = options.timeoutMs ?? 30_000;
  const intervalMs = options.intervalMs ?? 250;
  const deadline = Date.now() + timeoutMs;
  let lastEvidence: TurnCompletionEvidence | null = null;

  while (Date.now() < deadline) {
    lastEvidence = await readTurnCompletion(sessionId, turnId);
    if (
      lastEvidence?.allMessagesCompleted &&
      lastEvidence.lastCompletedTurnSeq >= lastEvidence.turnSeq
    ) {
      return lastEvidence;
    }
    await new Promise((resolve) => setTimeout(resolve, intervalMs));
  }

  throw new Error(
    `Timed out waiting for completed turn watermark: ${JSON.stringify(lastEvidence)}`,
  );
}

export async function readKnowledgeBaseEvidence(
  knowledgeBaseId: string,
  requestedMarkers: string[],
): Promise<KnowledgeBaseEvidence> {
  const markers = normalizeMarkers(requestedMarkers);
  return withDbClient("read redacted knowledge-base evidence", async (client) => {
    const documentResult = await client.query<RagDocumentRow>(
      `SELECT id::text,
              COALESCE(original_filename, filename) AS filename,
              parse_status,
              (indexed_at IS NOT NULL) AS indexed
         FROM knowledge_document
        WHERE knowledge_base_id = $1
          AND deleted = FALSE
        ORDER BY created_at, id`,
      [knowledgeBaseId],
    );
    const chunkResult = await client.query<RagChunkRow>(
      `SELECT chunk.id::text,
              chunk.knowledge_document_id::text AS parent_id,
              chunk.chunk_index,
              COALESCE(
                chunk.metadata ->> 'sectionPath',
                chunk.metadata ->> 'headingPath',
                chunk.metadata ->> 'title',
                chunk.metadata ->> 'slideTitle'
              ) AS section_path,
              chunk.metadata ->> 'engineId' AS engine_id,
              chunk.metadata ->> 'modelId' AS model_id,
              CASE
                WHEN chunk.metadata ? 'degraded'
                THEN (chunk.metadata ->> 'degraded')::boolean
                ELSE NULL
              END AS degraded,
              chunk.content
         FROM knowledge_chunk chunk
         JOIN knowledge_document document ON document.id = chunk.knowledge_document_id
        WHERE document.knowledge_base_id = $1
          AND document.deleted = FALSE
          AND chunk.enabled = TRUE
        ORDER BY chunk.knowledge_document_id, chunk.chunk_index`,
      [knowledgeBaseId],
    );

    return {
      knowledgeBaseId,
      documents: documentResult.rows.map((document) => {
        const chunks = chunkResult.rows
          .filter((chunk) => chunk.parent_id === document.id)
          .map((chunk) => ({
            id: chunk.id,
            chunkIndex: chunk.chunk_index,
            sectionPath: chunk.section_path,
            engineId: chunk.engine_id,
            modelId: chunk.model_id,
            degraded: chunk.degraded,
            markers: markerEvidence(markers, [chunk.content]),
          }));
        return {
          id: document.id,
          filename: document.filename,
          parseStatus: document.parse_status,
          indexed: document.indexed,
          markers: markerEvidence(
            markers,
            chunkResult.rows
              .filter((chunk) => chunk.parent_id === document.id)
              .map((chunk) => chunk.content),
          ),
          chunks,
        };
      }),
    };
  });
}

export async function readKnowledgeBaseBindingEvidence(
  knowledgeBaseId: string,
): Promise<KnowledgeBaseBindingEvidence> {
  return withDbClient("read redacted knowledge-base binding evidence", async (client) => {
    const assistantResult = await client.query<KnowledgeBaseBindingCountRow>(
      `SELECT COUNT(*)::int AS binding_count
         FROM agent_knowledge_base
        WHERE knowledge_base_id = CAST($1 AS uuid)`,
      [knowledgeBaseId],
    );
    const intentResult = await client.query<IntentKnowledgeBaseBindingRow>(
      `SELECT node.id::text AS node_id,
              node.version,
              node.status
         FROM intent_knowledge_base binding
         JOIN intent_node node ON node.id = binding.intent_node_id
        WHERE binding.knowledge_base_id = CAST($1 AS uuid)
        ORDER BY node.version, node.id`,
      [knowledgeBaseId],
    );

    return {
      knowledgeBaseId,
      assistantBindingCount: assistantResult.rows[0]?.binding_count ?? 0,
      intentBindings: intentResult.rows.map((row) => ({
        nodeId: row.node_id,
        version: row.version,
        status: row.status,
      })),
    };
  });
}

export async function waitForKnowledgeBaseEvidence(
  knowledgeBaseId: string,
  markers: string[],
  predicate: (evidence: KnowledgeBaseEvidence) => boolean,
  options: { timeoutMs?: number; intervalMs?: number } = {},
): Promise<KnowledgeBaseEvidence> {
  const timeoutMs = options.timeoutMs ?? 300_000;
  const intervalMs = options.intervalMs ?? 1_000;
  const deadline = Date.now() + timeoutMs;
  let lastEvidence = await readKnowledgeBaseEvidence(knowledgeBaseId, markers);

  while (Date.now() < deadline) {
    if (predicate(lastEvidence)) {
      return lastEvidence;
    }
    await new Promise((resolve) => setTimeout(resolve, intervalMs));
    lastEvidence = await readKnowledgeBaseEvidence(knowledgeBaseId, markers);
  }

  throw new Error(
    `Timed out waiting for redacted knowledge-base evidence: ${JSON.stringify(lastEvidence)}`,
  );
}

export async function readSessionFileEvidence(
  sessionId: string,
  filename: string,
  requestedMarkers: string[],
): Promise<SessionFileEvidence | null> {
  const markers = normalizeMarkers(requestedMarkers);
  return withDbClient("read redacted session-file evidence", async (client) => {
    const fileResult = await client.query<SessionFileRow>(
      `SELECT id::text,
              session_id,
              original_filename AS filename,
              parse_status
         FROM chat_session_file
        WHERE session_id = $1
          AND original_filename = $2
        ORDER BY created_at DESC
        LIMIT 1`,
      [sessionId, filename],
    );
    const file = fileResult.rows[0];
    if (!file) {
      return null;
    }
    const chunkResult = await client.query<RagChunkRow>(
      `SELECT id::text,
              session_file_id::text AS parent_id,
              chunk_index,
              COALESCE(
                metadata ->> 'sectionPath',
                metadata ->> 'headingPath',
                metadata ->> 'title',
                metadata ->> 'slideTitle'
              ) AS section_path,
              metadata ->> 'engineId' AS engine_id,
              metadata ->> 'modelId' AS model_id,
              CASE
                WHEN metadata ? 'degraded'
                THEN (metadata ->> 'degraded')::boolean
                ELSE NULL
              END AS degraded,
              content
         FROM file_chunk
        WHERE session_file_id = $1
          AND enabled = TRUE
        ORDER BY chunk_index`,
      [file.id],
    );
    return {
      id: file.id,
      sessionId: file.session_id,
      filename: file.filename,
      parseStatus: file.parse_status,
      markers: markerEvidence(
        markers,
        chunkResult.rows.map((chunk) => chunk.content),
      ),
      chunks: chunkResult.rows.map((chunk) => ({
        id: chunk.id,
        chunkIndex: chunk.chunk_index,
        sectionPath: chunk.section_path,
        engineId: chunk.engine_id,
        modelId: chunk.model_id,
        degraded: chunk.degraded,
        markers: markerEvidence(markers, [chunk.content]),
      })),
    };
  });
}

export async function waitForSessionFileEvidence(
  sessionId: string,
  filename: string,
  markers: string[],
  predicate: (evidence: SessionFileEvidence) => boolean,
  options: { timeoutMs?: number; intervalMs?: number } = {},
): Promise<SessionFileEvidence> {
  const timeoutMs = options.timeoutMs ?? 300_000;
  const intervalMs = options.intervalMs ?? 1_000;
  const deadline = Date.now() + timeoutMs;
  let lastEvidence = await readSessionFileEvidence(sessionId, filename, markers);

  while (Date.now() < deadline) {
    if (lastEvidence && predicate(lastEvidence)) {
      return lastEvidence;
    }
    await new Promise((resolve) => setTimeout(resolve, intervalMs));
    lastEvidence = await readSessionFileEvidence(sessionId, filename, markers);
  }

  throw new Error(
    `Timed out waiting for redacted session-file evidence: ${JSON.stringify(lastEvidence)}`,
  );
}
