import fs from "node:fs/promises";
import { cachedIcon } from "./deck.ts";
import { readCapped } from "./net-guard.ts";
import { shrinkPng } from "./png-shrink.ts";
import { singleFlight } from "./single-flight.ts";
import { AGENTS } from "./valorant-names.ts";

/**
 * Listes de valorant-api.com (agents, logos de rang, actes) et leurs images, lues par l'ordinateur : la tablette ne parle
 * jamais à Internet pour ça (route /valorant/agent et /valorant/rank). Service public sans clé ; rien de personnel ne lui
 * est envoyé (docs/confidentialite.md). `VALORANT_ASSETS_URL` le remplace pour les tests.
 */

type Act = { id: string; start: number; end: number };
type Assets = { agents: Map<string, { name: string; icon: string }>; ranks: Map<number, string>; acts: Act[] };

const LIST_TTL = 24 * 60 * 60_000;
/** Après un échec, pas de nouvel essai avant 5 min : valorant-api.com en panne ne reçoit pas un appel par lecture. */
const FAIL_PAUSE = 5 * 60_000;
const LIST_MAX_BYTES = 5_000_000;
const IMAGE_MAX_BYTES = 2_000_000;
/** Côté des images servies à la tablette (≤ 128 px, docs/tablette-4.2.md). */
const IMAGE_SIDE = 128;
/** Arrivée d'Ascendant (épisode 5, acte 1) : avant, Immortel 1 valait 21 et Radiant 24 (3 rangs de moins). */
const ASCENDANT_SINCE = Date.parse("2022-06-22T00:00:00Z");
export const AGENT_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

const base = () => (process.env.VALORANT_ASSETS_URL?.trim() || "https://valorant-api.com").replace(/\/+$/, "");
const flight = singleFlight<Assets>(LIST_TTL);
let last: Assets | null = null;
let failedAt = 0;

async function listOf(route: string): Promise<any[]> {
  const res = await fetch(base() + route, { redirect: "error", signal: AbortSignal.timeout(10_000) });
  if (!res.ok) throw new Error(`valorant-api.com a répondu ${res.status}`);
  const { data, truncated } = await readCapped(res, LIST_MAX_BYTES);
  if (truncated) throw new Error("Liste valorant-api.com trop grosse");
  const list = JSON.parse(data.toString("utf8"))?.data;
  return Array.isArray(list) ? list : [];
}

async function load(): Promise<Assets> {
  const [agents, tiers, seasons] = await Promise.all([
    listOf("/v1/agents?isPlayableCharacter=true&language=fr-FR"),
    listOf("/v1/competitivetiers"),
    listOf("/v1/seasons"),
  ]);
  const assets: Assets = { agents: new Map(), ranks: new Map(), acts: [] };
  for (const a of agents) {
    const id = String(a?.uuid ?? "").toLowerCase();
    if (AGENT_ID.test(id) && typeof a.displayIcon === "string")
      assets.agents.set(id, { name: String(a.displayName ?? ""), icon: a.displayIcon });
  }
  // Le dernier tableau de rangs est celui de l'épisode en cours.
  for (const t of tiers.at(-1)?.tiers ?? []) if (typeof t?.smallIcon === "string") assets.ranks.set(Number(t.tier), t.smallIcon);
  for (const s of seasons) {
    if (s?.type !== "EAresSeasonType::Act") continue;
    const act = { id: String(s.uuid), start: Date.parse(s.startTime), end: Date.parse(s.endTime) };
    if (Number.isFinite(act.start) && Number.isFinite(act.end)) assets.acts.push(act);
  }
  last = assets;
  return assets;
}

/** Listes à jour (relues une fois par jour), ou les dernières connues si valorant-api.com ne répond pas. */
export async function assets(): Promise<Assets | null> {
  if (Date.now() - failedAt < FAIL_PAUSE) return last;
  try {
    return await flight.get(load);
  } catch {
    failedAt = Date.now();
    return last;
  }
}

/** Lance la lecture des listes sans l'attendre (la première réponse du widget part avec les noms de secours). */
export function refreshAssets() {
  void assets();
}

/** Identifiant d'agent connu (minuscules), ou null : pas encore choisi, ou inconnu. */
export function agentId(raw: unknown): string | null {
  const id = String(raw ?? "").toLowerCase();
  return last?.agents.has(id) || id in AGENTS ? id : null;
}

export const agentName = (id: string | null) => (id ? (last?.agents.get(id)?.name ?? AGENTS[id] ?? null) : null);

/** Acte classé en cours, ou null si les listes ne sont pas encore lues. */
export const currentAct = (now = Date.now()) => last?.acts.find((a) => a.start <= now && now < a.end)?.id ?? null;

/** Acte d'avant Ascendant : ses rangs au-dessus de Diamant 3 se lisent 3 plus haut aujourd'hui. */
export const beforeAscendant = (actId: string) => {
  const act = last?.acts.find((a) => a.id === actId);
  return !!act && act.start < ASCENDANT_SINCE;
};

/** Fichier de l'icône carrée d'un agent (128 px), téléchargé une fois ; undefined si l'agent est inconnu. */
export async function agentIcon(id: string): Promise<string | undefined> {
  const url = (await assets())?.agents.get(id)?.icon;
  return url ? image(url) : undefined;
}

/** Fichier du logo d'un rang (0 à 27), téléchargé une fois. */
export async function rankIcon(tier: number): Promise<string | undefined> {
  const url = (await assets())?.ranks.get(tier);
  return url ? image(url) : undefined;
}

/** Adresse d'image venue de la liste : seulement vers valorant-api.com (ou le faux serveur des tests). */
function trusted(url: string): boolean {
  try {
    const u = new URL(url);
    return u.origin === new URL(base()).origin || (u.protocol === "https:" && u.hostname === "media.valorant-api.com");
  } catch {
    return false;
  }
}

function image(url: string): Promise<string | undefined> {
  return trusted(url) ? cachedIcon(`valorant:${url}`, (out) => download(url, out)) : Promise.resolve(undefined);
}

async function download(url: string, out: string): Promise<boolean> {
  const res = await fetch(url, { redirect: "error", signal: AbortSignal.timeout(10_000) });
  if (!res.ok) return false;
  const { data, truncated } = await readCapped(res, IMAGE_MAX_BYTES);
  // PNG d'un format que shrinkPng ne lit pas : gardé tel quel, la tablette le réduit au décodage.
  const png = truncated ? null : (shrinkPng(data, IMAGE_SIDE) ?? (data.subarray(1, 4).toString("ascii") === "PNG" ? data : null));
  if (!png) return false;
  // Écrit à côté puis renommé : une lecture en même temps ne sert jamais une image à moitié écrite.
  const tmp = `${out}.${process.pid}.tmp`;
  await fs.writeFile(tmp, png);
  await fs.rename(tmp, out);
  return true;
}
