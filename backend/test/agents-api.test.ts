import assert from "node:assert/strict";
import fs from "node:fs";
import http from "node:http";
import os from "node:os";
import path from "node:path";
import { after, before, describe, it } from "node:test";
import { startServer, type TestServer } from "./server.ts";

/** Faux `claude` : `claude agents --json` rend le contenu du fichier. */
const bin = fs.mkdtempSync(path.join(os.tmpdir(), "companion-claude-"));
const sessionsFile = path.join(bin, "sessions.json");
const claude = path.join(bin, "claude");
fs.writeFileSync(claude, `#!/bin/sh\ncat "${sessionsFile}"\n`, { mode: 0o755 });
fs.writeFileSync(sessionsFile, "[]");
const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), "companion-test-"));
const SECRET = "secret-de-test";
fs.writeFileSync(path.join(dataDir, "agents-hook.json"), JSON.stringify({ secret: SECRET }));

let server: TestServer;
before(async () => {
  server = await startServer({ dataDir, env: { CLAUDE_BIN: claude, AGENTS_POLL_MS: "100", AGENTS_HOLD_S: "5" } });
});
after(async () => {
  await server?.stop();
  fs.rmSync(dataDir, { recursive: true, force: true });
  fs.rmSync(bin, { recursive: true, force: true });
});

const hook = (body: Record<string, unknown>, secret: string | null = SECRET, headers: Record<string, string> = {}) =>
  fetch(`${server.url}/agents/hook`, {
    method: "POST",
    headers: { "content-type": "application/json", ...(secret ? { "x-companion-hook": secret } : {}), ...headers },
    body: JSON.stringify({ session_id: "s1", cwd: "/work/projet", ...body }),
  });

/** La tablette fait son long-polling : rend les ordres reçus, puis les acquitte. */
async function nextOrders(): Promise<any[]> {
  const { json } = await server.call("POST", "/tablet/poll", { state: {} });
  for (const c of json.commands) await server.call("POST", "/tablet/result", { id: c.id, ok: true });
  return json.commands.map((c: any) => c.command);
}

const until = async (check: () => Promise<boolean>, ms = 5_000) => {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    if (await check()) return;
    await new Promise((r) => setTimeout(r, 50));
  }
  assert.fail("condition jamais vraie");
};

describe("agents Claude : hooks et état", () => {
  it("un hook demande le secret propre, pas le jeton d'appairage", async () => {
    assert.equal((await hook({ hook_event_name: "Stop" }, null)).status, 401);
    assert.equal((await hook({ hook_event_name: "Stop" }, "faux")).status, 401);
    const withToken = await fetch(`${server.url}/agents/hook`, {
      method: "POST",
      headers: { "content-type": "application/json", authorization: "Bearer jeton-de-test" },
      body: JSON.stringify({ hook_event_name: "Stop", session_id: "x" }),
    });
    assert.equal(withToken.status, 401, "le jeton d'appairage n'ouvre pas les hooks");
  });

  it("un hook sur un nom d'hôte étranger est refusé (DNS rebinding)", async () => {
    // fetch ne laisse pas changer `Host` : requête à la main. Hors du nom local, la route demande le jeton d'appairage.
    const status = await new Promise<number>((resolve, reject) => {
      const req = http.request(
        `${server.url}/agents/hook`,
        { method: "POST", headers: { "content-type": "application/json", "x-companion-hook": SECRET, host: "evil.example" } },
        (res) => {
          res.resume();
          resolve(res.statusCode ?? 0);
        },
      );
      req.on("error", reject);
      req.end(JSON.stringify({ hook_event_name: "Stop", session_id: "x" }));
    });
    assert.equal(status, 401);
  });

  it("les routes de la tablette demandent le jeton", async () => {
    assert.equal((await server.call("GET", "/agents", undefined, false)).status, 401);
    assert.equal((await server.call("POST", "/agents/answer", { id: "x", decision: "allow" }, false)).status, 401);
  });

  it("travaille, fini : GET /agents rend l'état et le résumé", async () => {
    assert.equal((await hook({ hook_event_name: "UserPromptSubmit", session_title: "refacto" })).status, 200);
    let { json } = await server.call("GET", "/agents");
    assert.equal(json.sessions[0].state, "working");
    assert.equal(json.counts.working, 1);
    assert.equal(json.hooks, true);
    await hook({ hook_event_name: "Stop", last_assistant_message: "Fini.\nTrois fichiers changés." });
    ({ json } = await server.call("GET", "/agents"));
    assert.equal(json.sessions[0].state, "done");
    assert.deepEqual(json.sessions[0].lines, ["Fini.", "Trois fichiers changés."]);
    assert.equal(typeof json.sessions[0].workedMs, "number");
    await hook({ hook_event_name: "SessionEnd" });
    assert.equal((await server.call("GET", "/agents")).json.total, 0);
  });

  it("/deck/features annonce agents quand Claude Code est là", async () => {
    assert.equal((await server.call("GET", "/deck/features")).json.agents, true);
  });

  it("le filet lit `claude agents --json` quand la tablette est connectée", async () => {
    fs.writeFileSync(
      sessionsFile,
      JSON.stringify([
        { sessionId: "z", cwd: "/work/autre", name: "autre", kind: "interactive", status: "waiting", waitingFor: "permission prompt" },
      ]),
    );
    // La tablette vient de se connecter (un poll commencé, puis coupé) : connectée pendant 35 s.
    const ac = new AbortController();
    void fetch(`${server.url}/tablet/poll`, {
      method: "POST",
      headers: { authorization: "Bearer jeton-de-test", "content-type": "application/json" },
      body: JSON.stringify({ state: {} }),
      signal: ac.signal,
    }).catch(() => {});
    setTimeout(() => ac.abort(), 300);
    await until(async () => (await server.call("GET", "/agents")).json.sessions.some((s: any) => s.id === "z" && s.state === "waiting"));
    fs.writeFileSync(sessionsFile, "[]");
  });
});

describe("agents Claude : répondre depuis la tablette", () => {
  it("permission : ordre à la tablette, requête tenue, autorisation renvoyée au hook", async () => {
    const pending = hook({
      hook_event_name: "PermissionRequest",
      tool_name: "Bash",
      tool_input: { command: "npm test", description: "x" },
    });
    const orders = await nextOrders();
    const alert = orders.find((o) => o.type === "agent" && o.kind === "waiting" && o.askId);
    assert.ok(alert, JSON.stringify(orders));
    const ask = (await server.call("GET", `/agents/ask?id=${alert.askId}`)).json;
    assert.equal(ask.text, "npm test");
    assert.equal(ask.kind, "permission");
    assert.equal(ask.risky, false);
    assert.equal((await server.call("POST", "/agents/answer", { id: alert.askId, decision: "allow" })).status, 200);
    const res = await pending;
    assert.deepEqual(await res.json(), { hookSpecificOutput: { hookEventName: "PermissionRequest", decision: { behavior: "allow" } } });
    // Déjà traitée.
    assert.equal((await server.call("POST", "/agents/answer", { id: alert.askId, decision: "allow" })).status, 404);
    await hook({ hook_event_name: "SessionEnd" });
  });

  it("commande à risque : refus d'autoriser sans appui maintenu", async () => {
    const pending = hook({ hook_event_name: "PermissionRequest", tool_name: "Bash", tool_input: { command: "rm -rf build" } });
    const orders = await nextOrders();
    const id = orders.find((o) => o.askId).askId;
    assert.equal((await server.call("POST", "/agents/answer", { id, decision: "allow" })).status, 403);
    assert.equal((await server.call("POST", "/agents/answer", { id, decision: "allow", held: true })).status, 200);
    assert.equal(((await (await pending).json()) as any).hookSpecificOutput.decision.behavior, "allow");
    await hook({ hook_event_name: "SessionEnd" });
  });

  it("question à choix : réponse par index, renvoyée dans updatedInput", async () => {
    const questions = [{ question: "Couleur ?", header: "C", multiSelect: false, options: [{ label: "Rouge" }, { label: "Bleu" }] }];
    const pending = hook({ hook_event_name: "PreToolUse", tool_name: "AskUserQuestion", tool_input: { questions } });
    const orders = await nextOrders();
    const id = orders.find((o) => o.askId).askId;
    assert.equal((await server.call("POST", "/agents/answer", { id, answers: [{ choices: [7] }] })).status, 400);
    assert.equal((await server.call("POST", "/agents/answer", { id, answers: [{ choices: [1] }] })).status, 200);
    assert.deepEqual(await (await pending).json(), {
      hookSpecificOutput: {
        hookEventName: "PreToolUse",
        permissionDecision: "allow",
        updatedInput: { questions, answers: { "Couleur ?": "Bleu" } },
      },
    });
    await hook({ hook_event_name: "SessionEnd" });
  });

  it("fenêtre Claude devant : {} tout de suite, la session attend dans le terminal", async () => {
    server.setOs({ front: { name: "Terminal", bundleId: "com.apple.Terminal" } });
    const res = await hook({ hook_event_name: "PermissionRequest", tool_name: "Bash", tool_input: { command: "npm test" } });
    assert.deepEqual(await res.json(), {});
    assert.equal((await server.call("GET", "/agents")).json.sessions[0].state, "waiting");
    server.setOs({});
    await hook({ hook_event_name: "SessionEnd" });
  });

  it("sans réponse : {} après AGENTS_HOLD_S", async () => {
    const started = Date.now();
    const pending = hook({ hook_event_name: "PermissionRequest", tool_name: "Bash", tool_input: { command: "npm test" } });
    await nextOrders();
    assert.deepEqual(await (await pending).json(), {});
    assert.ok(Date.now() - started >= 4_500, "attend le délai");
    await hook({ hook_event_name: "SessionEnd" });
  });
});

describe("agents Claude : message, session devant, reprise", () => {
  it("session inconnue : 404 ; demande mal formée : 400", async () => {
    assert.equal((await server.call("POST", "/agents/send", { session: "inconnue", text: "continue" })).status, 404);
    assert.equal((await server.call("POST", "/agents/focus", { session: "inconnue" })).status, 404);
    assert.equal((await server.call("POST", "/agents/resume", { session: "inconnue", armed: true })).status, 409);
    assert.equal((await server.call("POST", "/agents/send", { session: "s1", text: "" })).status, 400);
    assert.equal((await server.call("POST", "/agents/send", { text: "continue" })).status, 400);
  });

  it("une session sans terminal Orca : pas de message, c'est dit", async () => {
    await hook({ hook_event_name: "Stop", last_assistant_message: "ok" });
    const send = await server.call("POST", "/agents/send", { session: "s1", text: "continue" });
    assert.equal(send.status, 409);
    assert.match(send.json.error, /pas dans un terminal Orca/);
    assert.equal((await server.call("GET", "/agents")).json.sessions[0].canSend, false);
    await hook({ hook_event_name: "SessionEnd" });
  });
});

describe("agents Claude : ailleurs que sur Mac", () => {
  it("Windows : 501 et agents absent de /deck/features", async () => {
    const win = await startServer({ os: "windows", env: { CLAUDE_BIN: claude } });
    try {
      assert.equal((await win.call("GET", "/agents")).status, 501);
      assert.equal((await win.call("GET", "/deck/features")).json.agents, false);
    } finally {
      await win.stop();
    }
  });
});
