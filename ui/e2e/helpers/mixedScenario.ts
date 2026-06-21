import { createHash } from "node:crypto";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import path from "node:path";
import type { TestInfo } from "@playwright/test";

export interface MixedWorkProfile {
  id: string;
  label: string;
  ambiguousPrompt: string;
  groupA: {
    project: string;
    oldRoom: string;
    fileRoom: string;
    currentRoom: string;
    ownerInitial: string;
    ownerCurrent: string;
    fileSlug: string;
    desk: string;
    archive: string;
    workbench: string;
    notes: string;
    fileLabel: string;
    workContext: string;
  };
  groupB: {
    project: string;
    oldLocker: string;
    currentLocker: string;
    ownerInitial: string;
    ownerCurrent: string;
    fileSlug: string;
    workContext: string;
  };
}

interface ScenarioHistory {
  runId: string;
  profileId: string;
  fingerprint?: string;
  normalizedPrompts?: string[];
  completed?: boolean;
}

interface PromptRecord {
  group: string;
  turn: number;
  base: string;
  rendered: string;
}

export interface MixedScenarioRun {
  readonly runId: string;
  readonly runSuffix: string;
  readonly profile: MixedWorkProfile;
  renderPrompt(group: string, turn: number, base: string): string;
  persistManifest(
    testInfo: TestInfo,
    groupAPrompts: string[],
    groupBPrompts: string[],
    completed: boolean,
  ): Promise<void>;
}

const PROFILES: MixedWorkProfile[] = [
  {
    id: "customer-escalation",
    label: "Customer escalation handoff",
    ambiguousPrompt: "operations",
    groupA: {
      project: "Ridgewater",
      oldRoom: "Maple",
      fileRoom: "Cedar",
      currentRoom: "Iris",
      ownerInitial: "Maya",
      ownerCurrent: "Owen",
      fileSlug: "ridgewater-room-card",
      desk: "Ridgewater Desk",
      archive: "Operations Archive",
      workbench: "Escalation Workbench",
      notes: "Ridgewater Launch Notes",
      fileLabel: "Uploaded Room Card",
      workContext: "partner escalation rehearsal",
    },
    groupB: {
      project: "Harborlight",
      oldLocker: "Locker",
      currentLocker: "Vault",
      ownerInitial: "Nolan",
      ownerCurrent: "Priya",
      fileSlug: "harborlight-floor-note",
      workContext: "vendor readiness review",
    },
  },
  {
    id: "audit-readiness",
    label: "Audit evidence readiness",
    ambiguousPrompt: "project status",
    groupA: {
      project: "Bluehaven",
      oldRoom: "Juniper",
      fileRoom: "Alder",
      currentRoom: "Orchid",
      ownerInitial: "Elena",
      ownerCurrent: "Marcus",
      fileSlug: "bluehaven-review-card",
      desk: "Bluehaven Review Desk",
      archive: "Audit Archive",
      workbench: "Evidence Workbench",
      notes: "Bluehaven Review Notes",
      fileLabel: "Uploaded Review Card",
      workContext: "audit evidence walkthrough",
    },
    groupB: {
      project: "Ledgerstone",
      oldLocker: "Cabinet",
      currentLocker: "ArchiveBay",
      ownerInitial: "Lucas",
      ownerCurrent: "Sonia",
      fileSlug: "ledgerstone-storage-note",
      workContext: "external auditor check-in",
    },
  },
  {
    id: "release-cutover",
    label: "Release cutover coordination",
    ambiguousPrompt: "release status",
    groupA: {
      project: "Redcliff",
      oldRoom: "Delta",
      fileRoom: "Echo",
      currentRoom: "Foxtrot",
      ownerInitial: "Talia",
      ownerCurrent: "Victor",
      fileSlug: "redcliff-bridge-card",
      desk: "Redcliff Cutover Desk",
      archive: "Release Archive",
      workbench: "Cutover Workbench",
      notes: "Redcliff Cutover Notes",
      fileLabel: "Uploaded Bridge Card",
      workContext: "production cutover rehearsal",
    },
    groupB: {
      project: "Cloudline",
      oldLocker: "Rack",
      currentLocker: "Bay",
      ownerInitial: "Amir",
      ownerCurrent: "Jules",
      fileSlug: "cloudline-rack-note",
      workContext: "deployment readiness check",
    },
  },
  {
    id: "vendor-onboarding",
    label: "Vendor onboarding launch",
    ambiguousPrompt: "vendor status",
    groupA: {
      project: "Silvergate",
      oldRoom: "Birch",
      fileRoom: "Aspen",
      currentRoom: "Willow",
      ownerInitial: "Rina",
      ownerCurrent: "Caleb",
      fileSlug: "silvergate-kickoff-card",
      desk: "Silvergate Onboarding Desk",
      archive: "Vendor Archive",
      workbench: "Onboarding Workbench",
      notes: "Silvergate Kickoff Notes",
      fileLabel: "Uploaded Kickoff Card",
      workContext: "supplier onboarding kickoff",
    },
    groupB: {
      project: "Portwell",
      oldLocker: "Shelf",
      currentLocker: "Cage",
      ownerInitial: "Evan",
      ownerCurrent: "Leah",
      fileSlug: "portwell-access-note",
      workContext: "supplier access review",
    },
  },
  {
    id: "research-review",
    label: "Research review coordination",
    ambiguousPrompt: "review status",
    groupA: {
      project: "Fairwind",
      oldRoom: "Atlas",
      fileRoom: "Beacon",
      currentRoom: "Curie",
      ownerInitial: "Hana",
      ownerCurrent: "Dev",
      fileSlug: "fairwind-panel-card",
      desk: "Fairwind Review Desk",
      archive: "Research Archive",
      workbench: "Panel Workbench",
      notes: "Fairwind Review Notes",
      fileLabel: "Uploaded Panel Card",
      workContext: "research panel review",
    },
    groupB: {
      project: "Signalcrest",
      oldLocker: "Bench",
      currentLocker: "Store",
      ownerInitial: "Niko",
      ownerCurrent: "Aria",
      fileSlug: "signalcrest-lab-note",
      workContext: "lab readiness review",
    },
  },
  {
    id: "site-move",
    label: "Site move coordination",
    ambiguousPrompt: "move status",
    groupA: {
      project: "Pinegate",
      oldRoom: "North",
      fileRoom: "East",
      currentRoom: "West",
      ownerInitial: "Imani",
      ownerCurrent: "Theo",
      fileSlug: "pinegate-venue-card",
      desk: "Pinegate Move Desk",
      archive: "Facilities Archive",
      workbench: "Move Workbench",
      notes: "Pinegate Move Notes",
      fileLabel: "Uploaded Venue Card",
      workContext: "office move readiness review",
    },
    groupB: {
      project: "Stonebridge",
      oldLocker: "Closet",
      currentLocker: "Storeroom",
      ownerInitial: "Grace",
      ownerCurrent: "Mateo",
      fileSlug: "stonebridge-floor-note",
      workContext: "facilities relocation check",
    },
  },
];

const HISTORY_PATH = path.resolve(
  process.cwd(),
  "../artifacts/e2e-mixed-scenario-last.json",
);

function hashNumber(value: string): number {
  return Number.parseInt(createHash("sha256").update(value).digest("hex").slice(0, 8), 16);
}

function lowerFirst(value: string): string {
  return value.length === 0 ? value : `${value[0].toLowerCase()}${value.slice(1)}`;
}

function isChinese(value: string): boolean {
  return /[\u3400-\u9fff]/.test(value);
}

function normalizePrompts(prompts: string[], runSuffix: string): string[] {
  return prompts.map((prompt) =>
    prompt
      .replaceAll(runSuffix, "<RUN>")
      .replace(/\s+/g, " ")
      .trim()
      .toLowerCase(),
  );
}

async function readHistory(): Promise<ScenarioHistory | null> {
  try {
    return JSON.parse(await readFile(HISTORY_PATH, "utf8")) as ScenarioHistory;
  } catch {
    return null;
  }
}

export async function createMixedScenarioRun(
  runId: string,
  runSuffix: string,
): Promise<MixedScenarioRun> {
  const previous = await readHistory();
  let profileIndex = hashNumber(runId) % PROFILES.length;
  if (PROFILES[profileIndex].id === previous?.profileId) {
    profileIndex = (profileIndex + 1) % PROFILES.length;
  }
  const profile = PROFILES[profileIndex];
  const records: PromptRecord[] = [];

  await mkdir(path.dirname(HISTORY_PATH), { recursive: true });
  await writeFile(
    HISTORY_PATH,
    `${JSON.stringify({ runId, profileId: profile.id, completed: false }, null, 2)}\n`,
  );

  return {
    runId,
    runSuffix,
    profile,
    renderPrompt(group, turn, base) {
      const trimmed = base.trim();
      let rendered: string;
      if (trimmed.toLowerCase() === profile.ambiguousPrompt.toLowerCase()) {
        rendered = trimmed;
      } else if (/^(?:not sure yet|still not sure|i can't tell yet)\.?$/i.test(trimmed)) {
        const uncertain = [
          "Still not sure.",
          "I can't tell yet.",
          "No preference yet.",
          "I'm undecided.",
          "I need one more clue.",
          "Not enough context yet.",
        ];
        rendered = uncertain[profileIndex];
      } else if (isChinese(trimmed)) {
        const wrappers = [
          `顺便问下：${trimmed}`,
          `我在整理记录：${trimmed}`,
          `${trimmed} 简短说就行。`,
          `开会前确认一下：${trimmed}`,
          `一个实际问题：${trimmed}`,
          `${trimmed} 我马上要发更新。`,
        ];
        rendered = wrappers[(turn + profileIndex) % wrappers.length];
      } else {
        const wrappers = [
          `Quick check: ${lowerFirst(trimmed)}`,
          `Before I send the update: ${lowerFirst(trimmed)}`,
          `${trimmed} I'm cleaning up the handoff note.`,
          `One practical question: ${lowerFirst(trimmed)}`,
          `For today's check-in, ${lowerFirst(trimmed)}`,
          `${trimmed} Keep it brief.`,
        ];
        rendered = wrappers[(turn + profileIndex) % wrappers.length];
      }
      records.push({ group, turn, base, rendered });
      return rendered;
    },
    async persistManifest(testInfo, groupAPrompts, groupBPrompts, completed) {
      const prompts = [...groupAPrompts, ...groupBPrompts];
      const normalizedPrompts = normalizePrompts(prompts, runSuffix);
      const fingerprint = createHash("sha256")
        .update(JSON.stringify(normalizedPrompts))
        .digest("hex");
      const previousPrompts = new Set(previous?.normalizedPrompts ?? []);
      const overlapCount = normalizedPrompts.filter((prompt) => previousPrompts.has(prompt)).length;
      const overlapRatio = normalizedPrompts.length === 0 ? 0 : overlapCount / normalizedPrompts.length;
      const manifest = {
        runId,
        runSuffix,
        profileId: profile.id,
        profileLabel: profile.label,
        completed,
        fingerprint,
        previousRunId: previous?.runId ?? null,
        previousProfileId: previous?.profileId ?? null,
        normalizedPromptOverlapRatio: overlapRatio,
        prompts: records,
      };
      await testInfo.attach("mixed-scenario-manifest", {
        body: JSON.stringify(manifest, null, 2),
        contentType: "application/json",
      });
      await writeFile(
        HISTORY_PATH,
        `${JSON.stringify(
          {
            runId,
            profileId: profile.id,
            fingerprint,
            normalizedPrompts,
            completed,
          } satisfies ScenarioHistory,
          null,
          2,
        )}\n`,
      );
      if (completed && previous?.fingerprint === fingerprint) {
        throw new Error("Mixed headed dialogue repeated the previous normalized scenario fingerprint.");
      }
      if (completed && previous?.normalizedPrompts?.length && overlapRatio > 0.4) {
        throw new Error(
          `Mixed headed dialogue reused too much prior wording: overlap=${overlapRatio.toFixed(3)}.`,
        );
      }
    },
  };
}
