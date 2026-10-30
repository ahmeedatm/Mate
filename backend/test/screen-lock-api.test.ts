import assert from "node:assert/strict";
import { after, before, describe, it } from "node:test";
import { startServer, type TestServer } from "./server.ts";

let server: TestServer;
before(async () => {
  server = await startServer({ os: "mac", env: { SCREEN_LOCK_POLL_MS: "20" } });
});
after(async () => {
  await server?.stop();
});

/** Long-polling de la tablette : rend les ordres reçus, puis les acquitte. */
async function nextOrders(): Promise<any[]> {
  const { json } = await server.call("POST", "/tablet/poll", { state: {} });
  for (const c of json.commands) await server.call("POST", "/tablet/result", { id: c.id, ok: true });
  return json.commands.map((c: any) => c.command);
}

const until = async (check: () => Promise<boolean>, ms = 5_000) => {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    if (await check()) return;
    await new Promise((r) => setTimeout(r, 20));
  }
  assert.fail("condition jamais vraie");
};

describe("ordinateur verrouillé : tablette inutilisable", () => {
  it("l'API répond 423, sauf l'identité, l'appairage et le canal /tablet ; le déverrouillage rouvre tout", async () => {
    assert.equal((await server.call("GET", "/system/dnd")).status, 200);
    server.setOs({ locked: true });
    await until(async () => (await server.call("GET", "/system/dnd")).status === 423);
    const res = await server.call("GET", "/deck");
    assert.equal(res.status, 423);
    assert.deepEqual(res.json, { error: "Ordinateur verrouillé : déverrouille-le pour continuer", locked: true });
    assert.equal((await server.call("GET", "/health", undefined, false)).status, 200);
    assert.equal((await server.call("GET", "/deck", undefined, false)).status, 401, "sans jeton : toujours 401");

    // La tablette arrive : elle apprend le verrouillage par son canal.
    assert.deepEqual(await nextOrders(), [{ type: "lock", locked: true }]);
    // Rien d'autre ne lui parvient tant que l'ordinateur est verrouillé.
    const message = await server.call("POST", "/tablet/command", { type: "message", text: "secret" });
    assert.equal(message.status, 423, "l'API (ici l'outil MCP) est fermée");

    server.setOs({ locked: false });
    assert.deepEqual(await nextOrders(), [{ type: "lock", locked: false }]);
    assert.equal((await server.call("GET", "/system/dnd")).status, 200);
  });

  it("tablette revenue encore bloquée alors que l'ordinateur est ouvert : débloquée", async () => {
    const { json } = await server.call("POST", "/tablet/poll", { state: { locked: true } });
    assert.deepEqual(
      json.commands.map((c: any) => c.command),
      [{ type: "lock", locked: false }],
    );
    await server.call("POST", "/tablet/result", { id: json.commands[0].id, ok: true, state: { locked: false } });
  });
});
