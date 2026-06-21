import { test as base, expect } from "@playwright/test";

function isExpectedAbortedRequest(url: string, failureText?: string): boolean {
  return url.includes("/api/sse/connect/") && failureText === "net::ERR_ABORTED";
}

export const test = base.extend({
  page: async ({ page }, use, testInfo) => {
    const diagnostics: string[] = [];

    page.on("console", (message) => {
      if (message.type() === "error") {
        diagnostics.push(`console.error: ${message.text()}`);
      }
    });

    page.on("requestfailed", (request) => {
      const failureText = request.failure()?.errorText;
      if (!isExpectedAbortedRequest(request.url(), failureText)) {
        diagnostics.push(`requestfailed: ${request.method()} ${request.url()} ${failureText}`);
      }
    });

    page.on("response", (response) => {
      if (response.status() >= 500) {
        diagnostics.push(`response ${response.status()}: ${response.url()}`);
      }
    });

    await use(page);

    if (diagnostics.length > 0) {
      await testInfo.attach("browser-diagnostics", {
        body: diagnostics.join("\n"),
        contentType: "text/plain",
      });
    }
  },
});

export { expect };
