/**
 * Rounds de la partie en cours, vus de mon équipe. Riot ne donne que le score (présence du client) : le compagnon note
 * chaque fois qu'il bouge. +1 chez moi : gagné ; +1 en face : perdu ; les deux ont bougé entre deux lectures : null
 * (ordre inconnu). Arrivé en cours de partie, les rounds déjà joués sont connus seulement si un seul camp a marqué.
 * En mémoire, une seule partie : client fermé ou compagnon redémarré, la frise repart du score.
 */

type Score = { ally: number; enemy: number };
type Live = { matchId: string | null; score: Score; rounds: (boolean | null)[] };

let live: Live | null = null;

const repeat = (value: boolean | null, n: number) => Array.from({ length: n }, () => value);

/** Rounds au premier regard sur une partie : connus si un seul camp a marqué. */
function first(score: Score): (boolean | null)[] {
  if (score.enemy === 0) return repeat(true, score.ally);
  if (score.ally === 0) return repeat(false, score.enemy);
  return repeat(null, score.ally + score.enemy);
}

/**
 * Rounds de la partie `matchId` au score `score`, du premier au dernier. `matchId` null (partie pas encore lue) : la
 * même que la précédente tant que le score ne recule pas.
 */
export function liveRounds(matchId: string | null, score: Score): (boolean | null)[] {
  const same = live && (matchId === null || matchId === live.matchId);
  const ally = score.ally - (live?.score.ally ?? 0);
  const enemy = score.enemy - (live?.score.enemy ?? 0);
  if (!live || !same || ally < 0 || enemy < 0) {
    live = { matchId: matchId ?? live?.matchId ?? null, score, rounds: first(score) };
    return live.rounds;
  }
  const added = ally > 0 && enemy > 0 ? repeat(null, ally + enemy) : [...repeat(true, ally), ...repeat(false, enemy)];
  live = { matchId: live.matchId, score, rounds: [...live.rounds, ...added] };
  return live.rounds;
}

/** Pour les tests : oublie la partie suivie. */
export function resetLiveRounds() {
  live = null;
}
