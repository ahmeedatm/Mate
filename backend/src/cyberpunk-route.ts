/**
 * Chemin du GPS du jeu pour la vue conduite de la tablette. Le jeu calcule ce chemin le long des rues pour sa mini-map,
 * mais ne le montre pas aux scripts : le mod libre In-World Navigation (jackhumbert, MIT) le rend lisible
 * (`MinimapContainerController.questPoints` / `poiPoints`), et le mod Mate l'écrit dans `route.json`. Ici, il est
 * simplifié pour que la tablette n'ait qu'un trait léger à dessiner.
 */

/** Écart toléré (mètres) : un point plus proche que ça de la ligne entre ses voisins gardés ne change rien au trait. */
const TOLERANCE_M = 1;
/** Plafond de points envoyés : bien au-delà d'un trajet à travers la ville une fois simplifié. */
export const MAX_ROUTE_POINTS = 1500;
const round = (v: number) => Math.round(v * 10) / 10;

/** Distance au carré du point i au segment [a, b] (indices de points dans le tableau à plat). */
function distSq(p: number[], i: number, a: number, b: number) {
  const ax = p[2 * a];
  const ay = p[2 * a + 1];
  const dx = p[2 * b] - ax;
  const dy = p[2 * b + 1] - ay;
  const len = dx * dx + dy * dy;
  const t = len === 0 ? 0 : Math.max(0, Math.min(1, ((p[2 * i] - ax) * dx + (p[2 * i + 1] - ay) * dy) / len));
  const ex = p[2 * i] - (ax + t * dx);
  const ey = p[2 * i + 1] - (ay + t * dy);
  return ex * ex + ey * ey;
}

/**
 * Ramer-Douglas-Peucker sur `[x0, y0, x1, y1, …]` : les virages restent, les points alignés partent. Rend `[]` si le
 * chemin a moins de deux points ou une coordonnée qui n'est pas un nombre fini.
 */
export function simplifyRoute(pts: unknown[]): number[] {
  if (pts.length % 2 !== 0 || pts.length < 4 || !pts.every((v) => typeof v === "number" && Number.isFinite(v))) return [];
  const p = pts as number[];
  const n = p.length / 2;
  const keep = new Uint8Array(n);
  keep[0] = keep[n - 1] = 1;
  const stack: [number, number][] = [[0, n - 1]];
  const tol = TOLERANCE_M * TOLERANCE_M;
  while (stack.length > 0) {
    const [a, b] = stack.pop()!;
    let far = -1;
    let farD = tol;
    for (let i = a + 1; i < b; i++) {
      const d = distSq(p, i, a, b);
      if (d > farD) [far, farD] = [i, d];
    }
    if (far < 0) continue;
    keep[far] = 1;
    stack.push([a, far], [far, b]);
  }
  const out: number[] = [];
  for (let i = 0; i < n && out.length < MAX_ROUTE_POINTS * 2; i++) if (keep[i]) out.push(round(p[2 * i]), round(p[2 * i + 1]));
  return out;
}
