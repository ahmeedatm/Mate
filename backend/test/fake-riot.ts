import fs from "node:fs";
import http from "node:http";
import type { AddressInfo } from "node:net";
import os from "node:os";
import path from "node:path";
import zlib from "node:zlib";

/**
 * Faux client Riot (API locale, lockfile) et faux serveurs Riot (glz, pd) et valorant-api.com, sur la boucle locale.
 * `scene` fixe la partie ; `seen` garde chaque appel distant. Partagé par les tests Valorant.
 */

export const ME = "puuid-moi";
export const PASSWORD = "mot-de-passe-du-client";
export const ACCESS = "jeton-riot-secret";
export const ENTITLEMENT = "droits-riot-secrets";
export const SOVA = "320b2a48-4d9b-a075-30f1-1f93a9b638fa";
export const SAGE = "569fdd95-4d10-43ab-ca70-79becc718b46";
export const JETT = "add6443a-41bd-e414-f6ad-e58d267f4e95";
export const TIERS_UUID = "03621f52-342b-cf4e-4f86-9350a49c6d04";
export const ACT_NOW = "acte-en-cours";
/** Acte d'avant Ascendant (avant juin 2022) : ses rangs au-dessus de Diamant 3 se décalent de 3. */
export const ACT_OLD = "acte-2021";

export type Scene = {
  loop: string;
  partyState: string;
  queueId: string;
  /** Client lancé mais pas connecté à un compte : pas de jeton. */
  signedIn: boolean;
  /** Un joueur de plus dans la partie, dont le mmr répond 503 (pd qui limite les appels). */
  withNewcomer: boolean;
  /** Appels à match-details de la partie jeu-1 refusés (404) avant qu'elle soit prête ; -1 : jamais prête. */
  endNotReady: number;
  /** Historique de l'adversaire refusé (429). */
  historyRefused: boolean;
  /** Tous les appels mmr refusés (429). */
  mmrRefused: boolean;
  /** Mes RR, s'ils changent (fin de partie). */
  myRr: number | null;
};

const freshScene = (): Scene => ({
  loop: "MENUS",
  partyState: "DEFAULT",
  queueId: "competitive",
  signedIn: true,
  withNewcomer: false,
  endNotReady: 0,
  historyRefused: false,
  mmrRefused: false,
  myRr: null,
});

export type Seen = { url: string; auth?: string; entitlements?: string; version?: string };

/** PNG RGBA 8 bits de `size` px, d'une couleur, filtre 0 sur chaque ligne. */
export function pngOf(size: number, rgba = [200, 30, 60, 255]): Buffer {
  const crc = (b: Buffer) => {
    const out = Buffer.alloc(4);
    out.writeUInt32BE(zlib.crc32(b));
    return out;
  };
  const chunk = (type: string, data: Buffer) => {
    const len = Buffer.alloc(4);
    len.writeUInt32BE(data.length);
    const body = Buffer.concat([Buffer.from(type, "ascii"), data]);
    return Buffer.concat([len, body, crc(body)]);
  };
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(size, 0);
  ihdr.writeUInt32BE(size, 4);
  ihdr[8] = 8;
  ihdr[9] = 6;
  const row = Buffer.concat([Buffer.from([0]), Buffer.from(Array.from({ length: size }, () => rgba).flat())]);
  const raw = Buffer.concat(Array.from({ length: size }, () => row));
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk("IHDR", ihdr),
    chunk("IDAT", zlib.deflateSync(raw)),
    chunk("IEND", Buffer.alloc(0)),
  ]);
}

/** Présence Valorant d'un joueur, telle que le client la donne (`private` en base64). */
function presence(puuid: string, scene: Scene, partyId: string, roster: Roster) {
  return {
    puuid,
    product: "valorant",
    private: Buffer.from(
      JSON.stringify({
        matchPresenceData: { sessionLoopState: scene.loop, matchMap: roster.map, queueId: scene.queueId },
        partyPresenceData: { partyState: scene.partyState, partyId, partyClientVersion: "release-13.09-shipping-18-5804228" },
        playerPresenceData: { competitiveTier: roster.ranks[ME]?.[0] ?? 0, accountLevel: 90 },
        partyOwnerMatchScoreAllyTeam: roster.score[0],
        partyOwnerMatchScoreEnemyTeam: roster.score[1],
        queueId: scene.queueId,
      }),
    ).toString("base64"),
  };
}

export type Line = {
  puuid: string;
  team: string;
  agent: string;
  tier: number;
  k: number;
  d: number;
  a: number;
  score: number;
  head: number;
  body: number;
  damage: number;
  fk: number;
};

const TEST_NAMES: Record<string, [string, string]> = {
  [ME]: ["Eliot", "EUW"],
  ami: ["Kinj", "EUW"],
  cache: ["Secret", "000"],
  ennemi: ["Adversaire", "1"],
};

/** Détail d'une partie terminée, au format de match-details : premiers kills répartis dans les premières manches. */
export function matchOf(
  id: string,
  queueID: string,
  rounds: number,
  won: string | null,
  scoreBlue: number,
  lines: Line[],
  names = TEST_NAMES,
  mapId = "/Game/Maps/Plummet/Plummet",
) {
  let next = 0;
  const roundResults = Array.from({ length: rounds }, (_, i) => ({ roundNum: i, playerStats: [] as any[] }));
  for (const l of lines) {
    const stats: any = {
      subject: l.puuid,
      kills: [],
      damage: [{ receiver: "x", damage: l.damage, headshots: l.head, bodyshots: l.body, legshots: 0 }],
    };
    roundResults[0].playerStats.push(stats);
    for (let f = 0; f < l.fk; f++, next++) {
      const r = roundResults[next + 1];
      r.playerStats.push({ subject: l.puuid, kills: [{ killer: l.puuid, victim: "y", roundTime: 1000 }], damage: [] });
      r.playerStats.push({ subject: "z", kills: [{ killer: "z", victim: "w", roundTime: 5000 }], damage: [] });
    }
  }
  return {
    matchInfo: { matchId: id, mapId, queueID },
    players: lines.map((l) => ({
      subject: l.puuid,
      gameName: names[l.puuid]?.[0] ?? "",
      tagLine: names[l.puuid]?.[1] ?? "",
      teamId: l.team,
      characterId: l.agent.toUpperCase(),
      competitiveTier: l.tier,
      stats: { score: l.score, roundsPlayed: rounds, kills: l.k, deaths: l.d, assists: l.a },
    })),
    teams:
      queueID === "deathmatch"
        ? lines.map((l) => ({ teamId: l.puuid, won: false, roundsWon: l.k }))
        : [
            { teamId: "Blue", won: won === "Blue", roundsWon: scoreBlue },
            { teamId: "Red", won: won === "Red", roundsWon: rounds - scoreBlue },
          ],
    roundResults,
  };
}

export const line = (puuid: string, team: string, agent: string, tier: number, o: Partial<Line>): Line => ({
  puuid,
  team,
  agent,
  tier,
  k: 0,
  d: 0,
  a: 0,
  score: 0,
  head: 0,
  body: 0,
  damage: 0,
  fk: 0,
  ...o,
});

/**
 * Parties : m1 à m3 (historique récent de chacun), m4 et m5 (mes 5 dernières), jeu-1 (la partie suivie, 13 à 7).
 * Mes 3 dernières : 30 kills, 25 morts (K/D 1,2), 15 têtes sur 60 tirs touchés (25 %).
 */
const TEST_MATCHES: Record<string, () => unknown> = {
  m1: () =>
    matchOf("m1", "competitive", 20, "Blue", 13, [
      line(ME, "Blue", SOVA, 10, { k: 20, d: 10, a: 4, score: 5000, head: 10, body: 30 }),
      line("ennemi", "Red", JETT, 18, { k: 5, d: 20, a: 1, score: 2000, head: 1, body: 9 }),
    ]),
  m2: () =>
    matchOf("m2", "competitive", 22, "Red", 9, [line(ME, "Blue", SAGE, 10, { k: 10, d: 10, a: 2, score: 4400, head: 5, body: 15 })]),
  m3: () => matchOf("m3", "competitive", 24, null, 12, [line(ME, "Red", SOVA, 10, { k: 0, d: 5, a: 0, score: 1200 })]),
  m4: () => matchOf("m4", "competitive", 13, "Blue", 13, [line(ME, "Blue", SOVA, 9, { k: 15, d: 5, a: 3, score: 3900 })]),
  m5: () => matchOf("m5", "competitive", 13, "Red", 0, [line(ME, "Blue", JETT, 9, { k: 3, d: 13, a: 0, score: 1300 })]),
  "jeu-1": () =>
    matchOf("jeu-1", "competitive", 20, "Blue", 13, [
      line("ennemi", "Red", JETT, 18, { k: 25, d: 12, a: 2, score: 6000, head: 12, body: 28, damage: 3600, fk: 4 }),
      line(ME, "Blue", SOVA, 10, { k: 20, d: 10, a: 5, score: 5000, head: 10, body: 30, damage: 3000, fk: 3 }),
      line("cache", "Red", "inconnu", 9, { k: 8, d: 18, a: 3, score: 2000, head: 2, body: 18, damage: 1400 }),
      line("ami", "Blue", SAGE, 12, { k: 12, d: 11, a: 9, score: 3000, head: 3, body: 27, damage: 2000, fk: 1 }),
    ]),
  dm: () =>
    matchOf("dm", "deathmatch", 1, null, 0, [
      line(ME, "a", SOVA, 10, { k: 30, d: 20, score: 3000 }),
      line("ennemi", "b", JETT, 18, { k: 40, d: 10, score: 4000 }),
    ]),
};

/** Détail d'une partie du faux pd, pour tester la lecture sans serveur. */
export const matchFixture = (id: string) => TEST_MATCHES[id]();

const TEST_HISTORY: Record<string, string[]> = { [ME]: ["m1", "m2", "m3"], ennemi: ["m1"], ami: [], cache: ["m2"] };
const TEST_UPDATES = [
  ["m1", 18],
  ["m2", -15],
  ["m3", 2],
  ["m4", 21],
  ["m5", -19],
] as const;

const TEST_RANKS: Record<string, [number, number]> = { [ME]: [10, 42], ami: [12, 15], cache: [9, 97], ennemi: [18, 60] };
/** Saisons classées par joueur : [acte, victoires, parties, rangs gagnés]. */
const TEST_SEASONS: Record<string, SeasonRow[]> = {
  [ME]: [
    [ACT_NOW, 6, 10, { "10": 4, "11": 2 }],
    [ACT_OLD, 1, 2, { "21": 1 }],
  ],
  ennemi: [[ACT_NOW, 1, 4, { "18": 1, "20": 3 }]],
};

/** Saison classée d'un joueur : [acte, victoires, parties, victoires par rang]. */
export type SeasonRow = [string, number, number, Record<string, number>];

/** Joueurs et parties servis par les faux serveurs : celle des tests, ou une plus fournie pour les captures. */
export type Roster = {
  names: Record<string, [string, string]>;
  ranks: Record<string, [number, number]>;
  seasons: Record<string, SeasonRow[]>;
  history: Record<string, string[]>;
  matches: Record<string, () => unknown>;
  updates: readonly (readonly [string, number])[];
  map: string;
  /** Score de la partie en cours (mon équipe, l'autre). */
  score: [number, number];
  /** Joueurs de core-game (partie en cours) et de pregame (mon équipe pendant la sélection). */
  ingame: unknown[];
  pregame: unknown[];
  /** Groupe de chaque joueur dont le client voit la présence. */
  parties: Record<string, string>;
  /** Partie suivie (identifiant core-game, puis match-details). */
  matchId: string;
};

function mmrOf(roster: Roster, puuid: string) {
  const [tier, rr] = roster.ranks[puuid] ?? [0, 0];
  const seasonal = Object.fromEntries(
    (roster.seasons[puuid] ?? []).map(([id, wins, games, byTier]) => [
      id,
      { SeasonID: id, NumberOfWinsWithPlacements: wins, NumberOfGames: games, WinsByTier: byTier },
    ]),
  );
  return {
    LatestCompetitiveUpdate: { TierAfterUpdate: tier, RankedRatingAfterUpdate: rr },
    ...(roster.seasons[puuid] ? { QueueSkills: { competitive: { SeasonalInfoBySeasonID: seasonal } } } : {}),
  };
}

const TEST_ROSTER: Roster = {
  names: TEST_NAMES,
  ranks: TEST_RANKS,
  seasons: TEST_SEASONS,
  history: TEST_HISTORY,
  matches: TEST_MATCHES,
  updates: TEST_UPDATES,
  map: "/Game/Maps/Plummet/Plummet",
  score: [2, 0],
  matchId: "jeu-1",
  parties: { [ME]: "groupe-1", ami: "groupe-1", ennemi: "groupe-2" },
  pregame: [
    {
      Subject: ME,
      CharacterID: SOVA,
      CharacterSelectionState: "locked",
      CompetitiveTier: 10,
      PlayerIdentity: { Incognito: false, AccountLevel: 90 },
    },
    {
      Subject: "ami",
      CharacterID: "",
      CharacterSelectionState: "",
      CompetitiveTier: 12,
      PlayerIdentity: { Incognito: false, AccountLevel: 120 },
    },
  ],
  ingame: [
    { Subject: "ennemi", TeamID: "Red", CharacterID: JETT, PlayerIdentity: { Incognito: false, AccountLevel: 15 } },
    { Subject: ME, TeamID: "Blue", CharacterID: SOVA, PlayerIdentity: { Incognito: false, AccountLevel: 90 } },
    {
      Subject: "cache",
      TeamID: "Red",
      CharacterID: "inconnu",
      PlayerIdentity: { Incognito: true, AccountLevel: 200, HideAccountLevel: true },
    },
    { Subject: "ami", TeamID: "Blue", CharacterID: SAGE, PlayerIdentity: { Incognito: false, AccountLevel: 120 } },
  ],
};

const send = (res: http.ServerResponse, body: unknown, status = 200) => {
  res.statusCode = status;
  res.setHeader("content-type", "application/json");
  res.end(JSON.stringify(body));
};

const listen = (s: http.Server) => new Promise<number>((r) => s.listen(0, "127.0.0.1", () => r((s.address() as AddressInfo).port)));

export type FakeRiot = {
  scene: Scene;
  seen: Seen[];
  localAuth: string[];
  lockfile: string;
  /** Variables d'environnement du compagnon : serveurs Riot et valorant-api.com remplacés. */
  env: Record<string, string>;
  /** Remet la scène et les journaux à zéro (avant chaque test). */
  reset: () => void;
  count: (prefix: string) => number;
  close: () => void;
};

export async function startFakeRiot(roster: Roster = TEST_ROSTER): Promise<FakeRiot> {
  const fake = { scene: freshScene(), seen: [] as Seen[], localAuth: [] as string[] };
  let endCalls = 0;
  const scene = () => fake.scene;

  const client = http.createServer((req, res) => {
    fake.localAuth.push(String(req.headers.authorization));
    const s = scene();
    if (req.url === "/chat/v1/session") return send(res, { puuid: ME, game_name: "Eliot", game_tag: "EUW" });
    if (req.url === "/chat/v4/presences")
      return send(res, {
        presences: [
          { puuid: "un-ami", product: "league_of_legends", private: "" },
          ...Object.entries(roster.parties).map(([puuid, party]) => presence(puuid, s, party, roster)),
        ],
      });
    if (req.url === "/entitlements/v1/token") return send(res, s.signedIn ? { accessToken: ACCESS, token: ENTITLEMENT, subject: ME } : {});
    if (req.url === "/product-session/v1/external-sessions")
      return send(res, { x: { productId: "valorant", launchConfiguration: { arguments: ["-ares-deployment=eu", "-config-endpoint=x"] } } });
    send(res, { message: "inconnu" }, 404);
  });

  let assetsBase = "";
  const servers = http.createServer((req, res) => {
    let raw = "";
    req.on("data", (c) => (raw += c));
    req.on("end", () => {
      const s = scene();
      const url = req.url ?? "";
      fake.seen.push({
        url,
        auth: req.headers.authorization,
        entitlements: req.headers["x-riot-entitlements-jwt"] as string,
        version: req.headers["x-riot-clientversion"] as string,
      });
      // valorant-api.com
      if (url.startsWith("/v1/agents"))
        return send(res, {
          data: [SOVA, SAGE, JETT].map((id, i) => ({
            uuid: id,
            displayName: ["Sova", "Sage", "Jett"][i],
            displayIcon: `${assetsBase}/agents/${id}/displayicon.png`,
          })),
        });
      if (url === "/v1/competitivetiers")
        return send(res, {
          data: [
            { uuid: "ancien", tiers: [] },
            {
              uuid: TIERS_UUID,
              tiers: Array.from({ length: 28 }, (_, tier) => ({
                tier,
                smallIcon: `${assetsBase}/competitivetiers/${TIERS_UUID}/${tier}/smallicon.png`,
              })),
            },
          ],
        });
      if (url === "/v1/seasons") {
        const day = 86_400_000;
        return send(res, {
          data: [
            { uuid: ACT_OLD, type: "EAresSeasonType::Act", startTime: "2021-01-12T00:00:00Z", endTime: "2021-03-02T00:00:00Z" },
            {
              uuid: "episode",
              type: null,
              startTime: new Date(Date.now() - day).toISOString(),
              endTime: new Date(Date.now() + 30 * day).toISOString(),
            },
            {
              uuid: ACT_NOW,
              type: "EAresSeasonType::Act",
              startTime: new Date(Date.now() - day).toISOString(),
              endTime: new Date(Date.now() + 30 * day).toISOString(),
            },
          ],
        });
      }
      if (/^\/agents\/[^/]+\/displayicon\.png$/.test(url)) return res.end(pngOf(1024));
      if (/^\/competitivetiers\/[^/]+\/\d+\/smallicon\.png$/.test(url)) return res.end(pngOf(256, [20, 200, 120, 255]));

      // glz
      if (url === `/pregame/v1/players/${ME}`) return s.loop === "PREGAME" ? send(res, { MatchID: "pre-1" }) : send(res, {}, 404);
      if (url === "/pregame/v1/matches/pre-1") return send(res, { MapID: roster.map, AllyTeam: { Players: roster.pregame } });
      if (url === `/core-game/v1/players/${ME}`) return s.loop === "INGAME" ? send(res, { MatchID: roster.matchId }) : send(res, {}, 404);
      if (url === `/core-game/v1/matches/${roster.matchId}`)
        return send(res, {
          MapID: roster.map,
          Players: [
            ...roster.ingame,
            ...(s.withNewcomer ? [{ Subject: "nouveau", TeamID: "Red", CharacterID: JETT, PlayerIdentity: { Incognito: false } }] : []),
          ],
        });

      // pd
      if (url === "/name-service/v2/players" && req.method === "PUT")
        return send(
          res,
          (JSON.parse(raw) as string[]).map((p) => ({
            Subject: p,
            GameName: roster.names[p]?.[0] ?? "",
            TagLine: roster.names[p]?.[1] ?? "",
          })),
        );
      const updates = url.match(/^\/mmr\/v1\/players\/([^/?]+)\/competitiveupdates\?/);
      if (updates)
        return send(res, {
          Matches:
            updates[1] === ME
              ? roster.updates.map(([MatchID, RankedRatingEarned]) => ({ MatchID, MapID: roster.map, RankedRatingEarned }))
              : [],
        });
      const mmr = url.match(/^\/mmr\/v1\/players\/([^/?]+)$/);
      if (mmr) {
        if (mmr[1] === "nouveau" || s.mmrRefused) return send(res, { message: "trop d'appels" }, mmr[1] === "nouveau" ? 503 : 429);
        const body = mmrOf(roster, mmr[1]);
        if (mmr[1] === ME && s.myRr !== null) body.LatestCompetitiveUpdate.RankedRatingAfterUpdate = s.myRr;
        return send(res, body);
      }
      const history = url.match(/^\/match-history\/v1\/history\/([^/?]+)\?/);
      if (history) {
        if (history[1] === "ennemi" && s.historyRefused) return send(res, { message: "trop d'appels" }, 429);
        return send(res, { History: (roster.history[history[1]] ?? []).map((MatchID) => ({ MatchID })) });
      }
      const details = url.match(/^\/match-details\/v1\/matches\/([^/?]+)$/);
      if (details && roster.matches[details[1]]) {
        if (details[1] === roster.matchId && (s.endNotReady < 0 || endCalls++ < s.endNotReady)) return send(res, {}, 404);
        return send(res, roster.matches[details[1]]());
      }
      send(res, { message: "inconnu" }, 404);
    });
  });

  const localPort = await listen(client);
  const remotePort = await listen(servers);
  const remote = `http://127.0.0.1:${remotePort}`;
  assetsBase = remote;
  const lockfile = path.join(fs.mkdtempSync(path.join(os.tmpdir(), "riot-")), "lockfile");
  fs.writeFileSync(lockfile, `Riot Client:1234:${localPort}:${PASSWORD}:http`);
  return {
    get scene() {
      return fake.scene;
    },
    get seen() {
      return fake.seen;
    },
    get localAuth() {
      return fake.localAuth;
    },
    lockfile,
    env: {
      VALORANT_GLZ_URL: remote,
      VALORANT_PD_URL: remote,
      VALORANT_ASSETS_URL: remote,
      VALORANT_GAP_MS: "0",
      VALORANT_RETRY_MS: "20",
      VALORANT_END_MS: "1500",
    },
    reset() {
      fake.scene = freshScene();
      fake.seen = [];
      fake.localAuth = [];
      endCalls = 0;
    },
    count: (prefix) => fake.seen.filter((s) => s.url.startsWith(prefix)).length,
    close() {
      client.close();
      servers.close();
    },
  };
}
