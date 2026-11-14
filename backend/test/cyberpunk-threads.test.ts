import assert from "node:assert/strict";
import fs from "node:fs";
import { after, afterEach, before, beforeEach, describe, it } from "node:test";
import { fakeMod, fakeSteam, threadVersion, type FakeThread } from "./cyberpunk-fakemod.ts";
import { startServer, type TestServer } from "./server.ts";

const { steam, modDir } = fakeSteam();
const env = { MATE_STEAM_PATH: steam, MATE_CYBERPUNK_ACK_MS: "700" };
let server: TestServer;
let mod: ReturnType<typeof fakeMod> | undefined;
const call = (m: string, r: string, b?: unknown) => server.call(m, r, b);

const takemura = (): FakeThread => ({
  messages: [
    { hash: "-1234567890", text: "Salut V.", player: false },
    { hash: "987654321", text: "Tu es là ?", player: false },
  ],
  choices: [
    { hash: "-55", text: "Oui." },
    { hash: "66", text: "Non." },
  ],
});
const version = () => threadVersion(takemura());

before(async () => {
  server = await startServer({ os: "windows", env });
});
after(async () => {
  await server?.stop();
  fs.rmSync(steam, { recursive: true, force: true });
});
beforeEach(() => {
  fs.rmSync(modDir, { recursive: true, force: true });
});
afterEach(() => mod?.stop());

describe("GET /cyberpunk/threads/:hash", () => {
  it("rend le fil lu dans thread.json, sans cmd", async () => {
    mod = fakeMod(modDir, { threads: { "-42": takemura() } });
    const r = await call("GET", "/cyberpunk/threads/-42");
    assert.equal(r.status, 200);
    assert.equal(r.json.cmd, undefined);
    assert.equal(r.json.v, 1);
    assert.equal(r.json.hash, "-42");
    assert.equal(r.json.version, version());
    assert.equal(r.json.messages.length, 2);
    assert.deepEqual(r.json.choices[0], { hash: "-55", text: "Oui." });
    assert.equal(mod.executed[0].t, "thread");
    assert.equal(mod.executed[0].hash, "-42");
  });

  it("fil introuvable : 409 avec res", async () => {
    mod = fakeMod(modDir);
    const r = await call("GET", "/cyberpunk/threads/7");
    assert.equal(r.status, 409);
    assert.equal(r.json.res, "fil-introuvable");
    assert.ok(r.json.error);
  });

  it("thread.json d'un autre ordre : refusé, jamais rendu", async () => {
    mod = fakeMod(modDir, { threads: { "7": takemura() }, wrongCmd: true });
    const r = await call("GET", "/cyberpunk/threads/7");
    assert.equal(r.status, 409);
    assert.equal(r.json.messages, undefined);
  });

  it("mod muet : 504", async () => {
    mod = fakeMod(modDir, { silent: true });
    assert.equal((await call("GET", "/cyberpunk/threads/7")).status, 504);
  });

  it("hors partie : 409", async () => {
    mod = fakeMod(modDir, { threads: { "7": takemura() } });
    mod.setInGame(false);
    await new Promise((r) => setTimeout(r, 120));
    assert.equal((await call("GET", "/cyberpunk/threads/7")).status, 409);
  });

  it("hash invalide : 400", async () => {
    mod = fakeMod(modDir);
    assert.equal((await call("GET", "/cyberpunk/threads/abc")).status, 400);
    assert.equal((await call("GET", "/cyberpunk/threads/12345678901")).status, 400);
  });
});

describe("POST /cyberpunk/threads/:hash/reply", () => {
  it("envoie le choix et la version au mod", async () => {
    mod = fakeMod(modDir, { threads: { "7": takemura() } });
    const r = await call("POST", "/cyberpunk/threads/7/reply", { choice: "-55", version: version() });
    assert.equal(r.status, 200);
    assert.deepEqual(r.json, { ok: true });
    const { id, ...order } = mod.executed[0];
    assert.ok(id);
    assert.deepEqual(order, { t: "reply", hash: "7", choice: "-55", version: version() });
  });

  it("choix périmé (autre choix) : 409 avec res", async () => {
    mod = fakeMod(modDir, { threads: { "7": takemura() } });
    const r = await call("POST", "/cyberpunk/threads/7/reply", { choice: "99", version: version() });
    assert.equal(r.status, 409);
    assert.equal(r.json.res, "choix-perime");
  });

  it("erreur Lua du mod : phrase fixe, jamais la trace", async () => {
    mod = fakeMod(modDir, { threads: { "7": takemura() }, failWith: "logic.lua:450: attempt to index a nil value" });
    const r = await call("POST", "/cyberpunk/threads/7/reply", { choice: "-55", version: version() });
    assert.equal(r.status, 409);
    assert.equal(r.json.res, "erreur");
    assert.doesNotMatch(JSON.stringify(r.json), /logic\.lua/);
  });

  it("mod 1.0.0 encore chargé (ordre inconnu) : demande la mise à jour", async () => {
    mod = fakeMod(modDir, { threads: { "7": takemura() }, failWith: "inconnu" });
    const r = await call("GET", "/cyberpunk/threads/7");
    assert.equal(r.status, 409);
    assert.equal(r.json.res, "inconnu");
    assert.match(r.json.error, /mets-le à jour/);
  });

  it("version changée : 409 avec res", async () => {
    mod = fakeMod(modDir, { threads: { "7": takemura() } });
    const r = await call("POST", "/cyberpunk/threads/7/reply", { choice: "-55", version: "9:1" });
    assert.equal(r.status, 409);
    assert.equal(r.json.res, "choix-perime");
  });

  it("pas de double envoi : le second est périmé", async () => {
    mod = fakeMod(modDir, { threads: { "7": takemura() } });
    const body = { choice: "-55", version: version() };
    assert.equal((await call("POST", "/cyberpunk/threads/7/reply", body)).status, 200);
    assert.equal((await call("POST", "/cyberpunk/threads/7/reply", body)).json.res, "choix-perime");
  });

  it("fil introuvable : 409 avec res", async () => {
    mod = fakeMod(modDir);
    const r = await call("POST", "/cyberpunk/threads/7/reply", { choice: "1", version: "0:0" });
    assert.equal(r.status, 409);
    assert.equal(r.json.res, "fil-introuvable");
  });

  it("mod muet : 504", async () => {
    mod = fakeMod(modDir, { silent: true });
    assert.equal((await call("POST", "/cyberpunk/threads/7/reply", { choice: "1", version: "0:0" })).status, 504);
  });

  it("corps invalide : 400", async () => {
    mod = fakeMod(modDir);
    for (const body of [{}, { choice: "x", version: "0:0" }, { choice: "1", version: "12345:1" }])
      assert.equal((await call("POST", "/cyberpunk/threads/7/reply", body)).status, 400);
  });
});
