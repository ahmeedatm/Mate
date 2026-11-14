import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { after, afterEach, before, beforeEach, describe, it } from "node:test";
import { fakeMod, fakeSteam, until } from "./cyberpunk-fakemod.ts";
import { startServer, type TestServer } from "./server.ts";

const { steam, modDir } = fakeSteam();
const env = { MATE_STEAM_PATH: steam, MATE_CYBERPUNK_ACK_MS: "700" };
let server: TestServer;
let mod: ReturnType<typeof fakeMod> | undefined;
const call = (m: string, r: string, b?: unknown) => server.call(m, r, b);
const pinsFile = () => path.join(server.dataDir, "cyberpunk-pins.json");
/** La tablette sonde la présence : c'est ce qui déclenche le rejeu. */
const poll = () => call("GET", "/cyberpunk/presence");
const pids = () =>
  mod!
    .pins()
    .map((p) => p.pid)
    .sort();

before(async () => {
  server = await startServer({ os: "windows", env });
});
after(async () => {
  await server?.stop();
  fs.rmSync(steam, { recursive: true, force: true });
});
beforeEach(() => {
  fs.rmSync(modDir, { recursive: true, force: true });
  fs.rmSync(pinsFile(), { force: true });
});
afterEach(() => mod?.stop());

describe("/cyberpunk/pins", () => {
  it("hors partie : le repère est gardé dans .data, sans ordre", async () => {
    const r = await call("POST", "/cyberpunk/pins", { x: 120.5, y: -300, name: "Planque" });
    assert.equal(r.status, 200);
    const list = (await call("GET", "/cyberpunk/pins")).json;
    assert.equal(list.pins.length, 1);
    assert.deepEqual({ ...list.pins[0], at: 0 }, { id: r.json.id, x: 120.5, y: -300, z: 0, name: "Planque", at: 0 });
    assert.ok(list.pins[0].at > 0);
    assert.equal(JSON.parse(fs.readFileSync(pinsFile(), "utf8")).pins.length, 1);
  });

  it("en partie : envoie pin{pid,x,y,z} au mod ; DELETE envoie unpin", async () => {
    mod = fakeMod(modDir);
    await until(() => mod!.executed.length === 0 && fs.existsSync(path.join(modDir, "state.json")));
    const { id } = (await call("POST", "/cyberpunk/pins", { x: 10, y: 20, z: 5 })).json;
    assert.deepEqual(mod.pins(), [{ pid: id, x: 10, y: 20, z: 5 }]);
    assert.equal((await call("DELETE", `/cyberpunk/pins/${id}`)).status, 200);
    await until(() => mod!.pins().length === 0);
    assert.equal((await call("GET", "/cyberpunk/pins")).json.pins.length, 0);
    assert.equal((await call("DELETE", `/cyberpunk/pins/${id}`)).status, 404);
  });

  it("track : ordre track{kind:pin,id}, repère suivi gardé", async () => {
    mod = fakeMod(modDir);
    const { id } = (await call("POST", "/cyberpunk/pins", { x: 1, y: 2 })).json;
    const r = await call("POST", `/cyberpunk/pins/${id}/track`, {});
    assert.equal(r.status, 200);
    const order = mod.executed.at(-1);
    assert.equal(order.t, "track");
    assert.equal(order.kind, "pin");
    assert.equal(String(order.ref), String(id));
    assert.equal((await call("GET", "/cyberpunk/pins")).json.tracked, id);
    assert.equal((await call("POST", "/cyberpunk/pins/999/track", {})).status, 404);
  });

  it("un id supprimé n'est jamais réutilisé", async () => {
    const a = (await call("POST", "/cyberpunk/pins", { x: 1, y: 1 })).json.id;
    const b = (await call("POST", "/cyberpunk/pins", { x: 2, y: 2 })).json.id;
    await call("DELETE", `/cyberpunk/pins/${b}`);
    const c = (await call("POST", "/cyberpunk/pins", { x: 3, y: 3 })).json.id;
    assert.deepEqual([a, b, c], [1, 2, 3]);
  });

  it("validation : nombres finis, |x| et |y| ≤ 8000, nom ≤ 40", async () => {
    for (const body of [
      {},
      { x: 1 },
      { x: "a", y: 1 },
      { x: 8001, y: 0 },
      { x: 0, y: -8001 },
      { x: 0, y: 0, z: 2001 },
      { x: 0, y: 0, z: -2001 },
      { x: 0, y: 0, name: "x".repeat(41) },
      { x: 0, y: 0, z: "h" },
    ])
      assert.equal((await call("POST", "/cyberpunk/pins", body)).status, 400, JSON.stringify(body));
    assert.equal((await call("GET", "/cyberpunk/pins")).json.pins.length, 0);
  });

  it("nouvelle partie ou mod rechargé : rejeu des seuls repères absents, jamais de doublon", async () => {
    const a = (await call("POST", "/cyberpunk/pins", { x: 1, y: 1 })).json.id;
    const b = (await call("POST", "/cyberpunk/pins", { x: 2, y: 2 })).json.id;
    mod = fakeMod(modDir);
    await poll();
    await until(async () => (await poll(), pids().length === 2));
    assert.deepEqual(pids(), [a, b].sort());
    // Sondages répétés : rien de plus n'est envoyé.
    for (let i = 0; i < 5; i++) await poll();
    await new Promise((r) => setTimeout(r, 200));
    assert.equal(mod.executed.filter((c) => c.t === "pin").length, 2);

    // Nouvelle partie chargée : la session change et le jeu n'a plus rien.
    mod.newSession();
    await until(async () => (await poll(), pids().length === 2));
    assert.equal(mod.executed.filter((c) => c.t === "pin").length, 4);

    // Le jeu a déjà b : seul l'absent (a) est renvoyé.
    mod.newSession();
    mod.pins().push({ pid: b, x: 2, y: 2, z: 0 });
    await until(async () => (await poll(), pids().length === 2));
    assert.equal(mod.executed.filter((c) => c.t === "pin").length, 5);

    // Mod rechargé (compteur reparti de zéro, même session) : tout est renvoyé une fois.
    mod.reload();
    await until(async () => (await poll(), pids().length === 2));
    assert.equal(mod.executed.filter((c) => c.t === "pin").length, 7);
  });
});
