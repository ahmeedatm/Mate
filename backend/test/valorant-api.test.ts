import assert from "node:assert/strict";
import path from "node:path";
import { after, before, beforeEach, describe, it } from "node:test";
import { riotServer } from "../src/valorant-riot.ts";
import { ACCESS, ENTITLEMENT, PASSWORD, startFakeRiot, type FakeRiot } from "./fake-riot.ts";
import { startServer, type TestServer } from "./server.ts";

/** Champs du JSON d'avant la refonte : une tablette pas encore mise à jour les lit toujours, à l'identique. */
const OLD_PLAYER = ["name", "agent", "locked", "tier", "rank", "rr", "ally", "me"];
const OLD_STATE = ["state", "queue", "map", "score", "me", "players"];
const OLD_ME = ["name", "tier", "rank", "rr", "level"];
const pick = (o: any, keys: string[]) => Object.fromEntries(keys.map((k) => [k, o[k]]));
const old = (json: any) => ({
  ...pick(json, OLD_STATE),
  me: json.me && pick(json.me, OLD_ME),
  players: json.players.map((p: any) => pick(p, OLD_PLAYER)),
});

let riot: FakeRiot;
let server: TestServer;
before(async () => {
  riot = await startFakeRiot();
  server = await startServer({ os: "windows", env: riot.env });
});
after(async () => {
  await server?.stop();
  riot.close();
});
beforeEach(() => {
  riot.reset();
  server.setOs({ riotLockfile: riot.lockfile });
});

describe("Valorant : rang et joueurs de la partie, lus dans le client Riot", () => {
  it("/deck/features annonce valorant sous Windows", async () => {
    assert.equal((await server.call("GET", "/deck/features")).json.valorant, true);
  });

  it("sans client Riot (pas de lockfile) : off", async () => {
    server.setOs({ riotLockfile: path.join(path.dirname(riot.lockfile), "absent") });
    assert.deepEqual(old((await server.call("GET", "/valorant")).json), {
      state: "off",
      queue: null,
      map: null,
      score: null,
      me: null,
      players: [],
    });
  });

  it("au menu : mon rang et mes RR, aucun joueur ; le mot de passe du client sert en Basic", async () => {
    const res = await server.call("GET", "/valorant");
    assert.equal(res.status, 200);
    assert.deepEqual(old(res.json), {
      state: "menu",
      queue: "Compétition",
      map: null,
      score: null,
      me: { name: "Eliot#EUW", tier: 10, rank: "Argent 2", rr: 42, level: 90 },
      players: [],
    });
    assert.ok(riot.localAuth.every((a) => a === `Basic ${Buffer.from(`riot:${PASSWORD}`).toString("base64")}`));
  });

  it("en file : queue", async () => {
    riot.scene.partyState = "MATCHMAKING";
    assert.equal((await server.call("GET", "/valorant")).json.state, "queue");
  });

  it("sélection d'agents : mon équipe seulement, agent verrouillé ou pas encore choisi", async () => {
    riot.scene.loop = "PREGAME";
    const res = await server.call("GET", "/valorant");
    assert.equal(res.json.state, "pregame");
    assert.equal(res.json.map, "Summit");
    assert.deepEqual(old(res.json).players, [
      { name: "Kinj#EUW", agent: null, locked: false, tier: 12, rank: "Or 1", rr: 15, ally: true, me: false },
      { name: "Eliot#EUW", agent: "Sova", locked: true, tier: 10, rank: "Argent 2", rr: 42, ally: true, me: true },
    ]);
  });

  it("en partie : les deux équipes, triées par rang, incognito sans nom ; score de la présence", async () => {
    riot.scene.loop = "INGAME";
    const res = await server.call("GET", "/valorant");
    assert.equal(res.json.state, "ingame");
    assert.deepEqual(res.json.score, { ally: 2, enemy: 0 });
    assert.deepEqual(old(res.json).players, [
      { name: "Kinj#EUW", agent: "Sage", locked: true, tier: 12, rank: "Or 1", rr: 15, ally: true, me: false },
      { name: "Eliot#EUW", agent: "Sova", locked: true, tier: 10, rank: "Argent 2", rr: 42, ally: true, me: true },
      { name: "Adversaire#1", agent: "Jett", locked: true, tier: 18, rank: "Diamant 1", rr: 60, ally: false, me: false },
      { name: null, agent: null, locked: true, tier: 9, rank: "Argent 1", rr: 97, ally: false, me: false },
    ]);
  });

  it("serveurs Riot appelés avec le jeton du client ; aucun jeton Riot dans la réponse", async () => {
    riot.scene.loop = "INGAME";
    const res = await server.call("GET", "/valorant");
    const glz = riot.seen.find((s) => s.url.startsWith("/core-game/"));
    assert.equal(glz?.auth, `Bearer ${ACCESS}`);
    assert.equal(glz?.entitlements, ENTITLEMENT);
    assert.equal(glz?.version, "release-13.09-shipping-18-5804228");
    const text = JSON.stringify(res.json);
    for (const secret of [ACCESS, ENTITLEMENT, PASSWORD]) assert.ok(!text.includes(secret));
    assert.ok(!server.output().includes(ACCESS) && !server.output().includes(PASSWORD));
  });

  it("rangs gardés : une seconde lecture ne redemande pas le mmr", async () => {
    riot.scene.loop = "INGAME";
    await server.call("GET", "/valorant");
    riot.reset();
    riot.scene.loop = "INGAME";
    await server.call("GET", "/valorant");
    assert.equal(riot.count("/mmr/"), 0);
  });

  it("mmr en échec : pas redemandé à chaque lecture", async () => {
    riot.scene.loop = "INGAME";
    riot.scene.withNewcomer = true;
    const first = await server.call("GET", "/valorant");
    assert.equal(first.json.players.length, 5);
    await server.call("GET", "/valorant");
    assert.equal(riot.count("/mmr/v1/players/nouveau"), 1);
  });

  it("client lancé sans compte connecté : aucun appel aux serveurs Riot sans jeton", async () => {
    riot.scene.signedIn = false;
    riot.scene.loop = "INGAME";
    const res = await server.call("GET", "/valorant");
    assert.equal(res.json.state, "ingame");
    assert.deepEqual(old(res.json).players, []);
    assert.equal(riot.seen.filter((s) => !s.url.startsWith("/v1/")).length, 0);
  });

  it("sans jeton de la tablette : 401", async () => {
    assert.equal((await server.call("GET", "/valorant", undefined, false)).status, 401);
  });
});

describe("Valorant hors Windows", () => {
  let mac: TestServer;
  before(async () => {
    mac = await startServer({ os: "mac" });
  });
  after(async () => {
    await mac?.stop();
  });

  it("pas annoncé, et /valorant répond off", async () => {
    assert.equal((await mac.call("GET", "/deck/features")).json.valorant, false);
    assert.equal((await mac.call("GET", "/valorant")).json.state, "off");
  });
});

describe("Valorant : serveurs de test", () => {
  it("une adresse de remplacement n'est suivie que vers la boucle locale (le jeton Riot ne part pas ailleurs)", () => {
    assert.equal(riotServer("http://127.0.0.1:4000", "https://pd.eu.a.pvp.net"), "http://127.0.0.1:4000");
    assert.equal(riotServer("http://localhost:4000", "https://pd.eu.a.pvp.net"), "http://localhost:4000");
    assert.equal(riotServer("https://exemple.fr", "https://pd.eu.a.pvp.net"), "https://pd.eu.a.pvp.net");
    assert.equal(riotServer("http://127.0.0.1.exemple.fr", "https://pd.eu.a.pvp.net"), "https://pd.eu.a.pvp.net");
    assert.equal(riotServer("pas une adresse", "https://pd.eu.a.pvp.net"), "https://pd.eu.a.pvp.net");
    assert.equal(riotServer(undefined, "https://pd.eu.a.pvp.net"), "https://pd.eu.a.pvp.net");
  });
});
