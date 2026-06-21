// Playwright AX driver — a tiny HTTP service that wraps one persistent, headed
// Chromium page so an agent (Claude Code) can drive the live ChatAgent UI and read
// the accessibility tree as text (token-cheap, screenshot-free), à la the
// "Claude Code + Playwright CLI" workflow.
//
// Start:  npm run e2e                 (or: node e2e/driver/server.mjs)
// Drive:  curl the JSON endpoints below from another shell.
//
// Endpoints:
//   GET  /ping            -> { ok: true }
//   GET  /url             -> { url }
//   GET  /title           -> { title }
//   POST /goto   { url }  -> navigate; returns { url }
//   POST /act    { locator, action, text?, key?, files? }  -> click/fill/press/setInputFiles a locator
//   GET  /ax[?max=200]    -> compact accessibility tree (role + name + value)
//   GET  /axjson          -> full accessibility snapshot JSON
//   GET  /shot[?full=1]   -> save screenshot to .auth/shot.png; returns { file }
//   POST /eval   { fn }   -> page.evaluate(fn); returns { result }
//   GET  /cookies         -> list cookies (name/domain/path only)
//   POST /reset           -> clearCookies(); returns { ok }
//
// locator shapes: { role, name? } | { placeholder } | { label } | { text, exact? }
//                  | { testid } | { css }
// action: "click" | "fill" (needs text) | "press" (needs key, default "Enter")
//       | "setInputFiles" (needs files: [{ name, mimeType?, base64 }])
//
// Auth persistence: uses launchPersistentContext with a user-data-dir under .auth/,
// so cookies/localStorage survive across restarts (login once, reuse).

import { createServer } from "node:http";
import { chromium } from "@playwright/test";
import { mkdir } from "node:fs/promises";
import path from "node:path";

const UI_BASE_URL = process.env.PLAYWRIGHT_UI_BASE_URL ?? "http://localhost:5173";
const PROFILE_DIR = path.resolve(process.cwd(), ".auth/browser-profile");
const SHOT_FILE = path.resolve(process.cwd(), ".auth/shot.png");
const DRIVER_PORT = Number(process.env.PLAYWRIGHT_DRIVER_PORT ?? 7878);
const HEADLESS = process.env.PLAYWRIGHT_HEADLESS === "1";

await mkdir(path.dirname(PROFILE_DIR), { recursive: true });

const context = await chromium.launchPersistentContext(PROFILE_DIR, {
  headless: HEADLESS,
  viewport: { width: 1366, height: 900 },
});
const page = context.pages()[0] ?? (await context.newPage());

function send(res, code, body) {
  res.writeHead(code, { "content-type": "application/json" });
  res.end(JSON.stringify(body));
}
function readBody(req) {
  return new Promise((resolve) => {
    let data = "";
    req.on("data", (chunk) => (data += chunk));
    req.on("end", () => resolve(data ? JSON.parse(data) : {}));
  });
}
function locate(desc) {
  if (!desc) throw new Error("missing locator");
  if (desc.role) return page.getByRole(desc.role, desc.name ? { name: desc.name } : undefined);
  if (desc.placeholder) return page.getByPlaceholder(desc.placeholder, { exact: Boolean(desc.exact) });
  if (desc.label) return page.getByLabel(desc.label);
  if (desc.text) return page.getByText(desc.text, { exact: Boolean(desc.exact) });
  if (desc.testid) return page.getByTestId(desc.testid);
  if (desc.css) return page.locator(desc.css);
  throw new Error(`unsupported locator: ${JSON.stringify(desc)}`);
}
// Flatten the AX snapshot into compact, indented "role \"name\"" lines.
function flattenAx(node, depth, out) {
  if (!node) return out;
  const parts = [node.role];
  if (node.name) parts.push(`"${node.name}"`);
  if (node.value !== undefined && node.value !== null && String(node.value)) {
    parts.push(`="${String(node.value).slice(0, 60)}"`);
  }
  if (node.checked !== undefined) parts.push(`[checked=${node.checked}]`);
  if (node.selected !== undefined) parts.push(`[selected=${node.selected}]`);
  out.push(`${"  ".repeat(depth)}${parts.join(" ")}`);
  for (const child of node.children ?? []) flattenAx(child, depth + 1, out);
  return out;
}

const server = createServer(async (req, res) => {
  try {
    const u = new URL(req.url, `http://localhost:${DRIVER_PORT}`);
    const m = req.method;
    if (m === "GET" && u.pathname === "/ping") return send(res, 200, { ok: true });
    if (m === "GET" && u.pathname === "/url") return send(res, 200, { url: page.url() });
    if (m === "GET" && u.pathname === "/title") return send(res, 200, { title: await page.title() });
    if (m === "GET" && u.pathname === "/ax") {
      const max = Number(u.searchParams.get("max") ?? 200);
      const snap = await page.locator("body").ariaSnapshot();
      const all = snap.split("\n");
      return send(res, 200, { url: page.url(), lines: all.slice(0, max), truncated: all.length > max });
    }
    if (m === "GET" && u.pathname === "/axjson") {
      const snap = await page.locator("body").ariaSnapshot();
      return send(res, 200, { url: page.url(), ax: snap });
    }
    if (m === "GET" && u.pathname === "/shot") {
      await page.screenshot({ path: SHOT_FILE, fullPage: u.searchParams.get("full") === "1" });
      return send(res, 200, { file: SHOT_FILE });
    }
    if (m === "GET" && u.pathname === "/cookies") {
      const cookies = (await context.cookies()).map((c) => ({
        name: c.name, domain: c.domain, path: c.path,
      }));
      return send(res, 200, { cookies });
    }
    if (m === "POST" && u.pathname === "/goto") {
      const b = await readBody(req);
      await page.goto(b.url ?? UI_BASE_URL, { waitUntil: "domcontentloaded", timeout: 30_000 });
      return send(res, 200, { url: page.url() });
    }
    if (m === "POST" && u.pathname === "/act") {
      const b = await readBody(req);
      const loc = locate(b.locator);
      if (b.action === "click") await loc.first().click({ timeout: 10_000 });
      else if (b.action === "fill") await loc.first().fill(b.text ?? "", { timeout: 10_000 });
      else if (b.action === "press") await loc.first().press(b.key ?? "Enter", { timeout: 10_000 });
      else if (b.action === "setInputFiles") {
        const files = (b.files ?? []).map((file) => ({
          name: file.name,
          mimeType: file.mimeType,
          buffer: Buffer.from(file.base64 ?? "", "base64"),
        }));
        await loc.first().setInputFiles(files, { timeout: 10_000 });
      }
      else throw new Error(`unknown action: ${b.action}`);
      return send(res, 200, { ok: true });
    }
    if (m === "POST" && u.pathname === "/eval") {
      const b = await readBody(req);
      return send(res, 200, { result: await page.evaluate(b.fn) });
    }
    if (m === "POST" && u.pathname === "/reset") {
      await context.clearCookies();
      return send(res, 200, { ok: true });
    }
    return send(res, 404, { error: `unknown route: ${m} ${u.pathname}` });
  } catch (error) {
    return send(res, 500, { error: String(error?.message ?? error) });
  }
});

server.listen(DRIVER_PORT, "127.0.0.1", () => {
  console.log(`playwright-ax-driver on http://127.0.0.1:${DRIVER_PORT} (headed=${!HEADLESS}, profile=${PROFILE_DIR})`);
});

for (const sig of ["SIGINT", "SIGTERM"]) {
  process.on(sig, async () => {
    try { await context.close(); } catch { /* ignore */ }
    process.exit(0);
  });
}
