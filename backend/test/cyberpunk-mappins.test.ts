import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { after, afterEach, before, beforeEach, describe, it } from "node:test";
import { categoryOf, POI_ICONS } from "../src/cyberpunk-poi.ts";
import { fakeSteam } from "./cyberpunk-fakemod.ts";
import { startServer, type TestServer } from "./server.ts";

let steam: string;
let modDir: string;
let server: TestServer;
const call = (m: string, r: string, b?: unknown) => server.call(m, r, b);
const writeMappins = (body: object | string) =>
  fs.writeFileSync(path.join(modDir, "mappins.json"), typeof body === "string" ? body : JSON.stringify(body));

before(async () => {
  ({ steam, modDir } = fakeSteam());
  server = await startServer({ os: "windows", env: { MATE_STEAM_PATH: steam } });
});
after(async () => {
  await server?.stop();
  fs.rmSync(steam, { recursive: true, force: true });
});
beforeEach(() => {
  fs.rmSync(modDir, { recursive: true, force: true });
  fs.mkdirSync(modDir, { recursive: true });
});

describe("categoryOf", () => {
  it("variantes vues au spike : une catégorie chacune", () => {
    assert.equal(categoryOf("DefaultQuestVariant", false), "quest");
    assert.equal(categoryOf("ExclamationMarkVariant", true), "quest");
    assert.equal(categoryOf("FixerVariant", false), "gig");
    assert.equal(categoryOf("RetrievingVariant", false), "gig");
    assert.equal(categoryOf("ClientInDistressVariant", false), "gig");
    assert.equal(categoryOf("GangWatchVariant", false), "ncpd");
    assert.equal(categoryOf("HiddenStashVariant", false), "ncpd");
    assert.equal(categoryOf("HuntForPsychoVariant", true), "ncpd");
    assert.equal(categoryOf("FastTravelVariant", false), "travel");
    assert.equal(categoryOf("Zzz17_NCARTVariant", false), "travel");
    assert.equal(categoryOf("ServicePointGunsVariant", false), "shop");
    assert.equal(categoryOf("Zzz21_ServicePointGlamdocVariant", false), "shop");
    assert.equal(categoryOf("ServicePointRipperdocVariant", false), "ripperdoc");
    assert.equal(categoryOf("ServicePointDropPointVariant", false), "other");
    assert.equal(categoryOf("TarotVariant", false), "other");
  });

  it("variante inconnue : quête si c'est un mappin de quête, sinon Autres", () => {
    assert.equal(categoryOf("Zzz99_NouvelleVariant", true), "quest");
    assert.equal(categoryOf("Zzz99_NouvelleVariant", false), "other");
  });

  it("icônes connues : toutes distinctes et en nom simple (servies en poi_<nom>.png)", () => {
    assert.equal(new Set(POI_ICONS).size, POI_ICONS.length);
    for (const i of POI_ICONS) assert.match(i, /^[a-z_]{1,30}$/);
  });
});

describe("GET /cyberpunk/mappins", () => {
  it("sans fichier du mod : liste vide", async () => {
    assert.deepEqual((await call("GET", "/cyberpunk/mappins")).json, { v: 1, points: [] });
  });

  it("catégorie, icône connue gardée, inconnue à null, nom gardé, non découvert ou terminé atténué", async () => {
    writeMappins({
      v: 1,
      mappins: [
        { id: "11", x: -1397.3, y: 1278.1, v: "ServicePointRipperdocVariant", ph: "Invalid", icon: "ripperdoc", name: "", q: false },
        { id: "12", x: 10, y: 20, v: "GangWatchVariant", ph: "UndiscoveredPhase", icon: "map_bounty", q: false },
        { id: "13", x: 11, y: 21, v: "GangWatchVariant", ph: "CompletedPhase", icon: "map_bounty", q: false },
        { id: "14", x: 12, y: 22, v: "FastTravelVariant", ph: "DefaultPhase", icon: "fast_travel", name: "Afterlife", q: false },
        { id: "15", x: 13, y: 23, v: "Zzz99_NouvelleVariant", ph: "DefaultPhase", icon: "piece_inconnue", q: false },
      ],
    });
    const res = await call("GET", "/cyberpunk/mappins");
    assert.equal(res.status, 200);
    assert.deepEqual(res.json, {
      v: 1,
      points: [
        { id: "11", x: -1397.3, y: 1278.1, cat: "ripperdoc", icon: "ripperdoc", name: null, dim: false },
        { id: "12", x: 10, y: 20, cat: "ncpd", icon: "map_bounty", name: null, dim: true },
        { id: "13", x: 11, y: 21, cat: "ncpd", icon: "map_bounty", name: null, dim: true },
        { id: "14", x: 12, y: 22, cat: "travel", icon: "fast_travel", name: "Afterlife", dim: false },
        { id: "15", x: 13, y: 23, cat: "other", icon: null, name: null, dim: false },
      ],
    });
  });

  it("points sans position lisible ignorés ; JSON coupé : la dernière liste valide est gardée", async () => {
    writeMappins({
      v: 1,
      mappins: [
        { id: "1", x: 1, y: 2, v: "FastTravelVariant", ph: "DefaultPhase", icon: "fast_travel" },
        { id: "2", x: "loin", y: 2, v: "FastTravelVariant" },
        { id: "3", y: 2, v: "FastTravelVariant" },
        "pas un objet",
      ],
    });
    const first = (await call("GET", "/cyberpunk/mappins")).json;
    assert.deepEqual(
      first.points.map((p: { id: string }) => p.id),
      ["1"],
    );
    writeMappins('{"v":1,"mappins":[');
    assert.deepEqual((await call("GET", "/cyberpunk/mappins")).json, first);
  });

  describe("plugin coupé", () => {
    afterEach(async () => {
      await call("POST", "/plugins", { choices: { cyberpunk: true } });
    });
    it("403", async () => {
      await call("POST", "/plugins", { choices: { cyberpunk: false } });
      assert.equal((await call("GET", "/cyberpunk/mappins")).status, 403);
    });
  });
});
