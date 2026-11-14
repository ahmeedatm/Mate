import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { after, afterEach, before, beforeEach, describe, it } from "node:test";
import { fakeMod, fakeSteam } from "./cyberpunk-fakemod.ts";
import { startServer, type TestServer } from "./server.ts";

const { steam, modDir } = fakeSteam();
const env = { MATE_STEAM_PATH: steam, MATE_CYBERPUNK_ACK_MS: "700" };
let server: TestServer;
let mod: ReturnType<typeof fakeMod> | undefined;
const call = (m: string, r: string, b?: unknown) => server.call(m, r, b);
const ITEM = "Items.AdvancedSandevistanC1MK1#123456";

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

describe("GET /cyberpunk/cyberware", () => {
  it("vide tant que le mod n'a rien écrit (mod ancien)", async () => {
    mod = fakeMod(modDir);
    const r = await call("GET", "/cyberpunk/cyberware");
    assert.equal(r.status, 200);
    assert.deepEqual(r.json, { v: 1, zones: {}, owned: [] });
  });

  it("rend cyberware.json du mod", async () => {
    mod = fakeMod(modDir);
    const body = { v: 1, body: "woman", capacity: { used: 40, max: 120 }, zones: { EyesCW: [{ locked: false, item: null }] }, owned: [] };
    fs.writeFileSync(path.join(modDir, "cyberware.json"), JSON.stringify(body));
    const r = await call("GET", "/cyberpunk/cyberware");
    assert.deepEqual(r.json, body);
  });
});

describe("POST /cyberpunk/cyberware/equip", () => {
  it("envoie l'ordre equip au mod et rend ok", async () => {
    mod = fakeMod(modDir);
    const r = await call("POST", "/cyberpunk/cyberware/equip", { area: "NervousSystemCW", slot: 1, item: ITEM });
    assert.equal(r.status, 200);
    assert.equal(r.json.ok, true);
    assert.deepEqual(
      mod.executed.map(({ t, area, slot, item }) => ({ t, area, slot, item })),
      [{ t: "equip", area: "NervousSystemCW", slot: 1, item: ITEM }],
    );
  });

  it("refus du jeu : 409 avec res connu et une phrase", async () => {
    mod = fakeMod(modDir, { equip: () => ({ ok: false, res: "capacite" }) });
    const r = await call("POST", "/cyberpunk/cyberware/equip", { area: "EyesCW", slot: 0, item: ITEM });
    assert.equal(r.status, 409);
    assert.equal(r.json.res, "capacite");
    assert.match(r.json.error, /capacité/);
  });

  it("erreur Lua brute : 409 res erreur, jamais la trace", async () => {
    mod = fakeMod(modDir, { equip: () => ({ ok: false, res: "logic.lua:42: attempt to index nil" }) });
    const r = await call("POST", "/cyberpunk/cyberware/equip", { area: "EyesCW", slot: 0, item: ITEM });
    assert.equal(r.status, 409);
    assert.equal(r.json.res, "erreur");
    assert.doesNotMatch(r.json.error, /logic\.lua|nil/);
  });

  it("mod muet : 504", async () => {
    mod = fakeMod(modDir, { silent: true });
    const r = await call("POST", "/cyberpunk/cyberware/equip", { area: "EyesCW", slot: 0, item: ITEM });
    assert.equal(r.status, 504);
  });

  it("entrées hors schéma : 400, rien envoyé", async () => {
    mod = fakeMod(modDir);
    for (const bad of [
      { area: "WeaponsCW", slot: 0, item: ITEM },
      { area: "EyesCW", slot: -1, item: ITEM },
      { area: "EyesCW", slot: 10, item: ITEM },
      { area: "EyesCW", slot: 0, item: "Items.X" },
      { area: "EyesCW", slot: 0, item: "../x#1" },
    ])
      assert.equal((await call("POST", "/cyberpunk/cyberware/equip", bad)).status, 400, JSON.stringify(bad));
    assert.equal(mod.executed.length, 0);
  });
});
