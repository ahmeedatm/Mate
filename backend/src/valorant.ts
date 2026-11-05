import { singleFlight } from "./single-flight.ts";
import { agentId, agentName, refreshAssets } from "./valorant-assets.ts";
import { endOfMatch, watchMatch, type EndView } from "./valorant-end.ts";
import { QUEUES, mapName, rankName } from "./valorant-names.ts";
import { local, readLock, remote, remoteFor, type Remote } from "./valorant-riot.ts";
import { actWinRate, historyOf, peakTier, profileOf, recentOf, type HistoryEntry } from "./valorant-stats.ts";

/**
 * Widget Valorant de la tablette : état de la partie, mon rang, et pour chaque joueur de la partie son rang, son agent,
 * son nom et ses stats de tracker ; tableau des scores après la partie, mes 5 dernières classées au menu.
 * Lu dans le client Riot de l'ordinateur (API locale, port et mot de passe du « lockfile ») puis, avec le jeton qu'il
 * donne, sur les serveurs Riot de la région (glz : partie en cours, pd : rangs, noms, historique). API non officielle,
 * sans clé : celle qu'utilisent les trackers. Le jeton Riot reste sur l'ordinateur ; la tablette ne reçoit que le résultat.
 * Les champs ajoutés par la refonte du 5/11/2026 s'ajoutent aux anciens : une tablette pas à jour lit le même JSON.
 */

export type ValorantPlayer = {
  /** « Nom#TAG » ; null pour un joueur incognito (sauf moi). */
  name: string | null;
  agent: string | null;
  /** Identifiant de l'agent (icône : /valorant/agent?id=) ; null si pas encore choisi. */
  agentId: string | null;
  /** Agent verrouillé (toujours vrai en partie). */
  locked: boolean;
  tier: number | null;
  rank: string | null;
  rr: number | null;
  ally: boolean;
  me: boolean;
  /** Niveau du compte ; null si le joueur le cache. */
  level: number | null;
  peakTier: number | null;
  /** % de victoires de l'acte en cours. */
  winRate: number | null;
  /** K/D et % de tirs à la tête de ses 3 dernières classées ; null tant qu'ils ne sont pas lus. */
  kd: number | null;
  hs: number | null;
  /** Joueurs venus ensemble : même numéro (dans les deux équipes) ; null : seul. */
  party: number | null;
};

export type ValorantState = {
  /** off : client Riot ou Valorant fermé ; queue : en file ; pregame : sélection d'agents ; ingame : en partie. */
  state: "off" | "menu" | "queue" | "pregame" | "ingame";
  queue: string | null;
  map: string | null;
  score: { ally: number; enemy: number } | null;
  me: { name: string; tier: number; rank: string; rr: number | null; level: number | null; peakTier: number | null } | null;
  /** Mon équipe d'abord, puis l'adverse (inconnue pendant la sélection), chacune du meilleur rang au plus bas. */
  players: ValorantPlayer[];
  /** Au menu et en file : mes 5 dernières classées. */
  history: HistoryEntry[];
  /** Tableau des scores de la partie qui vient de finir (2 min). */
  end: EndView | null;
};

const OFF: ValorantState = { state: "off", queue: null, map: null, score: null, me: null, players: [], history: [], end: null };

/** Noms « Nom#TAG », gardés le temps du compagnon (un joueur change rarement de nom), 500 au plus. */
const names = new Map<string, string>();
const NAMES_MAX = 500;

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
  PlayerIdentity?: { Incognito?: boolean; AccountLevel?: number; HideAccountLevel?: boolean };
};

type Context = { me: string; myTeam: string | null; pregame: boolean; withStats: boolean; parties: Map<string, string> };

async function players(r: Remote, list: RawPlayer[], ctx: Context): Promise<ValorantPlayer[]> {
  await loadNames(
    r,
    list.map((p) => p.Subject),
  );
  const out = await Promise.all(
    list.map(async (p) => {
      const profile = await profileOf(r, p.Subject);
      const tier = profile?.tier ?? (typeof p.CompetitiveTier === "number" ? p.CompetitiveTier : null);
      const isMe = p.Subject === ctx.me;
      const id = agentId(p.CharacterID);
      const level = Number(p.PlayerIdentity?.AccountLevel);
      const recent = ctx.withStats ? recentOf(r, p.Subject) : null;
      const player: ValorantPlayer = {
        name: p.PlayerIdentity?.Incognito && !isMe ? null : (names.get(p.Subject) ?? null),
        agent: agentName(id),
        agentId: id,
        locked: ctx.pregame ? p.CharacterSelectionState === "locked" : true,
        tier,
        rank: tier === null ? null : rankName(tier),
        rr: profile?.rr ?? null,
        ally: ctx.pregame || p.TeamID === ctx.myTeam,
        me: isMe,
        level: p.PlayerIdentity?.HideAccountLevel && !isMe ? null : level > 0 ? level : null,
        peakTier: ctx.withStats ? peakTier(profile) : null,
        winRate: ctx.withStats ? actWinRate(profile) : null,
        kd: recent?.kd ?? null,
        hs: recent?.hs ?? null,
        party: null,
      };
      return { player, partyId: ctx.parties.get(p.Subject) };
    }),
  );
  out.sort((a, b) => Number(b.player.ally) - Number(a.player.ally) || (b.player.tier ?? -1) - (a.player.tier ?? -1));
  // Groupes de deux joueurs ou plus, numérotés dans l'ordre d'affichage.
  const sizes = new Map<string, number>();
  for (const { partyId } of out) if (partyId) sizes.set(partyId, (sizes.get(partyId) ?? 0) + 1);
  const numbers = new Map<string, number>();
  return out.map(({ player, partyId }) => {
    if (!partyId || (sizes.get(partyId) ?? 0) < 2) return player;
    if (!numbers.has(partyId)) numbers.set(partyId, numbers.size + 1);
    return { ...player, party: numbers.get(partyId)! };
  });
}

/** Présence Valorant décodée (`private` en base64) ; null si illisible. */
function decode(presence: any): any {
  try {
    return JSON.parse(Buffer.from(presence.private, "base64").toString("utf8"));
  } catch {
    return null;
  }
}

const off = () => {
  endOfMatch("off", null);
  return OFF;
};

async function read(): Promise<ValorantState> {
  const lock = await readLock();
  if (!lock) return off();
  refreshAssets();
  let session: any, presences: any;
  try {
    session = await local(lock, "/chat/v1/session");
    presences = await local(lock, "/chat/v4/presences");
  } catch {
    // Client Riot fermé (vieux lockfile) ou pas encore prêt.
    return off();
  }
  const puuid: string = session?.puuid;
  const valorant = (presences?.presences ?? []).filter((p: any) => p?.product === "valorant" && p.private);
  const mine = valorant.find((p: any) => p.puuid === puuid);
  const priv = mine && decode(mine);
  if (!puuid || !priv) return off();
  // Groupe de chaque joueur dont le client voit la présence (comme VRY) : à vérifier sur une vraie partie.
  const parties = new Map<string, string>();
  for (const p of valorant) {
    const presence = decode(p);
    const id = presence?.partyPresenceData?.partyId ?? presence?.partyId;
    if (typeof id === "string" && id) parties.set(p.puuid, id);
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
  const myProfile = r ? await profileOf(r, puuid) : null;
  const myTier = myProfile?.tier ?? Number(priv.playerPresenceData?.competitiveTier ?? priv.competitiveTier ?? 0);
  const me = {
    name: session.game_name ? `${session.game_name}#${session.game_tag}` : "",
    tier: myTier,
    rank: rankName(myTier),
    rr: myProfile?.rr ?? null,
    level: Number.isFinite(level) && level > 0 ? level : null,
    peakTier: peakTier(myProfile),
  };
  const result: ValorantState = {
    state,
    queue: QUEUES[queueId] ?? null,
    map: null,
    score: null,
    me,
    players: [],
    history: r && (state === "menu" || state === "queue") ? historyOf(r, puuid) : [],
    end: endOfMatch(state, r),
  };
  if (state === "ingame")
    result.score = { ally: Number(priv.partyOwnerMatchScoreAllyTeam) || 0, enemy: Number(priv.partyOwnerMatchScoreEnemyTeam) || 0 };
  if (!r || (state !== "ingame" && state !== "pregame")) return result;
  result.map = mapName(priv.matchPresenceData?.matchMap);
  // Combat à mort (chacun pour soi) : rang et agent seulement.
  const ctx = { me: puuid, pregame: state === "pregame", withStats: queueId !== "deathmatch", parties };
  try {
    if (state === "pregame") {
      const { MatchID } = await remote(r, `${r.glz}/pregame/v1/players/${puuid}`);
      const match = await remote(r, `${r.glz}/pregame/v1/matches/${MatchID}`);
      result.map = mapName(match.MapID) ?? result.map;
      result.players = await players(r, match.AllyTeam?.Players ?? [], { ...ctx, myTeam: null });
    } else {
      const { MatchID } = await remote(r, `${r.glz}/core-game/v1/players/${puuid}`);
      const match = await remote(r, `${r.glz}/core-game/v1/matches/${MatchID}`);
      const list: RawPlayer[] = match.Players ?? [];
      result.map = mapName(match.MapID) ?? result.map;
      result.players = await players(r, list, { ...ctx, myTeam: list.find((p) => p.Subject === puuid)?.TeamID ?? null });
      const incognito = new Set(list.filter((p) => p.PlayerIdentity?.Incognito).map((p) => p.Subject));
      if (typeof MatchID === "string") watchMatch({ matchId: MatchID, me: puuid, incognito });
    }
  } catch {
    // Entre deux phases (la partie se termine, le jeu charge) : l'état seul, les joueurs reviennent au tour suivant.
  }
  return result;
}

/** Deux widgets (ou un widget agrandi) qui lisent en même temps : une seule lecture ; rien de gardé ensuite (rangs et noms ont leur cache). */
const flight = singleFlight<ValorantState>(0);

export function valorantState(): Promise<ValorantState> {
  return flight.get(read);
}
