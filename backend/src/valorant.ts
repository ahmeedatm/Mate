import fs from "node:fs/promises";
import http from "node:http";
import https from "node:https";
import { riotLockfile } from "./os.ts";
import { singleFlight } from "./single-flight.ts";

/**
 * Widget Valorant de la tablette : état de la partie, mon rang, et rang, agent et nom de chaque joueur de la partie.
 * Lu dans le client Riot de l'ordinateur (API locale, port et mot de passe du « lockfile ») puis, avec le jeton qu'il
 * donne, sur les serveurs Riot de la région (glz : partie en cours, pd : rangs et noms). API non officielle, sans clé :
 * celle qu'utilisent les trackers. Le jeton Riot reste sur l'ordinateur ; la tablette ne reçoit que le résultat.
 */

export type ValorantPlayer = {
  /** « Nom#TAG » ; null pour un joueur incognito (sauf moi). */
  name: string | null;
  agent: string | null;
  /** Agent verrouillé (toujours vrai en partie). */
  locked: boolean;
  tier: number | null;
  rank: string | null;
  rr: number | null;
  ally: boolean;
  me: boolean;
};

export type ValorantState = {
  /** off : client Riot ou Valorant fermé ; queue : en file ; pregame : sélection d'agents ; ingame : en partie. */
  state: "off" | "menu" | "queue" | "pregame" | "ingame";
  queue: string | null;
  map: string | null;
  score: { ally: number; enemy: number } | null;
  me: { name: string; tier: number; rank: string; rr: number | null; level: number | null } | null;
  /** Mon équipe d'abord, puis l'adverse (inconnue pendant la sélection), chacune du meilleur rang au plus bas. */
  players: ValorantPlayer[];
};

const OFF: ValorantState = { state: "off", queue: null, map: null, score: null, me: null, players: [] };

/** Agents par identifiant (valorant-api.com, noms français ; un agent absent d'ici s'affiche sans nom). */
const AGENTS: Record<string, string> = {
  "e370fa57-4757-3604-3648-499e1f642d3f": "Gekko",
  "dade69b4-4f5a-8528-247b-219e5a1facd6": "Fade",
  "5f8d3a7f-467b-97f3-062c-13acf203c006": "Breach",
  "cc8b64c8-4b25-4ff9-6e7f-37b4da43d235": "Deadlock",
  "b444168c-4e35-8076-db47-ef9bf368f384": "Tejo",
  "f94c3b30-42be-e959-889c-5aa313dba261": "Raze",
  "22697a3d-45bf-8dd7-4fec-84a9e28c69d7": "Chamber",
  "601dbbe7-43ce-be57-2a40-4abd24953621": "KAY/O",
  "6f2a04ca-43e0-be17-7f36-b3908627744d": "Skye",
  "117ed9e3-49f3-6512-3ccf-0cada7e3823b": "Cypher",
  "320b2a48-4d9b-a075-30f1-1f93a9b638fa": "Sova",
  "7c8a4701-4de6-9355-b254-e09bc2a34b72": "Miks",
  "1e58de9c-4950-5125-93e9-a0aee9f98746": "Killjoy",
  "95b78ed7-4637-86d9-7e41-71ba8c293152": "Harbor",
  "efba5359-4016-a1e5-7626-b1ae76895940": "Vyse",
  "707eab51-4836-f488-046a-cda6bf494859": "Viper",
  "ddd5a15c-4fbe-42fa-3789-11a55ca5ee04": "Hush",
  "eb93336a-449b-9c1b-0a54-a891f7921d69": "Phoenix",
  "92eeef5d-43b5-1d4a-8d03-b3927a09034b": "Veto",
  "41fb69c1-4189-7b37-f117-bcaf1e96f1bf": "Astra",
  "9f0d8ba9-4140-b941-57d3-a7ad57c6b417": "Brimstone",
  "0e38b510-41a8-5780-5e8f-568b2a4f2d6c": "Iso",
  "1dbf2edd-4729-0984-3115-daa5eed44993": "Clove",
  "bb2a4828-46eb-8cd1-e765-15848195d751": "Neon",
  "7f94d92c-4234-0a36-9646-3a87eb8b5c89": "Yoru",
  "df1cb487-4902-002e-5c17-d28e83e78588": "Waylay",
  "569fdd95-4d10-43ab-ca70-79becc718b46": "Sage",
  "a3bfb853-43b2-7238-a4f1-ad90e9e46bcc": "Reyna",
  "8e253930-4c05-31dd-1b6c-968525494517": "Omen",
  "add6443a-41bd-e414-f6ad-e58d267f4e95": "Jett",
};

/** Cartes par dossier du jeu (`/Game/Maps/<dossier>/…`). */
const MAPS: Record<string, string> = {
  Ascent: "Ascent",
  Bonsai: "Split",
  Canyon: "Fracture",
  Duality: "Bind",
  Foxtrot: "Breeze",
  Infinity: "Abyss",
  Jam: "Lotus",
  Juliett: "Sunset",
  Pitt: "Pearl",
  Plummet: "Summit",
  Port: "Icebox",
  Rook: "Corrode",
  Triad: "Haven",
  HURM: "Combat à mort par équipe",
  Duel: "Escarmouche",
  Poveglia: "Entraînement",
  PovegliaV2: "Entraînement",
};

const QUEUES: Record<string, string> = {
  competitive: "Compétition",
  unrated: "Non classé",
  swiftplay: "Vélocité",
  spikerush: "Spike Rush",
  deathmatch: "Combat à mort",
  hurm: "Combat à mort par équipe",
  ggteam: "Escalade",
  premier: "Premier",
  newmap: "Nouvelle carte",
  "": "Personnalisée",
};

const DIVISIONS = ["Fer", "Bronze", "Argent", "Or", "Platine", "Diamant", "Ascendant", "Immortel"];

/** Nom du rang : 0 à 2 non classé, 3 à 26 par trois (Fer 1… Immortel 3), 27 Radiant. */
export function rankName(tier: number): string {
  if (tier >= 27) return "Radiant";
  if (tier < 3) return "Non classé";
  return `${DIVISIONS[Math.floor((tier - 3) / 3)]} ${((tier - 3) % 3) + 1}`;
}

export const mapName = (url: string | undefined) => (url ? (MAPS[url.split("/")[3] ?? ""] ?? null) : null);

/** Région du client (`-ares-deployment=`) → serveurs glz et pd. */
const SHARDS: Record<string, { region: string; shard: string }> = {
  eu: { region: "eu", shard: "eu" },
  na: { region: "na", shard: "na" },
  latam: { region: "latam", shard: "na" },
  br: { region: "br", shard: "na" },
  ap: { region: "ap", shard: "ap" },
  kr: { region: "kr", shard: "kr" },
};

/** En-tête de plateforme attendu par les serveurs Riot (celui du client PC). */
const PLATFORM = Buffer.from(
  JSON.stringify({ platformType: "PC", platformOS: "Windows", platformOSVersion: "10.0.19042.1.256.64bit", platformChipset: "Unknown" }),
).toString("base64");

class RiotError extends Error {
  constructor(readonly status: number) {
    super(`Riot a répondu ${status}`);
  }
}

type Lock = { port: number; password: string; protocol: string };

async function readLock(): Promise<Lock | null> {
  const file = riotLockfile();
  if (!file) return null;
  const text = await fs.readFile(file, "utf8").catch(() => null);
  // nom:pid:port:mot de passe:protocole
  const [, , port, password, protocol] = text?.trim().split(":") ?? [];
  return port && password ? { port: Number(port), password, protocol: protocol === "http" ? "http" : "https" } : null;
}

/** API locale du client Riot : 127.0.0.1 seulement, certificat du client (auto-signé) accepté. */
function local(lock: Lock, route: string): Promise<any> {
  return new Promise((resolve, reject) => {
    const req = (lock.protocol === "http" ? http : https).request(
      {
        host: "127.0.0.1",
        port: lock.port,
        path: route,
        rejectUnauthorized: false,
        timeout: 3000,
        headers: { authorization: `Basic ${Buffer.from(`riot:${lock.password}`).toString("base64")}` },
      },
      (res) => {
        let raw = "";
        res.setEncoding("utf8");
        res.on("data", (c) => (raw += c));
        res.on("end", () => {
          if (res.statusCode !== 200) return reject(new RiotError(res.statusCode ?? 0));
          try {
            resolve(JSON.parse(raw));
          } catch (e) {
            reject(e);
          }
        });
      },
    );
    req.on("timeout", () => req.destroy(new Error("Client Riot muet")));
    req.on("error", reject);
    req.end();
  });
}

type Remote = { glz: string; pd: string; headers: Record<string, string> };

async function remote(r: Remote, url: string, init: RequestInit = {}): Promise<any> {
  const res = await fetch(url, { ...init, headers: { ...r.headers, ...init.headers }, signal: AbortSignal.timeout(5000) });
  if (!res.ok) throw new RiotError(res.status);
  return res.json();
}

/**
 * Rang et RR de chaque joueur, gardés 10 min (rien ne change pendant une partie, et pd limite les appels) ; un échec est
 * gardé 30 s (`rank` null), pour ne pas relancer dix appels refusés à chaque relecture.
 */
const ranks = new Map<string, { until: number; rank: { tier: number; rr: number } | null }>();
const RANK_TTL = 10 * 60_000;
const RANK_FAIL_TTL = 30_000;
/** Noms « Nom#TAG », gardés le temps du compagnon (un joueur change rarement de nom), 500 au plus. */
const names = new Map<string, string>();
const NAMES_MAX = 500;

async function rankOf(r: Remote, puuid: string): Promise<{ tier: number; rr: number } | null> {
  const now = Date.now();
  const known = ranks.get(puuid);
  if (known && now < known.until) return known.rank;
  for (const [id, entry] of ranks) if (now >= entry.until) ranks.delete(id);
  try {
    const mmr = await remote(r, `${r.pd}/mmr/v1/players/${puuid}`);
    const latest = mmr?.LatestCompetitiveUpdate ?? {};
    const rank = { tier: Number(latest.TierAfterUpdate) || 0, rr: Number(latest.RankedRatingAfterUpdate) || 0 };
    ranks.set(puuid, { until: Date.now() + RANK_TTL, rank });
    return rank;
  } catch {
    ranks.set(puuid, { until: Date.now() + RANK_FAIL_TTL, rank: null });
    return null;
  }
}

async function loadNames(r: Remote, puuids: string[]) {
  const missing = puuids.filter((p) => !names.has(p));
  if (missing.length === 0) return;
  if (names.size + missing.length > NAMES_MAX) names.clear();
  try {
    const list = await remote(r, `${r.pd}/name-service/v2/players`, {
      method: "PUT",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(missing),
    });
    for (const n of Array.isArray(list) ? list : []) if (n?.Subject && n.GameName) names.set(n.Subject, `${n.GameName}#${n.TagLine}`);
  } catch {
    // Noms absents : la tablette affiche le joueur sans nom.
  }
}

type RawPlayer = {
  Subject: string;
  CharacterID?: string;
  CharacterSelectionState?: string;
  CompetitiveTier?: number;
  TeamID?: string;
  PlayerIdentity?: { Incognito?: boolean };
};

async function players(r: Remote, me: string, list: RawPlayer[], myTeam: string | null, pregame: boolean): Promise<ValorantPlayer[]> {
  await loadNames(
    r,
    list.map((p) => p.Subject),
  );
  const out = await Promise.all(
    list.map(async (p): Promise<ValorantPlayer> => {
      const rank = await rankOf(r, p.Subject);
      const tier = rank?.tier ?? (typeof p.CompetitiveTier === "number" ? p.CompetitiveTier : null);
      const isMe = p.Subject === me;
      return {
        name: p.PlayerIdentity?.Incognito && !isMe ? null : (names.get(p.Subject) ?? null),
        agent: AGENTS[p.CharacterID?.toLowerCase() ?? ""] ?? null,
        locked: pregame ? p.CharacterSelectionState === "locked" : true,
        tier,
        rank: tier === null ? null : rankName(tier),
        rr: rank?.rr ?? null,
        ally: pregame || p.TeamID === myTeam,
        me: isMe,
      };
    }),
  );
  return out.sort((a, b) => Number(b.ally) - Number(a.ally) || (b.tier ?? -1) - (a.tier ?? -1));
}

async function read(): Promise<ValorantState> {
  const lock = await readLock();
  if (!lock) return OFF;
  let session: any, presences: any;
  try {
    session = await local(lock, "/chat/v1/session");
    presences = await local(lock, "/chat/v4/presences");
  } catch {
    // Client Riot fermé (vieux lockfile) ou pas encore prêt.
    return OFF;
  }
  const puuid: string = session?.puuid;
  const mine = (presences?.presences ?? []).find((p: any) => p.puuid === puuid && p.product === "valorant");
  if (!puuid || !mine?.private) return OFF;
  let priv: any;
  try {
    priv = JSON.parse(Buffer.from(mine.private, "base64").toString("utf8"));
  } catch {
    return OFF;
  }
  const loop: string = priv.matchPresenceData?.sessionLoopState ?? priv.sessionLoopState ?? "MENUS";
  const party = priv.partyPresenceData ?? {};
  const queueId: string = priv.queueId ?? priv.matchPresenceData?.queueId ?? "";
  const state: ValorantState["state"] =
    loop === "INGAME"
      ? "ingame"
      : loop === "PREGAME"
        ? "pregame"
        : (party.partyState ?? priv.partyState) === "MATCHMAKING"
          ? "queue"
          : "menu";

  const r = await remoteFor(lock, String(party.partyClientVersion ?? priv.partyClientVersion ?? ""));
  const level = Number(priv.playerPresenceData?.accountLevel ?? priv.accountLevel);
  const myRank = r ? await rankOf(r, puuid) : null;
  const myTier = myRank?.tier ?? Number(priv.playerPresenceData?.competitiveTier ?? priv.competitiveTier ?? 0);
  const me = {
    name: session.game_name ? `${session.game_name}#${session.game_tag}` : "",
    tier: myTier,
    rank: rankName(myTier),
    rr: myRank?.rr ?? null,
    level: Number.isFinite(level) && level > 0 ? level : null,
  };
  const result: ValorantState = { state, queue: QUEUES[queueId] ?? null, map: null, score: null, me, players: [] };
  if (state === "ingame")
    result.score = { ally: Number(priv.partyOwnerMatchScoreAllyTeam) || 0, enemy: Number(priv.partyOwnerMatchScoreEnemyTeam) || 0 };
  if (!r || (state !== "ingame" && state !== "pregame")) return result;
  result.map = mapName(priv.matchPresenceData?.matchMap);
  try {
    if (state === "pregame") {
      const { MatchID } = await remote(r, `${r.glz}/pregame/v1/players/${puuid}`);
      const match = await remote(r, `${r.glz}/pregame/v1/matches/${MatchID}`);
      result.map = mapName(match.MapID) ?? result.map;
      result.players = await players(r, puuid, match.AllyTeam?.Players ?? [], null, true);
    } else {
      const { MatchID } = await remote(r, `${r.glz}/core-game/v1/players/${puuid}`);
      const match = await remote(r, `${r.glz}/core-game/v1/matches/${MatchID}`);
      const list: RawPlayer[] = match.Players ?? [];
      result.map = mapName(match.MapID) ?? result.map;
      result.players = await players(r, puuid, list, list.find((p) => p.Subject === puuid)?.TeamID ?? null, false);
    }
  } catch {
    // Entre deux phases (la partie se termine, le jeu charge) : l'état seul, les joueurs reviennent au tour suivant.
  }
  return result;
}

/**
 * Serveur Riot : `VALORANT_GLZ_URL` / `VALORANT_PD_URL` remplacent les serveurs de Riot pour les tests (faux serveurs),
 * mais seulement vers la boucle locale : le jeton Riot ne part jamais vers une autre machine.
 */
export function riotServer(override: string | undefined, riot: string): string {
  if (!override) return riot;
  try {
    const host = new URL(override).hostname;
    return host === "127.0.0.1" || host === "localhost" ? override : riot;
  } catch {
    return riot;
  }
}

/** Jeton du client et serveurs de sa région ; null si le client ne les donne pas (pas connecté). */
async function remoteFor(lock: Lock, version: string): Promise<Remote | null> {
  try {
    const token = await local(lock, "/entitlements/v1/token");
    // Client lancé sans compte connecté : pas de jeton, rien ne part vers Riot.
    if (!token?.accessToken || !token.token) return null;
    const sessions = await local(lock, "/product-session/v1/external-sessions");
    const args: string[] =
      (Object.values(sessions ?? {}) as any[]).find((s) => s?.productId === "valorant")?.launchConfiguration?.arguments ?? [];
    const deployment = args.find((a) => a.startsWith("-ares-deployment="))?.split("=")[1] ?? "eu";
    const { region, shard } = SHARDS[deployment] ?? SHARDS.eu;
    return {
      glz: riotServer(process.env.VALORANT_GLZ_URL, `https://glz-${region}-1.${shard}.a.pvp.net`),
      pd: riotServer(process.env.VALORANT_PD_URL, `https://pd.${shard}.a.pvp.net`),
      headers: {
        authorization: `Bearer ${token.accessToken}`,
        "x-riot-entitlements-jwt": token.token,
        "x-riot-clientversion": version,
        "x-riot-clientplatform": PLATFORM,
      },
    };
  } catch {
    return null;
  }
}

/** Deux widgets (ou un widget agrandi) qui lisent en même temps : une seule lecture ; rien de gardé ensuite (rangs et noms ont leur cache). */
const flight = singleFlight<ValorantState>(0);

export function valorantState(): Promise<ValorantState> {
  return flight.get(read);
}
