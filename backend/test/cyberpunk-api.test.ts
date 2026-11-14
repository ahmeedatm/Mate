import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { after, afterEach, before, beforeEach, describe, it } from "node:test";
import { isFresh } from "../src/cyberpunk.ts";
import { startServer, type TestServer } from "./server.ts";

const ASSETS = path.resolve(import.meta.dirname, "..", "assets", "cyberpunk-mod");
const asset = (f: string) => fs.readFileSync(path.join(ASSETS, f), "utf8");

let steam: string;
let modDir: string;
let server: TestServer;
const call = (m: string, r: string, b?: unknown) => server.call(m, r, b);
const mod = (f: string) => path.join(modDir, f);
const setState = (state: object | string) => fs.writeFileSync(mod("state.json"), typeof state === "string" ? state : JSON.stringify(state));
const fresh = (extra: object = {}) => ({
  v: 1,
  seq: 1,
  at: Math.floor(Date.now() / 1000),
  inGame: true,
  session: 3,
  mod: "1.0.0",
  ...extra,
});

before(async () => {
  steam = fs.mkdtempSync(path.join(os.tmpdir(), "mate-cp-"));
  const game = path.join(steam, "steamapps", "common", "Cyberpunk 2077");
  const plugins = path.join(game, "bin", "x64", "plugins");
  fs.mkdirSync(path.join(plugins, "cyber_engine_tweaks", "mods"), { recursive: true });
  fs.writeFileSync(path.join(plugins, "cyber_engine_tweaks.asi"), "");
  fs.writeFileSync(path.join(steam, "steamapps", "appmanifest_1091500.acf"), '"AppState"\n{\n\t"installdir"\t\t"Cyberpunk 2077"\n}\n');
  modDir = path.join(plugins, "cyber_engine_tweaks", "mods", "mate");
  server = await startServer({ os: "windows", env: { MATE_STEAM_PATH: steam } });
});
after(async () => {
  await server?.stop();
  fs.rmSync(steam, { recursive: true, force: true });
});
beforeEach(() => {
  fs.rmSync(modDir, { recursive: true, force: true });
  fs.mkdirSync(modDir, { recursive: true });
  server.setOs({ running: [] });
});

describe("isFresh", () => {
  it("frais : écrit il y a moins de 10 s et seq vu récemment ; périmé sinon", () => {
    const now = 1_000_000_000_000;
    assert.equal(isFresh(now / 1000 - 3, now - 2000, now), true);
    assert.equal(isFresh(now / 1000 - 11, now - 2000, now), false);
    assert.equal(isFresh(now / 1000, now - 11_000, now), false);
    assert.equal(isFresh(undefined, now, now), false);
  });
});

describe("/cyberpunk/presence et /cyberpunk", () => {
  it("jeu trouvé, mod absent, jeu fermé : off", async () => {
    fs.rmSync(modDir, { recursive: true, force: true });
    const p = (await call("GET", "/cyberpunk/presence")).json;
    assert.deepEqual(p, { available: true, gameFound: true, modInstalled: false, running: false, inGame: false, session: 0 });
    const o = (await call("GET", "/cyberpunk")).json;
    assert.equal(o.status, "off");
    assert.equal(o.state, null);
    assert.deepEqual(o.install, { gameFound: true, cet: true, modInstalled: false, modVersion: null, current: false, pending: false });
  });

  it("state.json frais : ingame, avec l'état et la session", async () => {
    setState(fresh({ player: { level: 30 } }));
    const p = (await call("GET", "/cyberpunk/presence")).json;
    assert.equal(p.inGame, true);
    assert.equal(p.running, true);
    assert.equal(p.session, 3);
    const o = (await call("GET", "/cyberpunk")).json;
    assert.equal(o.status, "ingame");
    assert.equal(o.state.player.level, 30);
  });

  it("state.json périmé : menu si le processus tourne, sinon off", async () => {
    setState(fresh({ at: Math.floor(Date.now() / 1000) - 60 }));
    assert.equal((await call("GET", "/cyberpunk")).json.status, "off");
    server.setOs({ running: ["Cyberpunk2077"] });
    const o = (await call("GET", "/cyberpunk")).json;
    assert.equal(o.status, "menu");
    assert.equal((await call("GET", "/cyberpunk/presence")).json.inGame, false);
  });

  it("fresh mais pas en partie (menu principal) : menu", async () => {
    setState(fresh({ inGame: false }));
    assert.equal((await call("GET", "/cyberpunk")).json.status, "menu");
  });

  it("JSON coupé ou illisible : le dernier état valide est gardé", async () => {
    setState(fresh({ seq: 5, player: { level: 12 } }));
    assert.equal((await call("GET", "/cyberpunk")).json.state.player.level, 12);
    setState('{"v":1,"seq":6,"at":');
    const o = (await call("GET", "/cyberpunk")).json;
    assert.equal(o.state.player.level, 12);
    assert.equal(o.status, "ingame");
  });

  it("blocs lents : vides sans fichier, lus ensuite, derniers valides gardés", async () => {
    assert.deepEqual((await call("GET", "/cyberpunk/inventory")).json, { v: 1, items: [] });
    assert.deepEqual((await call("GET", "/cyberpunk/contacts")).json, { v: 1, contacts: [], threads: [] });
    assert.deepEqual((await call("GET", "/cyberpunk/quests")).json, { v: 1, quests: [] });
    const quests = { v: 1, quests: [{ id: "q1", title: "Disasterpiece", kind: "quest", objective: "Rejoindre Judy.", tracked: true }] };
    fs.writeFileSync(mod("quests.json"), JSON.stringify(quests));
    assert.deepEqual((await call("GET", "/cyberpunk/quests")).json, quests);
    fs.writeFileSync(mod("quests.json"), '{"v":1,"quests":[');
    assert.deepEqual((await call("GET", "/cyberpunk/quests")).json, quests);
  });
});

describe("carte sans jeu", () => {
  it("POST /cyberpunk/map/prepare : 409 avec une phrase, sans téléchargement", async () => {
    const dir = path.join(steam, "steamapps", "common", "Cyberpunk 2077");
    fs.renameSync(dir, `${dir}.off`);
    try {
      const res = await call("POST", "/cyberpunk/map/prepare", {});
      assert.equal(res.status, 409);
      assert.match(res.json.message, /Cyberpunk 2077/);
    } finally {
      fs.renameSync(`${dir}.off`, dir);
    }
  });
});

describe("install et désinstallation du mod", () => {
  it("installe les trois fichiers, puis le dit à jour", async () => {
    const res = await call("POST", "/cyberpunk/install", {});
    assert.deepEqual(res.json, { ok: true, deferred: false });
    for (const f of ["init.lua", "logic.lua", "VERSION"]) assert.equal(fs.readFileSync(mod(f), "utf8"), asset(f));
    const install = (await call("GET", "/cyberpunk")).json.install;
    assert.equal(install.current, true);
    assert.equal(install.modVersion, "1.1.0");
    assert.equal(install.modInstalled, true);
    assert.equal((await call("GET", "/cyberpunk/presence")).json.modInstalled, true);
  });

  it("jeu ouvert et init.lua à changer : différé, puis fait à la fermeture du jeu", async () => {
    server.setOs({ running: ["Cyberpunk2077"] });
    const res = await call("POST", "/cyberpunk/install", {});
    assert.deepEqual(res.json, { ok: true, deferred: true });
    assert.equal(fs.existsSync(mod("init.lua")), false);
    assert.equal((await call("GET", "/cyberpunk")).json.install.pending, true);
    server.setOs({ running: [] });
    await call("GET", "/cyberpunk/presence");
    assert.equal(fs.readFileSync(mod("init.lua"), "utf8"), asset("init.lua"));
    assert.equal((await call("GET", "/cyberpunk")).json.install.pending, false);
  });

  it("jeu ouvert et seul logic.lua change : remplacé et rechargé (reload.txt non vide)", async () => {
    await call("POST", "/cyberpunk/install", {});
    fs.writeFileSync(mod("logic.lua"), "-- ancienne version");
    server.setOs({ running: ["Cyberpunk2077"] });
    const res = await call("POST", "/cyberpunk/install", {});
    assert.deepEqual(res.json, { ok: true, deferred: false });
    assert.equal(fs.readFileSync(mod("logic.lua"), "utf8"), asset("logic.lua"));
    assert.notEqual(fs.readFileSync(mod("reload.txt"), "utf8"), "");
  });

  it("CET absent : 409, rien d'écrit", async () => {
    const plugins = path.join(steam, "steamapps", "common", "Cyberpunk 2077", "bin", "x64", "plugins");
    fs.renameSync(path.join(plugins, "cyber_engine_tweaks.asi"), path.join(plugins, "cyber_engine_tweaks.off"));
    try {
      fs.renameSync(path.join(plugins, "cyber_engine_tweaks"), path.join(plugins, "cet-off"));
      const res = await call("POST", "/cyberpunk/install", {});
      assert.equal(res.status, 409);
      fs.renameSync(path.join(plugins, "cet-off"), path.join(plugins, "cyber_engine_tweaks"));
    } finally {
      fs.renameSync(path.join(plugins, "cyber_engine_tweaks.off"), path.join(plugins, "cyber_engine_tweaks.asi"));
    }
  });

  it("désinstalle : le dossier du mod disparaît, rien d'autre", async () => {
    await call("POST", "/cyberpunk/install", {});
    const res = await call("POST", "/cyberpunk/uninstall", {});
    assert.deepEqual(res.json, { ok: true, deferred: false });
    assert.equal(fs.existsSync(modDir), false);
    assert.equal(fs.existsSync(path.dirname(modDir)), true);
  });
});

describe("plugin coupé", () => {
  afterEach(async () => {
    await call("POST", "/plugins", { choices: { cyberpunk: true } });
  });

  it("présent dans /plugins sous Windows quand le jeu est trouvé, sans appli liée", async () => {
    const list = (await call("GET", "/plugins")).json.plugins;
    const cp = list.find((p: any) => p.id === "cyberpunk");
    assert.equal(cp.name, "Cyberpunk 2077");
    assert.equal(cp.kind, "app");
    assert.equal(cp.app, null);
  });

  it("routes en 403, sauf presence qui répond available: false", async () => {
    await call("POST", "/plugins", { choices: { cyberpunk: false } });
    for (const [m, r] of [
      ["GET", "/cyberpunk"],
      ["GET", "/cyberpunk/inventory"],
      ["POST", "/cyberpunk/install"],
      ["POST", "/cyberpunk/uninstall"],
    ])
      assert.equal((await call(m, r, m === "POST" ? {} : undefined)).status, 403, r);
    const p = await call("GET", "/cyberpunk/presence");
    assert.equal(p.status, 200);
    assert.equal(p.json.available, false);
  });

  it("sans jeton : 401", async () => {
    assert.equal((await server.call("GET", "/cyberpunk/presence", undefined, false)).status, 401);
  });
});

describe("hors Windows", () => {
  it("Mac : plugin absent, presence available: false", async () => {
    const mac = await startServer({ os: "mac", env: { MATE_STEAM_PATH: steam } });
    try {
      assert.equal((await mac.call("GET", "/cyberpunk/presence")).json.available, false);
      assert.ok(!(await mac.call("GET", "/plugins")).json.plugins.some((p: any) => p.id === "cyberpunk"));
      assert.equal((await mac.call("POST", "/cyberpunk/install", {})).status, 404);
    } finally {
      await mac.stop();
    }
  });
});
