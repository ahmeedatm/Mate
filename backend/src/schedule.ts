import path from "node:path";
import { createHash, randomUUID } from "node:crypto";
import { config } from "./config.ts";
import { findTile, isPush, runAction, type Tile } from "./deck.ts";
import { logFailure } from "./log.ts";
import { canControl } from "./os.ts";
import { isScreenLocked } from "./screen-lock.ts";
import { readJson, updateJson } from "./store.ts";

/**
 * Programmations : une touche du Deck lancée à une heure fixe (`at`, certains jours) ou toutes les `everyMinutes`.
 * Ça tourne ici, sur l'ordinateur, sans la tablette. Aucune programmation au départ : rien ne part tant qu'on n'en
 * crée pas. Seules les touches du deck peuvent être programmées (pas d'action libre venue de la tablette).
 */

export type Schedule = {
  id: string;
  tileId: string;
  /** Empreinte de la touche à la création : les identifiants des pages Perso sont positionnels, un deck réordonné ne doit pas lancer une autre touche. */
  tileSig?: string;
  enabled: boolean;
  createdMs: number;
  /** Heure fixe "HH:MM", les jours `days` (1 = lundi … 7 = dimanche ; défaut : tous). */
  at?: string;
  days?: number[];
  /** Sinon : toutes les N minutes, depuis la création puis le dernier lancement. */
  everyMinutes?: number;
  lastMs?: number;
};

export type ScheduleInput = { tileId: string; tileSig?: string; at?: string; days?: number[]; everyMinutes?: number };

type Store = { items: Schedule[] };

export const MAX_SCHEDULES = 20;
/** Après une veille, une heure fixe manquée de plus de 5 minutes saute : « 18 h, muet » à 20 h n'a plus de sens. */
const CATCH_UP_MS = 5 * 60_000;

const storeFile = path.join(config.dataDir, "schedule.json");
const emptyStore = (): Store => ({ items: [] });
const minutesOf = (hhmm: string) => {
  const [h, m] = hhmm.split(":").map(Number);
  return h * 60 + m;
};

export async function listSchedule(): Promise<Schedule[]> {
  return (await readJson<Partial<Store>>(storeFile, emptyStore)).items ?? [];
}

const edit = <R>(change: (store: Store) => R) =>
  updateJson<Store, R>(storeFile, emptyStore, (store) => {
    store.items ??= [];
    return change(store);
  });

/** Crée une programmation (active). Lève si le plafond est atteint. */
export async function addSchedule(input: ScheduleInput, now = new Date()): Promise<Schedule> {
  const days = input.days?.length ? [...new Set(input.days)].sort() : undefined;
  const item: Schedule = {
    id: randomUUID().slice(0, 8),
    tileId: input.tileId,
    tileSig: input.tileSig,
    enabled: true,
    createdMs: now.getTime(),
    ...(input.at ? { at: input.at, days: days && days.length < 7 ? days : undefined } : { everyMinutes: input.everyMinutes }),
  };
  await edit((store) => {
    if (store.items.length >= MAX_SCHEDULES) throw Object.assign(new Error(`${MAX_SCHEDULES} programmations au plus`), { statusCode: 409 });
    store.items.push(item);
  });
  return item;
}

/** Renvoie false si la programmation n'existe pas. */
export const setScheduleEnabled = (id: string, enabled: boolean) =>
  edit((store) => {
    const item = store.items.find((i) => i.id === id);
    if (item) {
      item.enabled = enabled;
      // Réactivée, elle repart de maintenant : un intervalle ne se lance pas aussitôt à cause d'un vieux dernier lancement.
      if (enabled) item.lastMs = Date.now();
    }
    return Boolean(item);
  });

export const deleteSchedule = (id: string) =>
  edit((store) => {
    const before = store.items.length;
    store.items = store.items.filter((i) => i.id !== id);
    return store.items.length !== before;
  });

/** Vrai si la programmation doit partir à `now`, d'après son dernier lancement. */
export function isDue(item: Schedule, now: Date): boolean {
  if (!item.enabled) return false;
  const last = item.lastMs ?? item.createdMs;
  if (item.everyMinutes) return now.getTime() - last >= item.everyMinutes * 60_000;
  if (!item.at) return false;
  if (item.days && !item.days.includes(now.getDay() || 7)) return false;
  const slot = new Date(now);
  slot.setHours(Math.floor(minutesOf(item.at) / 60), minutesOf(item.at) % 60, 0, 0);
  const since = now.getTime() - slot.getTime();
  return since >= 0 && since < CATCH_UP_MS && last < slot.getTime();
}

/**
 * Chaque minute : lance les touches dues. Le lancement est noté avant l'exécution : une touche qui échoue ou qui traîne
 * n'est pas rejouée à la minute suivante (échec : journal, prochaine occasion normale).
 */
export async function scheduleTick(run: (tileId: string, tileSig?: string) => Promise<void>, now = new Date()) {
  const due = (await listSchedule()).filter((i) => isDue(i, now));
  if (!due.length) return;
  await edit((store) => {
    for (const item of store.items) if (due.some((d) => d.id === item.id)) item.lastMs = now.getTime();
  });
  for (const item of due) {
    try {
      await run(item.tileId, item.tileSig);
    } catch (e) {
      logFailure(`Programmation de la touche ${item.tileId}`, e);
    }
  }
}

export const tileSignature = (tile: Tile) =>
  createHash("sha256")
    .update(JSON.stringify([tile.label, tile.action]))
    .digest("hex")
    .slice(0, 16);

/** Lance la touche du deck : relue à chaque fois (elle a pu être supprimée, changée de place ou devenir une touche à maintenir). */
export async function runTile(tileId: string, tileSig?: string) {
  const tile = await findTile(tileId);
  if (!tile) throw new Error("touche introuvable dans le deck");
  if (tileSig && tileSignature(tile) !== tileSig) throw new Error("la touche n'est plus la même dans le deck : programmation à refaire");
  if (isPush(tile)) throw new Error("touche à maintenir : pas programmable");
  await runAction(tile.action, tile.id);
}

/** Écran verrouillé : rien ne part (une touche de raccourci ou de texte irait dans l'écran de verrouillage) ; un intervalle repart au déverrouillage, une heure fixe manquée saute. */
export function startSchedule() {
  if (!canControl) return;
  setInterval(() => {
    if (!isScreenLocked()) void scheduleTick(runTile).catch((e) => logFailure("Programmations", e));
  }, 60_000);
}
