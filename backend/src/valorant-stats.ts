import { beforeAscendant, currentAct, agentName } from "./valorant-assets.ts";
import { hsPercent, knownMatch, matchDetails, resultFor } from "./valorant-match.ts";
import { mapName } from "./valorant-names.ts";
import { paced, remote, type Remote } from "./valorant-riot.ts";

/**
 * Stats « tracker » d'un joueur, tirées de son historique classé (Riot ne donne rien par joueur pendant la partie) :
 * rang, meilleur rang et % de victoires de l'acte (un seul appel mmr, comme le rang d'avant), puis K/D et % de tirs à la
 * tête sur ses 3 dernières classées (file au débit limité, sans bloquer la réponse : la case reste vide en attendant).
 */

type Season = { wins: number; games: number; best: number };
export type Profile = { tier: number; rr: number; seasons: Record<string, Season> };
export type Recent = { kd: number | null; hs: number | null };
export type HistoryEntry = {
  won: boolean | null;
  score: { ally: number; enemy: number } | null;
  map: string | null;
  agent: string | null;
  agentId: string | null;
  kills: number | null;
  deaths: number | null;
  assists: number | null;
  acs: number | null;
  rr: number;
};

type Entry<T> = { until: number; value: T | null };
/** Rang et saisons gardés 10 min (rien ne change pendant une partie, et pd limite les appels). */
const PROFILE_TTL = 10 * 60_000;
/** K/D récent : une fois par partie (l'historique ne bouge pas pendant qu'on joue). */
const RECENT_TTL = 30 * 60_000;
/** Mes 5 dernières : relues au plus toutes les 2 min, et à la fin de chaque partie. */
const HISTORY_TTL = 2 * 60_000;
/** Un échec est gardé 30 s : on ne relance pas dix appels refusés à chaque relecture. */
const FAIL_TTL = 30_000;

const profiles = new Map<string, Entry<Profile>>();
const recents = new Map<string, Entry<Recent> & { pending?: boolean }>();

function prune<T>(map: Map<string, Entry<T>>, now: number) {
  for (const [id, entry] of map) if (now >= entry.until) map.delete(id);
}

export async function profileOf(r: Remote, puuid: string): Promise<Profile | null> {
  const now = Date.now();
  const known = profiles.get(puuid);
  if (known && now < known.until) return known.value;
  prune(profiles, now);
  try {
    const mmr = await remote(r, `${r.pd}/mmr/v1/players/${puuid}`);
    const latest = mmr?.LatestCompetitiveUpdate ?? {};
    const seasons: Record<string, Season> = {};
    for (const [id, s] of Object.entries<any>(mmr?.QueueSkills?.competitive?.SeasonalInfoBySeasonID ?? {})) {
      const tiers = Object.entries<number>(s?.WinsByTier ?? {})
        .filter(([, wins]) => Number(wins) > 0)
        .map(([tier]) => Number(tier));
      seasons[id] = { wins: Number(s?.NumberOfWinsWithPlacements) || 0, games: Number(s?.NumberOfGames) || 0, best: Math.max(0, ...tiers) };
    }
    const profile = { tier: Number(latest.TierAfterUpdate) || 0, rr: Number(latest.RankedRatingAfterUpdate) || 0, seasons };
    profiles.set(puuid, { until: Date.now() + PROFILE_TTL, value: profile });
    return profile;
  } catch {
    profiles.set(puuid, { until: Date.now() + FAIL_TTL, value: null });
    return null;
  }
}

/** Plus haut rang gagné sur toutes les saisons (rangs d'avant Ascendant relus à l'échelle d'aujourd'hui). */
export function peakTier(p: Profile | null): number | null {
  if (!p) return null;
  const best = Math.max(0, ...Object.entries(p.seasons).map(([id, s]) => (s.best > 20 && beforeAscendant(id) ? s.best + 3 : s.best)));
  return best >= 3 ? best : null;
}

/** % de victoires de l'acte en cours ; null sans partie dans l'acte (ou acte pas encore connu). */
export function actWinRate(p: Profile | null): number | null {
  const act = currentAct();
  const s = p && act ? p.seasons[act] : undefined;
  return s && s.games > 0 ? Math.round((100 * s.wins) / s.games) : null;
}

/** K/D et % tête de ses 3 dernières classées, si déjà lus ; sinon lance la lecture et rend null (case en attente). */
export function recentOf(r: Remote, puuid: string): Recent | null {
  const now = Date.now();
  const known = recents.get(puuid);
  if (known && (known.pending || now < known.until)) return known.value;
  prune(recents, now);
  recents.set(puuid, { until: Infinity, value: null, pending: true });
  loadRecent(r, puuid).then(
    (value) => recents.set(puuid, { until: Date.now() + RECENT_TTL, value }),
    () => recents.set(puuid, { until: Date.now() + FAIL_TTL, value: null }),
  );
  return null;
}

async function loadRecent(r: Remote, puuid: string): Promise<Recent> {
  const history = await paced(() => remote(r, `${r.pd}/match-history/v1/history/${puuid}?startIndex=0&endIndex=3&queue=competitive`));
  const ids: string[] = (history?.History ?? [])
    .map((h: any) => h?.MatchID)
    .filter((id: unknown) => typeof id === "string")
    .slice(0, 3);
  const lines = (await Promise.all(ids.map((id) => matchDetails(r, id)))).flatMap((m) => m.players.filter((p) => p.puuid === puuid));
  if (lines.length === 0) return { kd: null, hs: null };
  const sum = (key: "kills" | "deaths" | "headshots" | "hits") => lines.reduce((total, l) => total + l[key], 0);
  const kills = sum("kills");
  const deaths = sum("deaths");
  return { kd: Math.round((deaths > 0 ? kills / deaths : kills) * 100) / 100, hs: hsPercent(sum("headshots"), sum("hits")) };
}

type Update = { id: string; mapId: string | undefined; rr: number };
let history: (Entry<Update[]> & { puuid: string; pending?: boolean }) | null = null;
/** Détails de parties en échec : pas redemandés avant 30 s. */
const failedMatches = new Map<string, number>();

/** Mes 5 dernières classées : RR tout de suite, le reste (agent, K/D/A, ACS, score) à mesure que les détails arrivent. */
export function historyOf(r: Remote, puuid: string): HistoryEntry[] {
  const now = Date.now();
  for (const [id, until] of failedMatches) if (now >= until) failedMatches.delete(id);
  if (!history || history.puuid !== puuid || (!history.pending && now >= history.until)) {
    const mine = { puuid, until: Infinity, value: history?.puuid === puuid ? history.value : null, pending: true };
    history = mine;
    loadUpdates(r, puuid).then(
      (value) => history === mine && (history = { puuid, until: Date.now() + HISTORY_TTL, value }),
      () => history === mine && (history = { puuid, until: Date.now() + FAIL_TTL, value: mine.value }),
    );
  }
  return (history.value ?? []).map((u) => entryOf(r, puuid, u));
}

/** Fin de partie : l'historique se relit (la partie qui vient de finir y entre). */
export function forgetHistory() {
  history = null;
}

async function loadUpdates(r: Remote, puuid: string): Promise<Update[]> {
  const res = await paced(() => remote(r, `${r.pd}/mmr/v1/players/${puuid}/competitiveupdates?startIndex=0&endIndex=5&queue=competitive`));
  return (res?.Matches ?? [])
    .filter((m: any) => typeof m?.MatchID === "string")
    .slice(0, 5)
    .map((m: any) => ({ id: m.MatchID, mapId: m.MapID, rr: Number(m.RankedRatingEarned) || 0 }));
}

function entryOf(r: Remote, puuid: string, u: Update): HistoryEntry {
  const match = knownMatch(u.id);
  if (!match && !failedMatches.has(u.id)) {
    matchDetails(r, u.id).catch(() => failedMatches.set(u.id, Date.now() + FAIL_TTL));
  }
  const line = match?.players.find((p) => p.puuid === puuid);
  return {
    ...(match ? resultFor(match, puuid) : { won: null, score: null }),
    map: mapName(match?.mapId ?? u.mapId),
    agent: agentName(line?.agentId ?? null),
    agentId: line?.agentId ?? null,
    kills: line?.kills ?? null,
    deaths: line?.deaths ?? null,
    assists: line?.assists ?? null,
    acs: line?.acs ?? null,
    rr: u.rr,
  };
}
