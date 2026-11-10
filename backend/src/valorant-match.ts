import { agentId, agentName, mapIdOf } from "./valorant-assets.ts";
import { QUEUES, mapName, rankName } from "./valorant-names.ts";
import { paced, remote, type Remote } from "./valorant-riot.ts";

/**
 * Détail d'une partie terminée (pd `match-details`) : réduit à ce que le widget montre (ACS, K/D/A, tirs à la tête,
 * dégâts, premiers kills), gardé en mémoire. Sert au tableau de fin, aux 5 dernières parties du menu et au K/D des joueurs.
 */

export type MatchLine = {
  puuid: string;
  team: string;
  agentId: string | null;
  tier: number;
  /** « Nom#TAG », ou null si Riot ne le donne pas. */
  name: string | null;
  acs: number;
  kills: number;
  deaths: number;
  assists: number;
  headshots: number;
  /** Tirs qui ont touché (tête, corps, jambes). */
  hits: number;
  adr: number;
  firstKills: number;
};

/** Fin d'un round : élimination, spike explosé, spike désamorcé, temps écoulé (abandon et inconnu : null). */
export type RoundEnd = "elimination" | "detonate" | "defuse" | "time";

export type ParsedMatch = {
  id: string;
  mapId: string | undefined;
  queueId: string;
  teams: { id: string; won: boolean; rounds: number }[];
  players: MatchLine[];
  /** Rounds dans l'ordre : équipe gagnante (vide si aucune) et fin. */
  rounds: { winner: string; end: RoundEnd | null }[];
};

export type BoardPlayer = {
  name: string | null;
  agent: string | null;
  agentId: string | null;
  tier: number;
  rank: string;
  ally: boolean;
  me: boolean;
  acs: number;
  kills: number;
  deaths: number;
  assists: number;
  hs: number | null;
  adr: number;
  firstKills: number;
  mvp: boolean;
};

/** Tableau des scores : `ffa` (combat à mort) classe par kills, sans équipe ni MVP. */
export type Board = {
  map: string | null;
  /** Identifiant de la carte chez valorant-api.com (image : /valorant/map?id=) ; null si inconnue. */
  mapId: string | null;
  queue: string | null;
  mode: "teams" | "ffa";
  won: boolean | null;
  score: { ally: number; enemy: number } | null;
  players: BoardPlayer[];
  /** Rounds vus de mon équipe (gagné, perdu, null si aucun gagnant), avec leur fin ; vide en combat à mort. */
  rounds: { won: boolean | null; end: RoundEnd | null }[];
};

const ENDS: Record<string, RoundEnd> = { Elimination: "elimination", Detonate: "detonate", Defuse: "defuse" };

/** Fin d'un round de match-details : `roundResultCode`, vide quand le temps est écoulé (`roundResult` le dit). */
const roundEnd = (round: any): RoundEnd | null =>
  ENDS[String(round?.roundResultCode ?? "")] ?? (round?.roundResult === "Round timer expired" ? "time" : null);

const num = (v: unknown) => Number(v) || 0;
export const hsPercent = (headshots: number, hits: number) => (hits > 0 ? Math.round((100 * headshots) / hits) : null);

export function parseMatch(raw: any): ParsedMatch {
  const rounds: any[] = Array.isArray(raw?.roundResults) ? raw.roundResults : [];
  const shots = new Map<string, { head: number; hits: number; damage: number }>();
  const firsts = new Map<string, number>();
  for (const round of rounds) {
    let first: { killer: string; time: number } | null = null;
    for (const ps of round?.playerStats ?? []) {
      const s = shots.get(ps.subject) ?? { head: 0, hits: 0, damage: 0 };
      for (const d of ps.damage ?? []) {
        s.head += num(d.headshots);
        s.hits += num(d.headshots) + num(d.bodyshots) + num(d.legshots);
        s.damage += num(d.damage);
      }
      shots.set(ps.subject, s);
      for (const k of ps.kills ?? []) {
        const time = Number(k.roundTime ?? k.gameTime);
        if (Number.isFinite(time) && (!first || time < first.time)) first = { killer: k.killer, time };
      }
    }
    if (first) firsts.set(first.killer, (firsts.get(first.killer) ?? 0) + 1);
  }
  const players = (Array.isArray(raw?.players) ? raw.players : []).map((p: any): MatchLine => {
    const played = num(p.stats?.roundsPlayed) || rounds.length || 1;
    const s = shots.get(p.subject) ?? { head: 0, hits: 0, damage: 0 };
    return {
      puuid: String(p.subject),
      team: String(p.teamId ?? ""),
      agentId: agentId(p.characterId),
      tier: num(p.competitiveTier),
      name: p.gameName ? `${p.gameName}#${p.tagLine ?? ""}` : null,
      acs: Math.round(num(p.stats?.score) / played),
      kills: num(p.stats?.kills),
      deaths: num(p.stats?.deaths),
      assists: num(p.stats?.assists),
      headshots: s.head,
      hits: s.hits,
      adr: Math.round(s.damage / played),
      firstKills: firsts.get(p.subject) ?? 0,
    };
  });
  return {
    id: String(raw?.matchInfo?.matchId ?? ""),
    mapId: raw?.matchInfo?.mapId,
    queueId: String(raw?.matchInfo?.queueID ?? ""),
    teams: (Array.isArray(raw?.teams) ? raw.teams : []).map((t: any) => ({ id: String(t.teamId), won: !!t.won, rounds: num(t.roundsWon) })),
    players,
    rounds: rounds.map((r) => ({ winner: String(r?.winningTeam ?? ""), end: roundEnd(r) })),
  };
}

const isFfa = (m: ParsedMatch) => m.queueId === "deathmatch" || m.teams.length > 2;

/** Résultat de mon équipe : gagné, perdu, ou null (égalité, ou chacun pour soi). */
export function resultFor(m: ParsedMatch, me: string): { won: boolean | null; score: { ally: number; enemy: number } | null } {
  const team = m.players.find((p) => p.puuid === me)?.team;
  const mine = m.teams.find((t) => t.id === team);
  const other = m.teams.find((t) => t.id !== team);
  if (isFfa(m) || !mine || !other) return { won: null, score: null };
  return { won: mine.won ? true : other.won ? false : null, score: { ally: mine.rounds, enemy: other.rounds } };
}

/** Tableau de fin : mon équipe d'abord, chaque équipe triée par ACS, son MVP marqué ; incognito sans nom (sauf moi). */
export function boardOf(m: ParsedMatch, me: string, incognito: Set<string>): Board {
  const ffa = isFfa(m);
  const myTeam = m.players.find((p) => p.puuid === me)?.team;
  const rows = m.players.map((p): BoardPlayer => ({
    name: incognito.has(p.puuid) && p.puuid !== me ? null : p.name,
    agent: agentName(p.agentId),
    agentId: p.agentId,
    tier: p.tier,
    rank: rankName(p.tier),
    ally: ffa ? p.puuid === me : p.team === myTeam,
    me: p.puuid === me,
    acs: p.acs,
    kills: p.kills,
    deaths: p.deaths,
    assists: p.assists,
    hs: hsPercent(p.headshots, p.hits),
    adr: p.adr,
    firstKills: p.firstKills,
    mvp: false,
  }));
  if (ffa) rows.sort((a, b) => b.kills - a.kills);
  else {
    rows.sort((a, b) => Number(b.ally) - Number(a.ally) || b.acs - a.acs);
    for (const side of [true, false]) {
      const best = rows.find((r) => r.ally === side);
      if (best && best.acs > 0) best.mvp = true;
    }
  }
  const rounds = ffa ? [] : m.rounds.map((r) => ({ won: r.winner ? r.winner === myTeam : null, end: r.end }));
  return {
    map: mapName(m.mapId),
    mapId: mapIdOf(m.mapId),
    queue: QUEUES[m.queueId] ?? null,
    mode: ffa ? "ffa" : "teams",
    ...resultFor(m, me),
    players: rows,
    rounds,
  };
}

/** Parties déjà lues (100 au plus : de quoi couvrir les 3 dernières des 10 joueurs et mes 5 dernières). */
const matches = new Map<string, ParsedMatch>();
const MATCHES_MAX = 100;
const inflight = new Map<string, Promise<ParsedMatch>>();

export const knownMatch = (id: string) => matches.get(id);

/** Détail d'une partie, lu une seule fois (file au débit limité, sauf `direct`) ; une erreur n'est pas gardée. */
export function matchDetails(r: Remote, id: string, { direct = false } = {}): Promise<ParsedMatch> {
  const known = matches.get(id);
  if (known) return Promise.resolve(known);
  const running = inflight.get(id);
  if (running) return running;
  const read = () => remote(r, `${r.pd}/match-details/v1/matches/${encodeURIComponent(id)}`);
  const run = (direct ? read() : paced(read))
    .then((raw) => {
      const parsed = parseMatch(raw);
      if (matches.size >= MATCHES_MAX) matches.delete(matches.keys().next().value!);
      matches.set(id, parsed);
      return parsed;
    })
    .finally(() => inflight.delete(id));
  inflight.set(id, run);
  return run;
}
