import { mkdir, writeFile } from "node:fs/promises";
import { request } from "@playwright/test";
import {
  adminStorageStatePath,
  apiBaseUrl,
  authDir,
  e2eRunId,
  e2eUsersPath,
  normalStorageStatePath,
} from "./helpers/env";
import {
  createE2eUser,
  getCurrentUser,
  loginUser,
  registerUser,
  waitForBackendHealth,
} from "./helpers/auth";
import {
  createCleanupManifest,
  recordUser,
  writeCleanupManifest,
} from "./helpers/cleanupManifest";
import { postApi } from "./helpers/api";
import { promoteUserToAdmin } from "./helpers/db";

export default async function globalSetup(): Promise<void> {
  await mkdir(authDir, { recursive: true });
  await waitForBackendHealth();

  const manifest = createCleanupManifest();
  const normal = createE2eUser("normal");
  const admin = createE2eUser("admin");
  let normalSmokeSessionId = "";

  const normalContext = await request.newContext({ baseURL: apiBaseUrl });
  try {
    const auth = await registerUser(normalContext, normal);
    await getCurrentUser(normalContext, auth.accessToken);
    normalSmokeSessionId = await postApi<string>(
      normalContext,
      "/api/chat-sessions",
      { title: `Smoke ${e2eRunId}` },
      auth.accessToken,
    );
    await normalContext.storageState({ path: normalStorageStatePath });
    recordUser(manifest, normal, "user");
  } finally {
    await normalContext.dispose();
  }
  if (!normalSmokeSessionId) {
    throw new Error("Failed to create the normal smoke chat session.");
  }

  const adminRegisterContext = await request.newContext({ baseURL: apiBaseUrl });
  try {
    await registerUser(adminRegisterContext, admin);
    await promoteUserToAdmin(admin.username);
    recordUser(manifest, admin, "admin");
  } finally {
    await adminRegisterContext.dispose();
  }

  const adminLoginContext = await request.newContext({ baseURL: apiBaseUrl });
  try {
    const auth = await loginUser(adminLoginContext, admin);
    const currentUser = await getCurrentUser(adminLoginContext, auth.accessToken);
    if (currentUser.role.toLowerCase() !== "admin") {
      throw new Error("Generated admin fixture did not receive the admin role.");
    }
    await adminLoginContext.storageState({ path: adminStorageStatePath });
  } finally {
    await adminLoginContext.dispose();
  }

  await writeFile(
    e2eUsersPath,
    `${JSON.stringify({ runId: e2eRunId, normal, admin, normalSmokeSessionId }, null, 2)}\n`,
  );
  await writeCleanupManifest(manifest);
}
