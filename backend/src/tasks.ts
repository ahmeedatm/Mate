import fs from "node:fs/promises";
import path from "node:path";
import { randomUUID } from "node:crypto";
import { backendRoot, config } from "./config.ts";
import { jiraConfigured, worklogSecondsOn } from "./jira.ts";

/**
 * Tâches : quotidiennes (définies dans config/tasks.json, ex. le worklog Jira) et ponctuelles
 * (ajoutées depuis la tablette ou par Claude). Tout est stocké sur l'ordinateur ; la tablette affiche.
 */

type DailyTask = {
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

type OneOffTask = { id: string; label: string; createdMs: number; doneMs?: number };
type Store = { done: Record<string, string[]>; items: OneOffTask[] };

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
};

const tasksFile = path.join(backendRoot, "config", "tasks.json");
const storeFile = path.join(config.dataDir, "tasks.json");

const isoDay = (d: Date) =>
  `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
const minutesOf = (hhmm: string) => {
  const [h, m] = hhmm.split(":").map(Number);
  return h * 60 + m;
};

/**
 * Relu à chaque appel : une modification du fichier est prise en compte sans redémarrer.
 * Pas de fichier : pas de tâche quotidienne (config/tasks.example.json n'est qu'un modèle).
 */
async function loadDaily(): Promise<DailyTask[]> {
  const raw = await fs.readFile(tasksFile, "utf8").catch(() => "{}");
  return (JSON.parse(raw) as { daily?: DailyTask[] }).daily ?? [];
}

async function loadStore(): Promise<Store> {
  const raw = await fs.readFile(storeFile, "utf8").catch(() => undefined);
  return raw ? (JSON.parse(raw) as Store) : { done: {}, items: [] };
}

async function saveStore(store: Store) {
  await fs.mkdir(config.dataDir, { recursive: true });
  await fs.writeFile(storeFile, JSON.stringify(store, null, 2));
}

const appliesToday = (t: DailyTask, now: Date) => !t.days || t.days.includes(now.getDay() || 7);

function formatHours(seconds: number) {
  const h = Math.floor(seconds / 3600);
  const m = Math.round((seconds % 3600) / 60);
  return m ? `${h} h ${String(m).padStart(2, "0")}` : `${h} h`;
}

async function viewDaily(t: DailyTask, store: Store, now: Date, refresh: boolean): Promise<TaskView> {
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
  return { id: t.id, label: t.label, kind: "daily", done, auto, detail, hasUrl: Boolean(t.url), remindAt: t.remindAt ?? null };
}

export async function listTasks(refresh = false): Promise<TaskView[]> {
  const now = new Date();
  const [daily, store] = await Promise.all([loadDaily(), loadStore()]);
  const views = await Promise.all(daily.filter((t) => appliesToday(t, now)).map((t) => viewDaily(t, store, now, refresh)));
  const startOfDay = new Date(now).setHours(0, 0, 0, 0);
  for (const item of store.items) {
    // Une tâche ponctuelle cochée disparaît le lendemain.
    if (item.doneMs && item.doneMs < startOfDay) continue;
    views.push({ id: item.id, label: item.label, kind: "oneoff", done: Boolean(item.doneMs), auto: false, detail: null, hasUrl: false, remindAt: null });
  }
  return views;
}

export async function addTask(label: string): Promise<TaskView> {
  const store = await loadStore();
  const item = { id: randomUUID().slice(0, 8), label: label.trim(), createdMs: Date.now() };
  store.items.push(item);
  await saveStore(store);
  return { id: item.id, label: item.label, kind: "oneoff", done: false, auto: false, detail: null, hasUrl: false, remindAt: null };
}

/** Renvoie false si la tâche n'existe pas. */
export async function setTaskDone(id: string, done: boolean): Promise<boolean> {
  const store = await loadStore();
  const item = store.items.find((i) => i.id === id);
  if (item) {
    item.doneMs = done ? Date.now() : undefined;
  } else {
    if (!(await loadDaily()).some((t) => t.id === id)) return false;
    const day = isoDay(new Date());
    const ids = new Set(store.done[day] ?? []);
    if (done) ids.add(id);
    else ids.delete(id);
    // On ne garde que la semaine en cours : l'historique n'intéresse pas la tablette.
    const keep = isoDay(new Date(Date.now() - 7 * 86_400_000));
    store.done = Object.fromEntries(Object.entries(store.done).filter(([d]) => d >= keep));
    store.done[day] = [...ids];
  }
  await saveStore(store);
  return true;
}

export async function deleteTask(id: string): Promise<boolean> {
  const store = await loadStore();
  const before = store.items.length;
  store.items = store.items.filter((i) => i.id !== id);
  if (store.items.length === before) return false;
  await saveStore(store);
  return true;
}

export async function taskUrl(id: string): Promise<string | undefined> {
  return (await loadDaily()).find((t) => t.id === id)?.url;
}

/**
 * Rappels : chaque minute, une tâche quotidienne non faite dont l'heure est passée est rappelée
 * sur la tablette (bandeau + voix), puis toutes les `repeatMinutes`, jusqu'à `remindUntil`.
 * `notify` renvoie false si la tablette n'a pas reçu le rappel : on réessaie à la minute suivante.
 */
export function startReminders(notify: (text: string) => Promise<boolean>) {
  const last = new Map<string, number>(); // "jour|id" -> dernier rappel
  const tick = async () => {
    const now = new Date();
    const minute = now.getHours() * 60 + now.getMinutes();
    const daily = await loadDaily().catch(() => []);
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
  };
  setInterval(() => void tick().catch(() => undefined), 60_000);
}
