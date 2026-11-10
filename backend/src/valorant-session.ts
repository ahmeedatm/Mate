import { paced, remote, type Remote } from "./valorant-riot.ts";

/**
 * Mes dernières classées (pd `competitiveupdates` : partie, carte, début, RR gagnés ou perdus), lues dans la file au débit
 * limité sans bloquer la réponse : mes 5 dernières du menu, ma session du jour et mon bilan sur la carte jouée.
 * Victoire ou défaite au signe des RR (un nul rapporte 0).
 */

export type Update = { id: string; mapId: string | undefined; rr: number; start: number };
export type Today = { rr: number; wins: number; losses: number; results: (boolean | null)[] };
export type MapStats = { wins: number; games: number; window: number; results: boolean[] };

/** 40 dernières, en deux pages de 20 (le plus que Riot rend d'un coup). */
const PAGE = 20;
const PAGES = 2;
/** Relues au plus toutes les 2 min, et à la fin de chaque partie. */
const UPDATES_TTL = 2 * 60_000;
const FAIL_TTL = 30_000;

type Cached = { puuid: string; until: number; value: Update[] | null; pending: boolean };
let cached: Cached | null = null;

/** Mes dernières classées, de la plus récente, si déjà lues ; sinon lance la lecture (null en attendant). */
export function updatesOf(r: Remote, puuid: string): Update[] | null {
  const now = Date.now();
  if (!cached || cached.puuid !== puuid || (!cached.pending && now >= cached.until)) {
    const mine: Cached = { puuid, until: Infinity, value: cached?.puuid === puuid ? cached.value : null, pending: true };
    cached = mine;
    loadUpdates(r, puuid).then(
      (value) => cached === mine && (cached = { puuid, until: Date.now() + UPDATES_TTL, value, pending: false }),
      () => cached === mine && (cached = { puuid, until: Date.now() + FAIL_TTL, value: mine.value, pending: false }),
    );
  }
  return cached.value;
}

/** Fin de partie : relues (la partie qui vient de finir y entre). */
export function forgetUpdates() {
  cached = null;
}

async function loadUpdates(r: Remote, puuid: string): Promise<Update[]> {
  const all: Update[] = [];
  for (let page = 0; page < PAGES; page++) {
    const from = page * PAGE;
    const url = `${r.pd}/mmr/v1/players/${puuid}/competitiveupdates?startIndex=${from}&endIndex=${from + PAGE}&queue=competitive`;
    // Page suivante refusée : les premières suffisent (menu et journée) ; la première refusée, tout échoue.
    const res = await paced(() => remote(r, url)).catch((e) => {
      if (page === 0) throw e;
      return null;
    });
    const matches: any[] = (res?.Matches ?? []).filter((m: any) => typeof m?.MatchID === "string");
    all.push(
      ...matches.map((m) => ({
        id: m.MatchID,
        mapId: m.MapID,
        rr: Number(m.RankedRatingEarned) || 0,
        start: Number(m.MatchStartTime) || 0,
      })),
    );
    if (matches.length < PAGE) break;
  }
  return all;
}

const resultOf = (rr: number) => (rr > 0 ? true : rr < 0 ? false : null);

/** Mes classées commencées depuis minuit (heure de l'ordinateur) : RR, victoires, défaites, de la plus ancienne. */
export function todayOf(updates: Update[], now = Date.now()): Today {
  const midnight = new Date(now).setHours(0, 0, 0, 0);
  const today = updates.filter((u) => u.start >= midnight).reverse();
  return {
    rr: today.reduce((sum, u) => sum + u.rr, 0),
    wins: today.filter((u) => u.rr > 0).length,
    losses: today.filter((u) => u.rr < 0).length,
    results: today.map((u) => resultOf(u.rr)),
  };
}

/** Mon bilan sur la carte `map` (chemin du jeu) parmi mes dernières classées, de la plus ancienne ; null sans carte. */
export function mapStatsOf(updates: Update[], map: string | null | undefined): MapStats | null {
  if (!map) return null;
  const here = updates.filter((u) => u.mapId?.toLowerCase() === map.toLowerCase()).reverse();
  return { wins: here.filter((u) => u.rr > 0).length, games: here.length, window: updates.length, results: here.map((u) => u.rr > 0) };
}
