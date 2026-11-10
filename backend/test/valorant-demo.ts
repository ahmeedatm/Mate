/**
 * Compagnon de démonstration du widget Valorant, pour les captures sur émulateur : le vrai serveur, branché sur les faux
 * serveurs Riot (test/fake-riot.ts) avec une partie figée de 10 joueurs ; logos et icônes de valorant-api.com (réels).
 * DEMO_PORT (3930) : le compagnon (jeton `jeton-de-test`), joint par `adb reverse`.
 * DEMO_CONTROL_PORT (3931) : `GET /scene/menu|pregame|ingame|end` change l'état montré (end : tableau de fin, 1 h).
 * Outil de développement : jamais lancé par l'appli ni par la CI.
 */
import http from "node:http";
import { ME, SAGE, SOVA, JETT, line, matchOf, startFakeRiot, type Line, type RoundEnd, type Roster, type SeasonRow } from "./fake-riot.ts";
import { startServer, TOKEN } from "./server.ts";

const OMEN = "8e253930-4c05-31dd-1b6c-968525494517";
const REYNA = "a3bfb853-43b2-7238-a4f1-ad90e9e46bcc";
const RAZE = "f94c3b30-42be-e959-889c-5aa313dba261";
const SKYE = "6f2a04ca-43e0-be17-7f36-b3908627744d";
const CYPHER = "117ed9e3-49f3-6512-3ccf-0cada7e3823b";
const BRIMSTONE = "9f0d8ba9-4140-b941-57d3-a7ad57c6b417";
const KILLJOY = "1e58de9c-4950-5125-93e9-a0aee9f98746";

type Demo = {
  puuid: string;
  name: [string, string];
  team: "Blue" | "Red";
  agent: string;
  tier: number;
  rr: number;
  level: number;
  party?: string;
  incognito?: boolean;
  /** Acte en cours : victoires, parties ; meilleur rang (un acte d'avant Ascendant s'il est marqué `old`). */
  act: [number, number];
  peak: number;
  oldPeak?: boolean;
  /** 3 dernières classées : K/D et % tête. */
  kd: number;
  hs: number;
  /** Tableau de fin : kills, morts, assists, ACS, % tête, ADR, premiers kills. */
  end: [number, number, number, number, number, number, number];
};

const PLAYERS: Demo[] = [
  {
    puuid: ME,
    name: ["Eliot", "EUW"],
    team: "Blue",
    agent: SOVA,
    tier: 14,
    rr: 42,
    level: 214,
    party: "A",
    act: [14, 25],
    peak: 17,
    kd: 1.14,
    hs: 24,
    end: [19, 15, 6, 231, 26, 148, 3],
  },
  {
    puuid: "kinj",
    name: ["Kinj", "EUW"],
    team: "Blue",
    agent: SAGE,
    tier: 15,
    rr: 61,
    level: 187,
    party: "A",
    act: [20, 34],
    peak: 16,
    kd: 0.87,
    hs: 18,
    end: [12, 16, 11, 168, 19, 112, 1],
  },
  {
    puuid: "nox",
    name: ["Nox", "7781"],
    team: "Blue",
    agent: JETT,
    tier: 19,
    rr: 12,
    level: 36,
    act: [9, 11],
    peak: 21,
    oldPeak: true,
    kd: 1.62,
    hs: 31,
    end: [26, 12, 4, 312, 33, 197, 6],
  },
  {
    puuid: "melo",
    name: ["Mélodie", "FR1"],
    team: "Blue",
    agent: OMEN,
    tier: 13,
    rr: 88,
    level: 402,
    act: [30, 61],
    peak: 14,
    kd: 0.98,
    hs: 21,
    end: [14, 17, 7, 182, 22, 121, 2],
  },
  {
    puuid: "ghost",
    name: ["Fantôme", "000"],
    team: "Blue",
    agent: KILLJOY,
    tier: 11,
    rr: 30,
    level: 77,
    incognito: true,
    act: [4, 9],
    peak: 12,
    kd: 0.79,
    hs: 15,
    end: [9, 18, 5, 124, 14, 86, 0],
  },
  {
    puuid: "lune",
    name: ["Lune", "2402"],
    team: "Red",
    agent: REYNA,
    tier: 21,
    rr: 7,
    level: 22,
    act: [12, 13],
    peak: 25,
    kd: 1.95,
    hs: 34,
    end: [24, 16, 3, 289, 35, 182, 5],
  },
  {
    puuid: "bastos",
    name: ["Bastos", "EUW"],
    team: "Red",
    agent: RAZE,
    tier: 16,
    rr: 70,
    level: 156,
    party: "B",
    act: [18, 33],
    peak: 17,
    kd: 1.21,
    hs: 22,
    end: [17, 17, 5, 216, 21, 139, 2],
  },
  {
    puuid: "gigi",
    name: ["Gigi", "Ok"],
    team: "Red",
    agent: SKYE,
    tier: 15,
    rr: 44,
    level: 98,
    party: "B",
    act: [11, 25],
    peak: 15,
    kd: 0.74,
    hs: 16,
    end: [10, 19, 9, 141, 17, 95, 1],
  },
  {
    puuid: "pdc",
    name: ["PasDeChance", "404"],
    team: "Red",
    agent: CYPHER,
    tier: 12,
    rr: 95,
    level: 311,
    act: [26, 50],
    peak: 14,
    kd: 1.03,
    hs: 23,
    end: [13, 18, 4, 170, 24, 108, 0],
  },
  {
    puuid: "coco",
    name: ["Corentin le Grand Sauvageon", "EUW"],
    team: "Red",
    agent: BRIMSTONE,
    tier: 17,
    rr: 33,
    level: 260,
    act: [21, 40],
    peak: 18,
    kd: 0.91,
    hs: 19,
    end: [11, 19, 8, 152, 18, 101, 1],
  },
];

const MAP = "/Game/Maps/Ascent/Ascent";
/** Actes réels de valorant-api.com (les images et la liste des actes viennent du vrai service) : l'acte VI de 2026 et le premier de 2020. */
const ACT_NOW = "d816f426-48ea-f052-117f-9697a155b319";
const ACT_OLD = "3f61c772-4560-cd3f-5d3f-a7ab5abda6b3";
const names = Object.fromEntries(PLAYERS.map((p) => [p.puuid, p.name]));
const ROUNDS = 22;
const endLine = (p: Demo): Line => {
  const [k, d, a, acs, hs, adr, fk] = p.end;
  return line(p.puuid, p.team, p.agent, p.tier, { k, d, a, score: acs * ROUNDS, head: hs, body: 100 - hs, damage: adr * ROUNDS, fk });
};
/** 3 dernières classées d'un joueur : tout dans la première (K/D et % tête se calculent sur la somme). */
const recentLine = (p: Demo): Line =>
  line(p.puuid, "Blue", p.agent, p.tier, { k: Math.round(p.kd * 45), d: 45, head: p.hs, body: 100 - p.hs });

/** Mes 5 dernières : carte, agent, mes manches, les leurs, K/D/A, ACS, RR. */
const MINE: [string, string, number, number, number, number, number, number, number][] = [
  ["Ascent", SOVA, 13, 9, 19, 15, 6, 231, 21],
  ["Duality", SOVA, 10, 13, 14, 17, 3, 187, -17],
  ["Triad", KILLJOY, 13, 5, 21, 8, 5, 284, 24],
  ["Jam", SOVA, 7, 13, 9, 16, 4, 142, -19],
  ["Bonsai", SAGE, 14, 12, 16, 14, 12, 201, 18],
];

/** Les 22 rounds de la partie (13 à 9, mon équipe en Blue) et leur fin : la frise du tableau de fin. */
const DEMO_ROUNDS: RoundEnd[] = (
  [
    ["W", "Detonate"],
    ["W", "Elimination"],
    ["L", "Defuse"],
    ["W", "Elimination"],
    ["L", "Elimination"],
    ["L", ""],
    ["W", "Detonate"],
    ["W", "Elimination"],
    ["W", "Detonate"],
    ["L", "Defuse"],
    ["W", "Elimination"],
    ["L", "Elimination"],
    ["W", "Defuse"],
    ["L", "Detonate"],
    ["W", "Elimination"],
    ["W", ""],
    ["L", "Elimination"],
    ["W", "Defuse"],
    ["W", "Elimination"],
    ["L", "Detonate"],
    ["W", "Elimination"],
    ["L", "Elimination"],
  ] as const
).map(([w, code]) => [w === "W" ? "Blue" : "Red", code]);
/** Score de la partie en cours, round après round (7 à 5) : la frise se déduit comme en vrai. */
const LIVE = "WWLWLLWWWLWL";

const matches: Record<string, () => unknown> = {
  "partie-demo": () => matchOf("partie-demo", "competitive", ROUNDS, "Blue", 13, PLAYERS.map(endLine), names, MAP, DEMO_ROUNDS),
};
for (const p of PLAYERS) {
  matches[`r-${p.puuid}`] = () => matchOf(`r-${p.puuid}`, "competitive", 24, "Blue", 13, [recentLine(p)], names, MAP);
}
MINE.forEach(([map, agent, mine, theirs, k, d, a, acs], i) => {
  const rounds = mine + theirs;
  matches[`h${i}`] = () =>
    matchOf(
      `h${i}`,
      "competitive",
      rounds,
      mine > theirs ? "Blue" : "Red",
      mine,
      [line(ME, "Blue", agent, 14, { k, d, a, score: acs * rounds })],
      names,
      `/Game/Maps/${map}/${map}`,
    );
});

const seasons = (p: Demo): SeasonRow[] => [
  [ACT_NOW, p.act[0], p.act[1], { [String(p.tier)]: p.act[0] }],
  [ACT_OLD, 1, 2, { [String(p.peak)]: 1 }],
];
// Les rangs d'avant Ascendant (acte 2021) au-dessus de 20 sont relus +3 : on y range un meilleur rang ancien, sinon un récent.
const seasonsOf = (p: Demo): SeasonRow[] =>
  p.oldPeak || p.peak <= 20 ? seasons(p) : [[ACT_NOW, p.act[0], p.act[1], { [String(p.tier)]: p.act[0], [String(p.peak)]: 1 }]];

const identity = (p: Demo) => ({ Incognito: !!p.incognito, AccountLevel: p.level });
const roster: Roster = {
  names,
  ranks: Object.fromEntries(PLAYERS.map((p) => [p.puuid, [p.tier, p.rr]])),
  seasons: Object.fromEntries(PLAYERS.map((p) => [p.puuid, seasonsOf(p)])),
  history: Object.fromEntries(PLAYERS.map((p) => [p.puuid, [`r-${p.puuid}`]])),
  matches,
  // Mes 5 dernières (4 aujourd'hui), puis 20 plus anciennes : de quoi un bilan sur Ascent.
  updates: [
    ...MINE.map(
      ([map, , , , , , , , rr], i) =>
        [
          i === 0 ? "partie-demo" : `h${i}`,
          rr,
          `/Game/Maps/${map}/${map}`,
          Date.now() - (i < 4 ? (i + 1) * 50 * 60_000 : 30 * 3_600_000),
        ] as const,
    ),
    ...Array.from({ length: 20 }, (_, i) => {
      const map = MINE[i % MINE.length][0];
      return [`old-${i}`, i % 3 === 1 ? -16 : 19, `/Game/Maps/${map}/${map}`, Date.now() - (i + 2) * 86_400_000] as const;
    }),
  ],
  map: MAP,
  score: [7, 5],
  matchId: "partie-demo",
  parties: Object.fromEntries(PLAYERS.map((p) => [p.puuid, p.party ?? `seul-${p.puuid}`])),
  ingame: PLAYERS.map((p) => ({ Subject: p.puuid, TeamID: p.team, CharacterID: p.agent, PlayerIdentity: identity(p) })),
  pregame: PLAYERS.filter((p) => p.team === "Blue").map((p, i) => ({
    Subject: p.puuid,
    // Un agent survolé (pas verrouillé), un pas encore choisi.
    CharacterID: i === 3 ? "" : p.agent,
    CharacterSelectionState: i === 1 || i === 3 ? "" : "locked",
    CompetitiveTier: p.tier,
    PlayerIdentity: identity(p),
  })),
};

const riot = await startFakeRiot(roster);
const { VALORANT_ASSETS_URL: _real, ...env } = riot.env;
const port = Number(process.env.DEMO_PORT || 3930);
const server = await startServer({ os: "windows", port, env: { ...env, VALORANT_END_MS: "3600000", VALORANT_GAP_MS: "50" } });
server.setOs({ riotLockfile: riot.lockfile });
const read = () => server.call("GET", "/valorant");

const scenes: Record<string, () => Promise<void>> = {
  async menu() {
    // Une file coupe le tableau de fin.
    riot.scene.partyState = "MATCHMAKING";
    await read();
    riot.scene.partyState = "DEFAULT";
    riot.scene.loop = "MENUS";
  },
  async pregame() {
    riot.scene.loop = "PREGAME";
  },
  async ingame() {
    // Partie suivie dès 0 – 0 : le compagnon voit chaque round passer.
    riot.scene.loop = "INGAME";
    const score: [number, number] = [0, 0];
    riot.scene.score = [...score];
    await read();
    for (const r of LIVE) {
      score[r === "W" ? 0 : 1]++;
      riot.scene.score = [...score];
      await read();
    }
  },
  async end() {
    riot.scene.loop = "INGAME";
    await read();
    riot.scene.loop = "MENUS";
  },
};
const control = http.createServer(async (req, res) => {
  const scene = scenes[(req.url ?? "").replace("/scene/", "")];
  if (!scene) return res.writeHead(404).end("scènes : menu, pregame, ingame, end\n");
  await scene();
  await read();
  res.end(`ok ${JSON.stringify((await read()).json.state)}\n`);
});
control.listen(Number(process.env.DEMO_CONTROL_PORT || 3931), "127.0.0.1");
await read();
// Plugins déjà choisis : la tablette va droit à l'Accueil.
const plugins = (await server.call("GET", "/plugins")).json.plugins as { id: string }[];
await server.call("POST", "/plugins", { choices: Object.fromEntries(plugins.map((p) => [p.id, true])) });
console.log(`Compagnon de démo Valorant : http://127.0.0.1:${port} (jeton ${TOKEN}), id ${(await server.call("GET", "/health")).json.id}`);

const stop = async () => {
  control.close();
  await server.stop();
  riot.close();
  process.exit(0);
};
process.on("SIGINT", stop);
process.on("SIGTERM", stop);
