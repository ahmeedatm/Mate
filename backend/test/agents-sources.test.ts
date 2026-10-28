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
const { agentsAvailable, claudeBin, frontIsClaude, listClaude, listOrca } = await import("../src/agents-sources.ts");
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

  it("orca : branche, agents Claude, terminaux par volet ; en échec : null puis pas d'insistance", async () => {
    const ps = {
      result: {
        worktrees: [
          {
            path: "/w/p",
            branch: "refs/heads/feat/x",
            agents: [
              { agentType: "claude", paneKey: "t:l", toolName: "Edit", lastAssistantMessage: "ok", structuredHostOwned: null },
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
      { path: "/w/p", branch: "feat/x", agents: [{ paneKey: "t:l", toolName: "Edit", lastMessage: "ok", structured: false }] },
    ]);
    assert.equal(info?.handles.get("t:l"), "term_1");
    process.env.ORCA_BIN = script("orca-ko", "exit 1");
    assert.equal(await listOrca(), null);
    process.env.ORCA_BIN = script("orca-ok2", `echo '${JSON.stringify(ps)}'`);
    assert.equal(await listOrca(), null, "Orca vient d'échouer : pas de nouvel essai avant 30 s");
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
});
