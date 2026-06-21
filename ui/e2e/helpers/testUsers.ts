import { readFile } from "node:fs/promises";
import { e2eUsersPath } from "./env";
import type { E2eUser } from "./auth";

export interface E2eUsersFile {
  runId: string;
  normal: E2eUser;
  admin: E2eUser;
  normalSmokeSessionId: string;
}

export async function readE2eUsers(): Promise<E2eUsersFile> {
  return JSON.parse(await readFile(e2eUsersPath, "utf8")) as E2eUsersFile;
}
