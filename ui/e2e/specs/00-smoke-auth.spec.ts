import { adminStorageStatePath, normalStorageStatePath } from "../helpers/env";
import { loginThroughUi } from "../helpers/auth";
import { readE2eUsers } from "../helpers/testUsers";
import { expect, test } from "../fixtures";

test.describe("@smoke unauthenticated shell", () => {
  test("redirects / to /chat without auto-opening auth", async ({ page }) => {
    await page.goto("/");

    await expect(page).toHaveURL(/\/chat$/);
    await expect(page.getByText("Start with a question")).toBeVisible();
    await expect(page.getByRole("button", { name: "Log in", exact: true })).toBeVisible();
    await expect(page.getByPlaceholder("Ask anything")).toBeVisible();
    await expect(page.getByText("Log in or sign up")).toHaveCount(0);
  });
});

test.describe("@smoke normal user auth", () => {
  test.use({ storageState: normalStorageStatePath });

  test("rehydrates from refresh cookie and opens a generated session", async ({ page }) => {
    const users = await readE2eUsers();
    await loginThroughUi(page, users.normal, normalStorageStatePath);

    const refreshResponse = page.waitForResponse(
      (response) =>
        response.url().includes("/api/auth/refresh") && response.status() === 200,
    );

    await page.reload();
    await refreshResponse;
    await page.context().storageState({ path: normalStorageStatePath });

    await expect(page.getByText(users.normal.username)).toBeVisible();
    await expect(page.getByText(/Signed in as user/i)).toBeVisible();

    const sessionId = users.normalSmokeSessionId;
    await page.goto(`/chat/${sessionId}`);

    await expect(page).toHaveURL(new RegExp(`/chat/${sessionId}$`));
    await expect(page.getByPlaceholder("Ask anything")).toBeVisible();
  });

  test("cannot enter the admin console", async ({ page }) => {
    const users = await readE2eUsers();
    await loginThroughUi(page, users.normal, normalStorageStatePath);

    await page.goto("/admin");
    await page.context().storageState({ path: normalStorageStatePath });

    await expect(page).toHaveURL(/\/chat$/);
    await expect(page.getByText("Admin / Dashboard")).toHaveCount(0);
  });
});

test.describe("@smoke admin route guard", () => {
  test.use({ storageState: adminStorageStatePath });

  test("allows generated admin fixture into the admin dashboard", async ({ page }) => {
    const users = await readE2eUsers();
    await loginThroughUi(page, users.admin, adminStorageStatePath);
    await page.goto("/admin");
    await page.context().storageState({ path: adminStorageStatePath });

    await expect(page).toHaveURL(/\/admin$/);
    await expect(page.getByText(users.admin.username)).toBeVisible();
    await expect(page.getByText("Admin / Dashboard")).toBeVisible();
  });
});
