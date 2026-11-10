import assert from "node:assert/strict";
import { after, before, beforeEach, describe, it } from "node:test";
import { liveRounds, resetLiveRounds } from "../src/valorant-rounds.ts";
import { mapStatsOf, todayOf } from "../src/valorant-session.ts";
import { ME, SUMMIT, TEST_ROSTER, startFakeRiot, type FakeRiot } from "./fake-riot.ts";
import { startServer, TOKEN, type TestServer } from "./server.ts";

/** Ajouts du 10/11/2026 : rounds, image de la carte, session du jour, bilan sur la carte jouée. */

const HOUR = 3_600_000;
const DAY = 24 * HOUR;
const SUMMIT_PATH = "/Game/Maps/Plummet/Plummet";
const ASCENT_PATH = "/Game/Maps/Ascent/Ascent";

async function until(server: TestServer, ok: (json: any) => boolean, ms = 3000): Promise<any> {
  const stop = Date.now() + ms;
  for (;;) {
    const json = (await server.call("GET", "/valorant")).json;
    if (ok(json)) return json;
    if (Date.now() > stop) assert.fail(`attente dépassée : ${JSON.stringify(json)}`);
    await new Promise((r) => setTimeout(r, 30));
  }
}

async function image(server: TestServer, route: string) {
  const res = await fetch(`${server.url}${route}`, { headers: { authorization: `Bearer ${TOKEN}` } });
  return { status: res.status, type: res.headers.get("content-type"), data: Buffer.from(await res.arrayBuffer()) };
}

/** 45 classées : les 2 plus récentes aujourd'hui, puis une par jour ; une sur trois sur Summit (la carte du roster). */
const UPDATES = Array.from({ length: 45 }, (_, i) => {
  const rr = i === 0 ? 21 : i === 1 ? -17 : i % 2 ? -15 : 18;
  const start = i < 2 ? Date.now() - (i + 1) * 60_000 : Date.now() - i * DAY;
  return [`p${i}`, rr, i % 3 === 0 ? SUMMIT_PATH : ASCENT_PATH, start] as const;
});

let riot: FakeRiot;
let server: TestServer;
before(async () => {
  riot = await startFakeRiot({ ...TEST_ROSTER, updates: [["jeu-1", 21, SUMMIT_PATH], ...UPDATES.slice(1)] });
});
after(async () => {
  await server?.stop();
  riot.close();
});
beforeEach(async () => {
  riot.reset();
  await server?.stop();
  server = await startServer({ os: "windows", env: riot.env });
  server.setOs({ riotLockfile: riot.lockfile });
});

describe("Valorant : rounds de la partie en cours", () => {
  it("le score qui bouge ajoute un round gagné ou perdu ; arrivé en cours de partie, l'ordre d'avant est inconnu", async () => {
    riot.scene.loop = "INGAME";
    riot.scene.score = [2, 1];
    assert.deepEqual((await server.call("GET", "/valorant")).json.rounds, [null, null, null]);
    riot.scene.score = [3, 1];
    assert.deepEqual((await server.call("GET", "/valorant")).json.rounds, [null, null, null, true]);
    riot.scene.score = [3, 2];
    assert.deepEqual((await server.call("GET", "/valorant")).json.rounds, [null, null, null, true, false]);
    // Au menu : pas de frise.
    riot.scene.loop = "MENUS";
    assert.deepEqual((await server.call("GET", "/valorant")).json.rounds, []);
  });

  it("logique : un seul camp a marqué, on sait tout ; les deux ont bougé, ordre inconnu ; nouvelle partie, tout repart", () => {
    resetLiveRounds();
    assert.deepEqual(liveRounds("a", { ally: 2, enemy: 0 }), [true, true]);
    assert.deepEqual(liveRounds("a", { ally: 2, enemy: 2 }), [true, true, false, false]);
    assert.deepEqual(liveRounds("a", { ally: 3, enemy: 3 }), [true, true, false, false, null, null]);
    assert.deepEqual(liveRounds("a", { ally: 3, enemy: 3 }), [true, true, false, false, null, null]);
    assert.deepEqual(liveRounds("b", { ally: 0, enemy: 1 }), [false]);
    // Partie suivie inconnue (core-game pas lu) : même partie tant que le score ne recule pas.
    assert.deepEqual(liveRounds(null, { ally: 1, enemy: 1 }), [false, true]);
    assert.deepEqual(liveRounds(null, { ally: 0, enemy: 0 }), []);
  });
});

describe("Valorant : fin de partie", () => {
  it("chaque round vu de mon équipe, avec sa cause ; RR gagnés dans cette partie", async () => {
    riot.scene.loop = "INGAME";
    await server.call("GET", "/valorant");
    riot.scene.loop = "MENUS";
    const json = await until(server, (j) => j.end?.status === "ready" && j.end.rr != null);
    const rounds = json.end.rounds;
    assert.equal(rounds.length, 20);
    assert.deepEqual(rounds.slice(0, 5), [
      { won: true, end: "detonate" },
      { won: false, end: "elimination" },
      { won: true, end: "defuse" },
      { won: false, end: "time" },
      { won: true, end: "elimination" },
    ]);
    assert.deepEqual(rounds.at(-1), { won: false, end: null });
    assert.equal(rounds.filter((r: any) => r.won).length, 13);
    assert.equal(json.end.rr, 21);
    assert.equal(json.end.mapId, SUMMIT);
  });
});

describe("Valorant : image de la carte", () => {
  it("identifiant de la carte dans l'état, image réduite servie par l'ordinateur", async () => {
    riot.scene.loop = "PREGAME";
    const json = await until(server, (j) => j.mapId != null);
    assert.equal(json.mapId, SUMMIT);
    const map = await image(server, `/valorant/map?id=${SUMMIT}`);
    assert.equal(map.status, 200);
    assert.equal(map.type, "image/png");
    assert.deepEqual([map.data.readUInt32BE(16), map.data.readUInt32BE(20)], [512, 512]);
    // Deuxième demande : depuis le cache de l'ordinateur.
    await image(server, `/valorant/map?id=${SUMMIT}`);
    assert.equal(riot.count("/maps/"), 1);
  });

  it("carte sans image ou inconnue : 404 ; identifiant invalide : 400 ; sans jeton : 401", async () => {
    await server.call("GET", "/valorant");
    assert.equal((await image(server, "/valorant/map?id=00000000-0000-0000-0000-000000000000")).status, 404);
    assert.equal((await image(server, "/valorant/map?id=..%2F..%2Fsecret")).status, 400);
    assert.equal((await fetch(`${server.url}/valorant/map?id=${SUMMIT}`)).status, 401);
  });

  it("au menu : pas de carte", async () => {
    assert.equal((await server.call("GET", "/valorant")).json.mapId, null);
  });
});

describe("Valorant : session du jour et bilan sur la carte", () => {
  it("au menu : RR, victoires et défaites des classées d'aujourd'hui ; pas de bilan de carte", async () => {
    const json = await until(server, (j) => j.today != null);
    assert.deepEqual(json.today, { rr: 4, wins: 1, losses: 1, results: [false, true] });
    assert.equal(json.mapStats, null);
  });

  it("sélection d'agents : bilan sur la carte jouée, sur mes 40 dernières (deux pages de 20, dans la file)", async () => {
    riot.scene.loop = "PREGAME";
    const json = await until(server, (j) => j.mapStats != null);
    // Parties 0, 3, …, 39 (jeu-1 compris) : 14 sur Summit ; gagnées : jeu-1 et les paires. De la plus ancienne à la plus récente.
    assert.deepEqual(json.mapStats, {
      wins: 7,
      games: 14,
      window: 40,
      results: [false, true, false, true, false, true, false, true, false, true, false, true, false, true],
    });
    const pages = riot.seen.filter((s) => s.url.startsWith(`/mmr/v1/players/${ME}/competitiveupdates?`)).map((s) => s.url);
    assert.ok(pages.some((u) => u.includes("startIndex=0&endIndex=20")));
    assert.ok(pages.some((u) => u.includes("startIndex=20&endIndex=40")));
    assert.ok(json.today != null);
  });

  it("logique : minuit de l'ordinateur ; RR à 0 (nul) ni gagné ni perdu", () => {
    const now = new Date(2026, 10, 10, 9, 0).getTime();
    const at = (h: number) => new Date(2026, 10, 10, h, 0).getTime();
    const updates = [
      { id: "a", mapId: ASCENT_PATH, rr: 0, start: at(8) },
      { id: "b", mapId: ASCENT_PATH, rr: -12, start: at(1) },
      { id: "c", mapId: SUMMIT_PATH, rr: 20, start: at(0) + 60_000 },
      { id: "d", mapId: SUMMIT_PATH, rr: 25, start: at(0) - 60_000 },
    ];
    assert.deepEqual(todayOf(updates, now), { rr: 8, wins: 1, losses: 1, results: [true, false, null] });
    assert.deepEqual(todayOf([], now), { rr: 0, wins: 0, losses: 0, results: [] });
    assert.deepEqual(mapStatsOf(updates, "/game/maps/plummet/plummet"), { wins: 2, games: 2, window: 4, results: [true, true] });
    assert.equal(mapStatsOf(updates, null), null);
  });
});
