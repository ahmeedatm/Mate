import path from "node:path";
import { randomUUID } from "node:crypto";
import { backendRoot, config } from "./config.ts";
import { jiraConfigured, worklogSecondsOn } from "./jira.ts";
import { logFailure } from "./log.ts";
import { readJson, updateJson } from "./store.ts";

/**
 * Tâches : récurrentes (créées depuis la tablette, ou écrites dans config/tasks.json comme le worklog Jira) et
 * ponctuelles (tablette ou Claude), avec rappel parlé sur la tablette à l'heure choisie. Tout est stocké sur
 * l'ordinateur ; la tablette affiche.
 */

export type DailyTask = {
  id: string;
  label: string;
  /** Jours de la semaine, 1 = lundi … 7 = dimanche (défaut : tous). */
  days?: number[];
  /** Heure du premier rappel sur la tablette ("HH:MM"), puis toutes les `repeatMinutes` jusqu'à `remindUntil`. */
  remindAt?: string;
  repeatMinutes?: number;
  remindUntil?: string;
  /** Page ouverte sur l'ordinateur par le bouton de la tablette. */
  url?: string;
  /** Cochée automatiquement quand ce nombre d'heures est saisi dans Jira (si Jira est configuré). */
  jiraWorklogHours?: number;
};

/** `remindAtMs` : rappel unique (date et heure) ; `remindedMs` : rappel déjà fait. */
export type OneOffTask = { id: string; label: string; createdMs: number; doneMs?: number; remindAtMs?: number; remindedMs?: number };
/** `recurring` : tâches récurrentes créées depuis la tablette (mêmes champs que config/tasks.json). */
type Store = { done: Record<string, string[]>; items: OneOffTask[]; recurring?: DailyTask[] };

export type TaskView = {
  id: string;
  label: string;
  kind: "daily" | "oneoff";
  done: boolean;
  /** Cochée par Jira : pas décochable à la main. */
  auto: boolean;
  detail: string | null;
  hasUrl: boolean;
  remindAt: string | null;
  /** Fréquence et rappel lisibles : « Lun–ven · 17:30 », « Rappel à 15:00 ». */
  schedule: string | null;
  /** Supprimable depuis la tablette (pas une tâche de config/tasks.json). */
  deletable: boolean;
};

const tasksFile = path.join(backendRoot, "config", "tasks.json");
const storeFile = path.join(config.dataDir, "tasks.json");

const isoDay = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
const minutesOf = (hhmm: string) => {
  const [h, m] = hhmm.split(":").map(Number);
  return h * 60 + m;
};

/**
 * Tâches récurrentes : celles de config/tasks.json (relu à chaque appel : modifiable sans redémarrer ; pas de fichier,
 * pas de tâche : config/tasks.example.json n'est qu'un modèle), puis celles créées depuis la tablette.
 */
async function loadConfigDaily(): Promise<DailyTask[]> {
  return (await readJson<{ daily?: DailyTask[] }>(tasksFile, () => ({}), { quarantine: false })).daily ?? [];
}

async function loadDaily(store?: Store): Promise<DailyTask[]> {
  return [...(await loadConfigDaily()), ...((store ?? (await loadStore())).recurring ?? [])];
}

const emptyStore = (): Store => ({ done: {}, items: [] });

async function loadStore(): Promise<Store> {
  const store = await readJson<Partial<Store>>(storeFile, emptyStore);
  return { ...store, done: store.done ?? {}, items: store.items ?? [] };
}

/** Relit le fichier, applique `change`, réécrit : une mise à jour faite pendant ce temps n'est pas perdue. */
const editStore = <R>(change: (store: Store) => R | Promise<R>) =>
  updateJson<Store, R>(storeFile, emptyStore, (store) => {
    store.done ??= {};
    store.items ??= [];
    return change(store);
  });

const appliesToday = (t: DailyTask, now: Date) => !t.days || t.days.includes(now.getDay() || 7);

const DAY_NAMES = ["lun", "mar", "mer", "jeu", "ven", "sam", "dim"];
const hhmm = (d: Date) => `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;

/** « Tous les jours », « Lun–ven », « Week-end », « Lun, mer, ven », suivi de l'heure du rappel. */
function describeDays(t: DailyTask): string {
  const days = [...new Set(t.days ?? [1, 2, 3, 4, 5, 6, 7])].sort();
  const key = days.join("");
  const text =
    key === "1234567"
      ? "Tous les jours"
      : key === "12345"
        ? "Lun–ven"
        : key === "67"
          ? "Week-end"
          : days
              .map((d) => DAY_NAMES[d - 1])
              .join(", ")
              .replace(/^./, (c) => c.toUpperCase());
  return t.remindAt ? `${text} · ${t.remindAt}` : text;
}

function describeReminder(item: OneOffTask, now: Date): string | null {
  if (!item.remindAtMs) return null;
  const at = new Date(item.remindAtMs);
  const days = Math.round((new Date(at).setHours(0, 0, 0, 0) - new Date(now).setHours(0, 0, 0, 0)) / 86_400_000);
  const day = days === 0 ? "" : days === 1 ? "demain " : `le ${at.getDate()}/${at.getMonth() + 1} `;
  return `Rappel ${day}à ${hhmm(at)}`;
}

function formatHours(seconds: number) {
  const h = Math.floor(seconds / 3600);
  const m = Math.round((seconds % 3600) / 60);
  return m ? `${h} h ${String(m).padStart(2, "0")}` : `${h} h`;
}

async function viewDaily(t: DailyTask, store: Store, now: Date, refresh: boolean): Promise<TaskView> {
  const deletable = store.recurring?.some((r) => r.id === t.id) ?? false;
  let done = store.done[isoDay(now)]?.includes(t.id) ?? false;
  let auto = false;
  let detail: string | null = null;
  if (t.jiraWorklogHours && jiraConfigured) {
    try {
      const seconds = await worklogSecondsOn(now, refresh);
      detail = `${formatHours(seconds)} / ${t.jiraWorklogHours} h saisies dans Jira`;
      if (seconds >= t.jiraWorklogHours * 3600) done = auto = true;
    } catch (e) {
      detail = `Jira injoignable (${e instanceof Error ? e.message : e})`;
    }
  }
  return {
    id: t.id,
    label: t.label,
    kind: "daily",
    done,
    auto,
    detail,
    hasUrl: Boolean(t.url),
    remindAt: t.remindAt ?? null,
    schedule: describeDays(t),
    deletable,
  };
}

export async function listTasks(refresh = false): Promise<TaskView[]> {
  const now = new Date();
  const store = await loadStore();
  const daily = await loadDaily(store);
  const views = await Promise.all(daily.filter((t) => appliesToday(t, now)).map((t) => viewDaily(t, store, now, refresh)));
  const startOfDay = new Date(now).setHours(0, 0, 0, 0);
  for (const item of store.items) {
    // Une tâche ponctuelle cochée disparaît le lendemain.
    if (item.doneMs && item.doneMs < startOfDay) continue;
    views.push(viewOneOff(item, now));
  }
  return views;
}

function viewOneOff(item: OneOffTask, now: Date): TaskView {
  return {
    id: item.id,
    label: item.label,
    kind: "oneoff",
    done: Boolean(item.doneMs),
    auto: false,
    detail: null,
    hasUrl: false,
    remindAt: item.remindAtMs ? hhmm(new Date(item.remindAtMs)) : null,
    schedule: describeReminder(item, now),
    deletable: true,
  };
}

/**
 * Nouvelle tâche. `days` (1 = lundi … 7 = dimanche) : récurrente ces jours-là ; sinon ponctuelle. `remindAt` ("HH:MM") :
 * rappel parlé sur la tablette à cette heure (récurrente : puis toutes les 30 min pendant 2 h tant qu'elle n'est pas
 * faite ; ponctuelle : une fois, aujourd'hui, ou demain si l'heure est passée).
 */
export async function addTask(label: string, options: { remindAt?: string; days?: number[] } = {}): Promise<TaskView> {
  const now = new Date();
  const { remindAt, days } = options;
  if (days?.length) {
    const unique = [...new Set(days)].sort();
    const task: DailyTask = { id: `r-${randomUUID().slice(0, 8)}`, label: label.trim(), days: unique.length === 7 ? undefined : unique };
    if (remindAt) {
      task.remindAt = remindAt;
      task.repeatMinutes = 30;
      const until = Math.min(minutesOf(remindAt) + 120, 23 * 60 + 59);
      task.remindUntil = `${String(Math.floor(until / 60)).padStart(2, "0")}:${String(until % 60).padStart(2, "0")}`;
    }
    await editStore((s) => void (s.recurring ??= []).push(task));
    return viewDaily(task, await loadStore(), now, false);
  }
  const item: OneOffTask = { id: randomUUID().slice(0, 8), label: label.trim(), createdMs: Date.now() };
  if (remindAt) {
    const at = new Date(now);
    at.setHours(Math.floor(minutesOf(remindAt) / 60), minutesOf(remindAt) % 60, 0, 0);
    if (at.getTime() <= now.getTime()) at.setDate(at.getDate() + 1);
    item.remindAtMs = at.getTime();
  }
  await editStore((s) => void s.items.push(item));
  return viewOneOff(item, now);
}

/** Renvoie false si la tâche n'existe pas. */
export async function setTaskDone(id: string, done: boolean): Promise<boolean> {
  return editStore(async (store) => {
    const item = store.items.find((i) => i.id === id);
    if (item) {
      item.doneMs = done ? Date.now() : undefined;
      return true;
    }
    if (!(await loadDaily(store)).some((t) => t.id === id)) return false;
    const day = isoDay(new Date());
    const ids = new Set(store.done[day] ?? []);
    if (done) ids.add(id);
    else ids.delete(id);
    // On ne garde que la semaine en cours : l'historique n'intéresse pas la tablette.
    const keep = isoDay(new Date(Date.now() - 7 * 86_400_000));
    store.done = Object.fromEntries(Object.entries(store.done).filter(([d]) => d >= keep));
    store.done[day] = [...ids];
    return true;
  });
}

export async function deleteTask(id: string): Promise<boolean> {
  return editStore((store) => {
    const before = store.items.length + (store.recurring?.length ?? 0);
    store.items = store.items.filter((i) => i.id !== id);
    store.recurring = store.recurring?.filter((t) => t.id !== id);
    return store.items.length + (store.recurring?.length ?? 0) !== before;
  });
}

/** Toutes les tâches récurrentes, même celles qui ne tombent pas aujourd'hui (pour les retrouver et les supprimer). */
export async function listRecurring(): Promise<TaskView[]> {
  const store = await loadStore();
  const now = new Date();
  return Promise.all((await loadDaily(store)).map((t) => viewDaily(t, store, now, false)));
}

export async function taskUrl(id: string): Promise<string | undefined> {
  return (await loadDaily()).find((t) => t.id === id)?.url;
}

/**
 * Rappels : chaque minute, une tâche récurrente non faite dont l'heure est passée est rappelée
 * sur la tablette (bandeau + voix), puis toutes les `repeatMinutes`, jusqu'à `remindUntil` ; une tâche
 * ponctuelle l'est une fois, à son heure.
 * `notify` renvoie false si la tablette n'a pas reçu le rappel : on réessaie à la minute suivante.
 */
export async function reminderTick(notify: (text: string) => Promise<boolean>, last: Map<string, number>) {
  const now = new Date();
  const minute = now.getHours() * 60 + now.getMinutes();
  const daily = await loadDaily().catch(() => [] as DailyTask[]);
  for (const t of daily) {
    if (!t.remindAt || !appliesToday(t, now)) continue;
    if (minute < minutesOf(t.remindAt) || minute > minutesOf(t.remindUntil ?? "19:00")) continue;
    const key = `${isoDay(now)}|${t.id}`;
    const previous = last.get(key);
    if (previous !== undefined && (!t.repeatMinutes || now.getTime() - previous < t.repeatMinutes * 60_000)) continue;
    const view = await viewDaily(t, await loadStore(), now, false);
    if (view.done) continue;
    if (await notify(`À faire : ${t.label}`)) last.set(key, now.getTime());
  }
  const due = (await loadStore()).items.filter((i) => i.remindAtMs && !i.remindedMs && !i.doneMs && i.remindAtMs <= now.getTime());
  for (const item of due) {
    if (!(await notify(`Rappel : ${item.label}`))) continue;
    // Le fichier a pu changer pendant l'envoi : on le relit et on n'y marque que ce rappel.
    await editStore((store) => {
      const current = store.items.find((i) => i.id === item.id);
      if (current) current.remindedMs = now.getTime();
    });
  }
}

export function startReminders(notify: (text: string) => Promise<boolean>) {
  const last = new Map<string, number>(); // "jour|id" -> dernier rappel
  setInterval(() => void reminderTick(notify, last).catch((e) => logFailure("Rappels de tâches", e)), 60_000);
}
