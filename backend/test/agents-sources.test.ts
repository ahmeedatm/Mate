import "./fake-os-hook.ts";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { after, describe, it } from "node:test";
import { state } from "./fake-os.ts";

const dir = fs.mkdtempSync(path.join(os.tmpdir(), "companion-bins-"));
process.env.DATA_DIR = dir;
const script = (name: string, body: string) => {
  const file = path.join(dir, name);
  fs.writeFileSync(file, `#!/bin/sh\n${body}\n`, { mode: 0o755 });
  return file;
};
const {
  agentsAvailable,
  claudeBin,
  claudeConfigDirs,
  findBinary,
  frontIsClaude,
  listClaude,
  listOrca,
  projectSlug,
  readTranscriptTail,
  splitDirs,
  transcriptInfo,
} = await import("../src/agents-sources.ts");
const { hookSecret, hookSecretOk } = await import("../src/agents-secret.ts");

after(() => fs.rmSync(dir, { recursive: true, force: true }));

describe("sources des agents", () => {
  it("claude agents --json : liste des sessions ; absent ou en échec : null", async () => {
    process.env.CLAUDE_BIN = script("claude-ok", `echo '[{"sessionId":"a","cwd":"/x","status":"busy"}]'`);
    assert.equal(agentsAvailable(), true);
    assert.deepEqual(await listClaude(), [{ sessionId: "a", cwd: "/x", status: "busy" }]);
    process.env.CLAUDE_BIN = script("claude-ko", "exit 3");
    assert.equal(await listClaude(), null);
    process.env.CLAUDE_BIN = script("claude-texte", "echo pas du json");
    assert.equal(await listClaude(), null);
    process.env.CLAUDE_BIN = "/inexistant";
    assert.equal(claudeBin(), null);
    assert.equal(agentsAvailable(), false);
    assert.equal(await listClaude(), null);
  });

  it("lit chaque dossier de config (défaut, profils Orca, AGENTS_CLAUDE_CONFIG_DIRS) et fusionne sans doublon", async () => {
    const a = fs.mkdtempSync(path.join(dir, "cfg-a-"));
    const b = fs.mkdtempSync(path.join(dir, "cfg-b-"));
    process.env.CLAUDE_CONFIG_DIR = a;
    process.env.AGENTS_CLAUDE_CONFIG_DIRS = `${b}:${a}`;
    assert.deepEqual(
      claudeConfigDirs().filter((d) => d === a || d === b),
      [a, b],
      "chaque dossier une fois",
    );
    // Le faux claude répond selon le dossier reçu dans CLAUDE_CONFIG_DIR.
    process.env.CLAUDE_BIN = script(
      "claude-dirs",
      `case "$CLAUDE_CONFIG_DIR" in "${a}") echo '[{"sessionId":"1","status":"idle"},{"sessionId":"2","status":"busy"}]';; "${b}") echo '[{"sessionId":"2","status":"busy"},{"sessionId":"3","status":"idle"}]';; *) echo '[]';; esac`,
    );
    assert.deepEqual((await listClaude())?.map((s) => s.sessionId).sort(), ["1", "2", "3"]);
    delete process.env.CLAUDE_CONFIG_DIR;
    delete process.env.AGENTS_CLAUDE_CONFIG_DIRS;
  });

  it("orca : branche, agents Claude, terminaux par volet ; en échec : null puis pas d'insistance", async () => {
    const ps = {
      result: {
        worktrees: [
          {
            path: "/w/p",
            branch: "refs/heads/feat/x",
            repo: "Mate",
            displayName: "Micro des sessions",
            lastActivityAt: 1_700_000_000_000,
            linkedGitLabMR: 394,
            linkedItems: [{ provider: "jira", jiraIdentifier: "TECH-1" }],
            agents: [
              {
                agentType: "claude",
                state: "done",
                workingMode: "monitoring",
                paneKey: "t:l",
                toolName: "Edit",
                lastAssistantMessage: "ok",
                structuredHostOwned: null,
              },
              { agentType: "codex", paneKey: "autre" },
            ],
          },
        ],
      },
    };
    const terminals = { result: { terminals: [{ handle: "term_1", tabId: "t", leafId: "l" }] } };
    process.env.ORCA_BIN = script(
      "orca-ok",
      `case "$1" in worktree) echo '${JSON.stringify(ps)}';; terminal) echo '${JSON.stringify(terminals)}';; esac`,
    );
    const info = await listOrca();
    assert.deepEqual(info?.worktrees, [
      {
        path: "/w/p",
        branch: "feat/x",
        ticket: "TECH-1",
        mr: 394,
        repo: "Mate",
        name: "Micro des sessions",
        activityAt: 1_700_000_000_000,
        agents: [{ paneKey: "t:l", state: "done", monitoring: true, toolName: "Edit", lastMessage: "ok", structured: false }],
      },
    ]);
    assert.equal(info?.handles.get("t:l"), "term_1");
    process.env.ORCA_BIN = script("orca-ko", "exit 1");
    assert.equal(await listOrca(), null);
    process.env.ORCA_BIN = script("orca-ok2", `echo '${JSON.stringify(ps)}'`);
    assert.equal(await listOrca(), null, "Orca vient d'échouer : pas de nouvel essai avant 30 s");
  });

  it("journal de la session : dernier titre automatique et dernière MR, lignes coupées ignorées", async () => {
    const lines = [
      '"aiTitle":"début coupé"}',
      JSON.stringify({ type: "ai-title", aiTitle: "Ancien titre", sessionId: "x" }),
      JSON.stringify({ type: "pr-link", prNumber: 61, prUrl: "u" }),
      JSON.stringify({ type: "user", message: "« ai-title » dans un message" }),
      JSON.stringify({ type: "ai-title", aiTitle: "Agents Claude : première version V1", sessionId: "x" }),
      JSON.stringify({ type: "pr-link", prNumber: 74, prUrl: "u" }),
    ];
    assert.deepEqual(readTranscriptTail(lines.join("\n")), { title: "Agents Claude : première version V1", mr: 74 });
    assert.deepEqual(readTranscriptTail("rien"), { title: null, mr: null });
    assert.equal(projectSlug("/Users/a/orca/workspaces/x.y"), "-Users-a-orca-workspaces-x-y");

    const id = "0c83ea11-b00b-4d90-a5ad-a1c9b717f12c";
    const cfg = fs.mkdtempSync(path.join(dir, "cfg-t-"));
    process.env.CLAUDE_CONFIG_DIR = cfg;
    const file = path.join(cfg, "projects", projectSlug("/w/p"), `${id}.jsonl`);
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, lines.join("\n") + "\n");
    assert.deepEqual(await transcriptInfo(id, "/w/p", null), { title: "Agents Claude : première version V1", mr: 74 });
    // La suite du journal sans titre : on garde le dernier connu.
    fs.appendFileSync(file, JSON.stringify({ type: "assistant", text: "x".repeat(300 * 1024) }) + "\n");
    assert.deepEqual(await transcriptInfo(id, "/w/p", null), { title: "Agents Claude : première version V1", mr: 74 });
    assert.equal(await transcriptInfo("pas-un-id", "/w/p", null), null);
    delete process.env.CLAUDE_CONFIG_DIR;
  });

  it("devant Claude : Orca et les terminaux, pas un navigateur", async () => {
    state.front = { name: "Orca", bundleId: "com.stablyai.orca" };
    assert.equal(await frontIsClaude(), true);
    state.front = { name: "Terminal", bundleId: "com.apple.Terminal" };
    assert.equal(await frontIsClaude(), true);
    state.front = { name: "Safari", bundleId: "com.apple.Safari" };
    assert.equal(await frontIsClaude(), false);
  });

  it("secret des hooks : créé une fois, relu, comparé sans fuite", () => {
    const secret = hookSecret();
    assert.match(secret, /^[0-9a-f]{48}$/);
    assert.equal(JSON.parse(fs.readFileSync(path.join(dir, "agents-hook.json"), "utf8")).secret, secret);
    assert.equal(hookSecretOk(secret), true);
    assert.equal(hookSecretOk(secret + "x"), false);
    assert.equal(hookSecretOk(undefined), false);
    assert.equal(fs.statSync(path.join(dir, "agents-hook.json")).mode & 0o077, 0, "lisible par l'utilisateur seulement");
  });

  it("Windows : claude.exe avant claude.cmd, PATH séparé par « ; », fichier sans droit d'exécution trouvé", () => {
    const a = fs.mkdtempSync(path.join(dir, "path-a-"));
    const b = fs.mkdtempSync(path.join(dir, "path-b-"));
    const extra = fs.mkdtempSync(path.join(dir, "extra-"));
    fs.writeFileSync(path.join(a, "claude.cmd"), "@echo off\n", { mode: 0o644 });
    const opts = { windows: true, pathVar: `${a};${b}`, delimiter: ";" };
    const forced = process.env.CLAUDE_BIN;
    delete process.env.CLAUDE_BIN;
    try {
      assert.equal(findBinary(["claude.exe", "claude.cmd"], "CLAUDE_BIN", [extra], opts), path.join(a, "claude.cmd"));
      fs.writeFileSync(path.join(extra, "claude.exe"), "", { mode: 0o644 });
      assert.equal(findBinary(["claude.exe", "claude.cmd"], "CLAUDE_BIN", [extra], opts), path.join(extra, "claude.exe"), ".exe d'abord");
      assert.equal(findBinary(["absent.exe"], "CLAUDE_BIN", [extra], opts), null);
    } finally {
      if (forced !== undefined) process.env.CLAUDE_BIN = forced;
    }
  });

  it("AGENTS_CLAUDE_CONFIG_DIRS : « ; » sous Windows (les chemins ont un « : »), « : » ailleurs", () => {
    assert.deepEqual(splitDirs("C:\\Users\\x\\.claude-a; D:\\profils\\b ;", ";"), ["C:\\Users\\x\\.claude-a", "D:\\profils\\b"]);
    assert.deepEqual(splitDirs("/a:/b", ":"), ["/a", "/b"]);
    assert.deepEqual(splitDirs(undefined), []);
  });
});
