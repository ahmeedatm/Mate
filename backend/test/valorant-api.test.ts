import assert from "node:assert/strict";
import fs from "node:fs";
import http from "node:http";
import type { AddressInfo } from "node:net";
import os from "node:os";
import path from "node:path";
import { after, before, beforeEach, describe, it } from "node:test";
import { riotServer } from "../src/valorant.ts";
import { startServer, type TestServer } from "./server.ts";

const ME = "puuid-moi";
const PASSWORD = "mot-de-passe-du-client";
const ACCESS = "jeton-riot-secret";
const ENTITLEMENT = "droits-riot-secrets";
const SOVA = "320b2a48-4d9b-a075-30f1-1f93a9b638fa";
const SAGE = "569fdd95-4d10-43ab-ca70-79becc718b46";
const JETT = "add6443a-41bd-e414-f6ad-e58d267f4e95";

/** Présence Valorant du joueur, telle que le client la donne (`private` en base64). */
const presence = (loop: string, over: Record<string, unknown> = {}) => ({
  puuid: ME,
  product: "valorant",
  private: Buffer.from(
    JSON.stringify({
      matchPresenceData: { sessionLoopState: loop, matchMap: "/Game/Maps/Plummet/Plummet", queueId: "competitive" },
      partyPresenceData: { partyState: "DEFAULT", partyClientVersion: "release-13.09-shipping-18-5804228" },
      playerPresenceData: { competitiveTier: 10, accountLevel: 90 },
      partyOwnerMatchScoreAllyTeam: 2,
      partyOwnerMatchScoreEnemyTeam: 0,
      queueId: "competitive",
      ...over,
    }),
  ).toString("base64"),
});

/** Faux client Riot (local) et faux serveurs Riot (glz, pd) : `loop` fixe l'état de la partie. */
let loop = "MENUS";
let partyState = "DEFAULT";
/** Client lancé mais pas connecté à un compte : pas de jeton. */
let signedIn = true;
/** Un joueur de plus dans la partie, dont le mmr répond 503 (pd qui limite les appels). */
let withNewcomer = false;
let remoteSeen: { url: string; auth?: string; entitlements?: string; version?: string }[] = [];
let localAuth: string[] = [];
const send = (res: http.ServerResponse, body: unknown, status = 200) => {
  res.statusCode = status;
  res.setHeader("content-type", "application/json");
  res.end(JSON.stringify(body));
};
const riotClient = http.createServer((req, res) => {
  localAuth.push(String(req.headers.authorization));
  if (req.url === "/chat/v1/session") return send(res, { puuid: ME, game_name: "Eliot", game_tag: "EUW" });
  if (req.url === "/chat/v4/presences")
    return send(res, {
      presences: [
        { puuid: "un-ami", product: "league_of_legends", private: "" },
        presence(loop, { partyPresenceData: { partyState, partyClientVersion: "release-13.09-shipping-18-5804228" } }),
      ],
    });
  if (req.url === "/entitlements/v1/token") return send(res, signedIn ? { accessToken: ACCESS, token: ENTITLEMENT, subject: ME } : {});
  if (req.url === "/product-session/v1/external-sessions")
    return send(res, { x: { productId: "valorant", launchConfiguration: { arguments: ["-ares-deployment=eu", "-config-endpoint=x"] } } });
  send(res, { message: "inconnu" }, 404);
});

const tierOf: Record<string, [number, number]> = {
  [ME]: [10, 42],
  ami: [12, 15],
  cache: [9, 97],
  ennemi: [18, 60],
};
const riotServers = http.createServer((req, res) => {
  let raw = "";
  req.on("data", (c) => (raw += c));
  req.on("end", () => {
    remoteSeen.push({
      url: req.url ?? "",
      auth: req.headers.authorization,
      entitlements: req.headers["x-riot-entitlements-jwt"] as string,
      version: req.headers["x-riot-clientversion"] as string,
    });
    const url = req.url ?? "";
    if (url === `/pregame/v1/players/${ME}`) return loop === "PREGAME" ? send(res, { MatchID: "pre-1" }) : send(res, {}, 404);
    if (url === "/pregame/v1/matches/pre-1")
      return send(res, {
        MapID: "/Game/Maps/Plummet/Plummet",
        AllyTeam: {
          Players: [
            {
              Subject: ME,
              CharacterID: SOVA,
              CharacterSelectionState: "locked",
              CompetitiveTier: 10,
              PlayerIdentity: { Incognito: false },
            },
            { Subject: "ami", CharacterID: "", CharacterSelectionState: "", CompetitiveTier: 12, PlayerIdentity: { Incognito: false } },
          ],
        },
      });
    if (url === `/core-game/v1/players/${ME}`) return loop === "INGAME" ? send(res, { MatchID: "jeu-1" }) : send(res, {}, 404);
    if (url === "/core-game/v1/matches/jeu-1")
      return send(res, {
        MapID: "/Game/Maps/Plummet/Plummet",
        Players: [
          { Subject: "ennemi", TeamID: "Red", CharacterID: JETT, PlayerIdentity: { Incognito: false } },
          { Subject: ME, TeamID: "Blue", CharacterID: SOVA, PlayerIdentity: { Incognito: false } },
          { Subject: "cache", TeamID: "Red", CharacterID: "inconnu", PlayerIdentity: { Incognito: true } },
          { Subject: "ami", TeamID: "Blue", CharacterID: SAGE, PlayerIdentity: { Incognito: false } },
          ...(withNewcomer ? [{ Subject: "nouveau", TeamID: "Red", CharacterID: JETT, PlayerIdentity: { Incognito: false } }] : []),
        ],
      });
    if (url === "/name-service/v2/players" && req.method === "PUT") {
      const names: Record<string, [string, string]> = {
        [ME]: ["Eliot", "EUW"],
        ami: ["Kinj", "EUW"],
        cache: ["Secret", "000"],
        ennemi: ["Adversaire", "1"],
      };
      return send(
        res,
        (JSON.parse(raw) as string[]).map((s) => ({ Subject: s, GameName: names[s]?.[0] ?? "", TagLine: names[s]?.[1] ?? "" })),
      );
    }
    const mmr = url.match(/^\/mmr\/v1\/players\/(.+)$/);
    if (mmr) {
      if (mmr[1] === "nouveau") return send(res, { message: "trop d'appels" }, 503);
      const [tier, rr] = tierOf[mmr[1]] ?? [0, 0];
      return send(res, { LatestCompetitiveUpdate: { TierAfterUpdate: tier, RankedRatingAfterUpdate: rr } });
    }
    send(res, { message: "inconnu" }, 404);
  });
});

const listen = (s: http.Server) => new Promise<number>((r) => s.listen(0, "127.0.0.1", () => r((s.address() as AddressInfo).port)));

let server: TestServer;
let lockfile: string;
before(async () => {
  const localPort = await listen(riotClient);
  const remotePort = await listen(riotServers);
  lockfile = path.join(fs.mkdtempSync(path.join(os.tmpdir(), "riot-")), "lockfile");
  fs.writeFileSync(lockfile, `Riot Client:1234:${localPort}:${PASSWORD}:http`);
  const remote = `http://127.0.0.1:${remotePort}`;
  server = await startServer({ os: "windows", env: { VALORANT_GLZ_URL: remote, VALORANT_PD_URL: remote } });
});
after(async () => {
  await server?.stop();
  riotClient.close();
  riotServers.close();
});
beforeEach(() => {
  loop = "MENUS";
  partyState = "DEFAULT";
  signedIn = true;
  withNewcomer = false;
  remoteSeen = [];
  localAuth = [];
  server.setOs({ riotLockfile: lockfile });
});

describe("Valorant : rang et joueurs de la partie, lus dans le client Riot", () => {
  it("/deck/features annonce valorant sous Windows", async () => {
    assert.equal((await server.call("GET", "/deck/features")).json.valorant, true);
  });

  it("sans client Riot (pas de lockfile) : off", async () => {
    server.setOs({ riotLockfile: path.join(path.dirname(lockfile), "absent") });
    assert.deepEqual((await server.call("GET", "/valorant")).json, {
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
    assert.deepEqual(res.json, {
      state: "menu",
      queue: "Compétition",
      map: null,
      score: null,
      me: { name: "Eliot#EUW", tier: 10, rank: "Argent 2", rr: 42, level: 90 },
      players: [],
    });
    assert.ok(localAuth.every((a) => a === `Basic ${Buffer.from(`riot:${PASSWORD}`).toString("base64")}`));
  });

  it("en file : queue", async () => {
    partyState = "MATCHMAKING";
    assert.equal((await server.call("GET", "/valorant")).json.state, "queue");
  });

  it("sélection d'agents : mon équipe seulement, agent verrouillé ou pas encore choisi", async () => {
    loop = "PREGAME";
    const res = await server.call("GET", "/valorant");
    assert.equal(res.json.state, "pregame");
    assert.equal(res.json.map, "Summit");
    assert.deepEqual(res.json.players, [
      { name: "Kinj#EUW", agent: null, locked: false, tier: 12, rank: "Or 1", rr: 15, ally: true, me: false },
      { name: "Eliot#EUW", agent: "Sova", locked: true, tier: 10, rank: "Argent 2", rr: 42, ally: true, me: true },
    ]);
  });

  it("en partie : les deux équipes, triées par rang, incognito sans nom ; score de la présence", async () => {
    loop = "INGAME";
    const res = await server.call("GET", "/valorant");
    assert.equal(res.json.state, "ingame");
    assert.deepEqual(res.json.score, { ally: 2, enemy: 0 });
    assert.deepEqual(res.json.players, [
      { name: "Kinj#EUW", agent: "Sage", locked: true, tier: 12, rank: "Or 1", rr: 15, ally: true, me: false },
      { name: "Eliot#EUW", agent: "Sova", locked: true, tier: 10, rank: "Argent 2", rr: 42, ally: true, me: true },
      { name: "Adversaire#1", agent: "Jett", locked: true, tier: 18, rank: "Diamant 1", rr: 60, ally: false, me: false },
      { name: null, agent: null, locked: true, tier: 9, rank: "Argent 1", rr: 97, ally: false, me: false },
    ]);
  });

  it("serveurs Riot appelés avec le jeton du client ; aucun jeton Riot dans la réponse", async () => {
    loop = "INGAME";
    const res = await server.call("GET", "/valorant");
    const glz = remoteSeen.find((s) => s.url.startsWith("/core-game/"));
    assert.equal(glz?.auth, `Bearer ${ACCESS}`);
    assert.equal(glz?.entitlements, ENTITLEMENT);
    assert.equal(glz?.version, "release-13.09-shipping-18-5804228");
    const text = JSON.stringify(res.json);
    for (const secret of [ACCESS, ENTITLEMENT, PASSWORD]) assert.ok(!text.includes(secret));
    assert.ok(!server.output().includes(ACCESS) && !server.output().includes(PASSWORD));
  });

  it("rangs gardés : une seconde lecture ne redemande pas le mmr", async () => {
    loop = "INGAME";
    await server.call("GET", "/valorant");
    remoteSeen = [];
    await server.call("GET", "/valorant");
    assert.equal(remoteSeen.filter((s) => s.url.startsWith("/mmr/")).length, 0);
  });

  it("mmr en échec : pas redemandé à chaque lecture", async () => {
    loop = "INGAME";
    withNewcomer = true;
    const first = await server.call("GET", "/valorant");
    assert.equal(first.json.players.length, 5);
    await server.call("GET", "/valorant");
    assert.equal(remoteSeen.filter((s) => s.url === "/mmr/v1/players/nouveau").length, 1);
  });

  it("client lancé sans compte connecté : aucun appel aux serveurs Riot sans jeton", async () => {
    signedIn = false;
    loop = "INGAME";
    const res = await server.call("GET", "/valorant");
    assert.equal(res.json.state, "ingame");
    assert.deepEqual(res.json.players, []);
    assert.equal(remoteSeen.length, 0);
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
