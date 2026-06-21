import { spawn } from "node:child_process";

const tiers = [
  "@smoke",
  "@core-agent",
  "@memory",
  "@rag",
  "@vlm",
  "@intent",
  "@tools",
  "@mixed-dialogue",
  "@admin",
  "@routing",
];

const isWindows = process.platform === "win32";
const command = isWindows ? "cmd.exe" : "npx";
const timestamp = new Date()
  .toISOString()
  .replace(/[-:.TZ]/g, "")
  .slice(0, 14);

for (const tier of tiers) {
  const safeTier = tier.replace(/[^a-z0-9]+/gi, "-").replace(/^-|-$/g, "");
  const runId = `full-${safeTier}-${timestamp}`;
  const env = {
    ...process.env,
    PLAYWRIGHT_E2E_RUN_ID: runId,
  };
  if (tier === "@routing") {
    const routingMode = process.env.PLAYWRIGHT_ROUTING_PROVIDER_MODE?.trim();
    if (!routingMode) {
      env.PLAYWRIGHT_ROUTING_PROVIDER_MODE = "real";
      console.log(
        "[headed-full] PLAYWRIGHT_ROUTING_PROVIDER_MODE is not set; " +
          "defaulting @routing to real-provider smoke. Set it to fixture " +
          "and start the routing fixture for deterministic fault injection.",
      );
    } else if (!["fixture", "real"].includes(routingMode)) {
      console.error(
        `[headed-full] Unsupported PLAYWRIGHT_ROUTING_PROVIDER_MODE=${routingMode}; expected fixture or real.`,
      );
      process.exit(1);
    }
  }
  console.log(`\n[headed-full] Running ${tier} with PLAYWRIGHT_E2E_RUN_ID=${runId}`);
  const status = await runTier(tier, env);
  if (status !== 0) {
    console.error(`[headed-full] ${tier} failed with exit code ${status}`);
    process.exit(status ?? 1);
  }
}

console.log("\n[headed-full] All tiers completed.");

async function runTier(tier, env) {
  const args = isWindows
    ? ["/d", "/s", "/c", "npx.cmd", "playwright", "test", "--headed", "--grep", tier]
    : ["playwright", "test", "--headed", "--grep", tier];
  const child = spawn(command, args, {
    cwd: process.cwd(),
    env,
    shell: false,
    stdio: ["ignore", "pipe", "pipe"],
  });

  child.stdout.pipe(process.stdout);
  child.stderr.pipe(process.stderr);

  return new Promise((resolve) => {
    child.on("error", (error) => {
      console.error(`[headed-full] Failed to start ${tier}: ${error.message}`);
      resolve(1);
    });
    child.on("close", (code) => {
      resolve(code ?? 1);
    });
  });
}
