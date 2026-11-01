import path from "node:path";
import { config } from "./config.ts";
import { runCommand } from "./os.ts";
import { readJson } from "./store.ts";

/**
 * Tuile Valeurs : des chiffres calculés par l'ordinateur. Les commandes sont écrites à la main dans metrics.json
 * (liste blanche) ; la tablette ne fait que lire le résultat, aucune commande ne vient d'elle (comme deck.ts).
 */

export type Metric = { label: string; value: string | null };
type Def = { label: string; command: string };

export const MAX_METRICS = 4;
export const CACHE_MS = 30_000;
const MAX_LABEL = 40;
const MAX_COMMAND = 500;
const MAX_VALUE = 40;
const storeFile = path.join(config.dataDir, "metrics.json");

/** Les entrées bien formées du fichier (libellé et commande non vides), quatre au plus ; fichier absent ou illisible : aucune. */
async function readDefs(): Promise<Def[]> {
  const file = await readJson<{ metrics?: unknown }>(storeFile, () => ({}), { quarantine: false });
  if (!Array.isArray(file.metrics)) return [];
  return file.metrics
    .filter((m): m is Def => typeof m?.label === "string" && typeof m?.command === "string")
    .map((m) => ({ label: m.label.trim().slice(0, MAX_LABEL), command: m.command.trim() }))
    .filter((m) => m.label && m.command && m.command.length <= MAX_COMMAND)
    .slice(0, MAX_METRICS);
}

/** Première ligne non vide de la sortie, coupée ; `null` si la commande échoue ou ne dit rien. */
async function valueOf(run: (command: string) => Promise<string>, command: string) {
  try {
    const line = (await run(command)).split(/\r?\n/).find((l) => l.trim());
    return line ? line.trim().slice(0, MAX_VALUE) : null;
  } catch {
    return null;
  }
}

let cache: { key: string; at: number; items: Metric[] } | null = null;

/** Lance les commandes du fichier (en parallèle) ; la réponse reste en cache `CACHE_MS` tant que le fichier ne change pas. */
export async function loadMetrics(run: (command: string) => Promise<string> = runCommand, now = Date.now()) {
  const defs = await readDefs();
  const key = JSON.stringify(defs);
  if (cache && cache.key === key && now - cache.at >= 0 && now - cache.at < CACHE_MS) return { items: cache.items };
  const items = await Promise.all(defs.map(async (d) => ({ label: d.label, value: await valueOf(run, d.command) })));
  cache = { key, at: now, items };
  return { items };
}
