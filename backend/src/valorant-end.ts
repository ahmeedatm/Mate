import { boardOf, matchDetails, type Board } from "./valorant-match.ts";
import type { Remote } from "./valorant-riot.ts";
import { forgetHistory } from "./valorant-stats.ts";

/**
 * Tableau des scores de fin de partie : quand la partie suivie passe au menu, le compagnon lit son détail (Riot ne l'a
 * pas toujours tout de suite : nouvel essai toutes les 5 s, une minute) et le garde 2 minutes, compté ici pour que tous
 * les widgets et le zoom montrent le même. Une nouvelle file ou partie le coupe ; rien n'est gardé sur disque.
 */

export type EndView = Board & { status: "loading" | "ready" | "missing" };
type Watched = { matchId: string; me: string; incognito: Set<string> };
type End = { status: EndView["status"]; until: number; board: Board | null };

const showMs = () => Number(process.env.VALORANT_END_MS) || 120_000;
const retryMs = () => Number(process.env.VALORANT_RETRY_MS) || 5_000;
const TRIES = 12;
const EMPTY: Board = { map: null, queue: null, mode: "teams", won: null, score: null, players: [] };

let watched: Watched | null = null;
let end: End | null = null;

/** Partie en cours : c'est elle dont on montrera le tableau. */
export function watchMatch(match: Watched) {
  watched = match;
  end = null;
}

/** Tableau à montrer dans l'état `state`, ou null. Le passage au menu après une partie suivie le lance. */
export function endOfMatch(state: string, r: Remote | null): EndView | null {
  if (state !== "menu") {
    // File, sélection ou partie : la nouvelle partie gagne. Client fermé : tout est perdu.
    end = null;
    if (state !== "ingame") watched = null;
    return null;
  }
  if (watched && r) start(watched, r);
  watched = null;
  if (end && Date.now() >= end.until) end = null;
  return end && { ...(end.board ?? EMPTY), status: end.status };
}

function start(match: Watched, r: Remote) {
  forgetHistory();
  const since = Date.now();
  const mine: End = { status: "loading", until: Infinity, board: null };
  end = mine;
  void (async () => {
    for (let i = 0; i < TRIES && end === mine; i++) {
      try {
        const parsed = await matchDetails(r, match.matchId);
        if (end === mine) end = { status: "ready", until: Date.now() + showMs(), board: boardOf(parsed, match.me, match.incognito) };
        return;
      } catch {
        await new Promise((resolve) => setTimeout(resolve, retryMs()));
      }
    }
    if (end === mine) end = { status: "missing", until: since + showMs(), board: null };
  })();
}
