import { writeFile } from "node:fs/promises";
import { cleanupManifestPath, e2eRunId } from "./env";
import type { E2eUser } from "./auth";

export interface CleanupManifest {
  runId: string;
  users: Array<{ username: string; role: "user" | "admin" }>;
}

export function createCleanupManifest(): CleanupManifest {
  return {
    runId: e2eRunId,
    users: [],
  };
}

export function recordUser(
  manifest: CleanupManifest,
  user: E2eUser,
  role: "user" | "admin",
): void {
  manifest.users.push({ username: user.username, role });
}

export async function writeCleanupManifest(
  manifest: CleanupManifest,
): Promise<void> {
  await writeFile(cleanupManifestPath, `${JSON.stringify(manifest, null, 2)}\n`);
}
