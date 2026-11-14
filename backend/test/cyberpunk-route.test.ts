import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { after, before, beforeEach, describe, it } from "node:test";
import { MAX_ROUTE_POINTS, simplifyRoute } from "../src/cyberpunk-route.ts";
import { fakeSteam } from "./cyberpunk-fakemod.ts";
import { startServer, type TestServer } from "./server.ts";

let steam: string;
let modDir: string;
let server: TestServer;
const call = (m: string, r: string) => server.call(m, r);
const now = () => Date.now() / 1000;
let seq = 0;
const setRoute = (route: object | string) =>
  fs.writeFileSync(
    path.join(modDir, "route.json"),
    typeof route === "string" ? route : JSON.stringify({ v: 1, seq: ++seq, at: now(), ...route }),
  );

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

describe("simplifyRoute", () => {
  it("garde les virages, retire les points alignés d'une rue droite", () => {
    // Une rue droite de 0 à 100 (11 points), puis un virage à angle droit jusqu'à (100, 50).
    const straight = Array.from({ length: 11 }, (_, i) => [i * 10, 0.2 * (i % 2)]).flat();
    const pts = [...straight, 100, 25, 100, 50];
    assert.deepEqual(simplifyRoute(pts), [0, 0, 100, 0, 100, 50]);
  });

  it("arrondit au décimètre, et ne garde pas plus de MAX_ROUTE_POINTS points (le début, côté voiture)", () => {
    assert.deepEqual(simplifyRoute([1.234, 5.678, 9.87, 6.54]), [1.2, 5.7, 9.9, 6.5]);
    // Zigzag de 30 m d'amplitude : rien n'est retiré, la coupe s'applique.
    const zigzag = Array.from({ length: MAX_ROUTE_POINTS + 50 }, (_, i) => [i * 10, (i % 2) * 30]).flat();
    const out = simplifyRoute(zigzag);
    assert.equal(out.length, MAX_ROUTE_POINTS * 2);
    assert.deepEqual(out.slice(0, 4), [0, 0, 10, 30]);
  });

  it("moins de deux points ou coordonnées illisibles : pas de tracé", () => {
    assert.deepEqual(simplifyRoute([1, 2]), []);
    assert.deepEqual(simplifyRoute([1, 2, 3]), []);
    assert.deepEqual(simplifyRoute([1, 2, Number.NaN, 4]), []);
  });
});

describe("/cyberpunk/route (chemin du GPS du jeu, en véhicule)", () => {
  it("route.json frais : le chemin simplifié et sa sorte (quête ou repère suivi)", async () => {
    setRoute({ iwn: true, kind: "poi", pts: [0, 0, 50, 0, 100, 0, 100, 80] });
    assert.deepEqual((await call("GET", "/cyberpunk/route")).json, {
      fresh: true,
      source: "iwn",
      kind: "poi",
      points: [0, 0, 100, 0, 100, 80],
    });
  });

  it("aucune destination suivie : frais, sans points", async () => {
    setRoute({ iwn: true, kind: null, pts: [] });
    assert.deepEqual((await call("GET", "/cyberpunk/route")).json, { fresh: true, source: "iwn", kind: null, points: [] });
  });

  it("In-World Navigation absent du jeu : frais, source absent, sans points (la tablette garde la ligne droite)", async () => {
    setRoute({ iwn: false });
    assert.deepEqual((await call("GET", "/cyberpunk/route")).json, { fresh: true, source: "absent", kind: null, points: [] });
  });

  it("route.json vieux, absent ou illisible : périmé, sans chemin", async () => {
    setRoute({ iwn: true, kind: "quest", pts: [0, 0, 10, 10], at: now() - 5 });
    assert.deepEqual((await call("GET", "/cyberpunk/route")).json, { fresh: false });
    fs.rmSync(path.join(modDir, "route.json"));
    assert.deepEqual((await call("GET", "/cyberpunk/route")).json, { fresh: false });
    setRoute('{"v":1,"seq":');
    assert.deepEqual((await call("GET", "/cyberpunk/route")).json, { fresh: false });
  });

  it("points qui ne sont pas des nombres : frais, sans points (jamais un chemin inventé)", async () => {
    setRoute({ iwn: true, kind: "quest", pts: ["a", 2, 3, 4] });
    assert.deepEqual((await call("GET", "/cyberpunk/route")).json, { fresh: true, source: "iwn", kind: "quest", points: [] });
  });
});
