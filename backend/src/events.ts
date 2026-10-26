import path from "node:path";
import { randomUUID } from "node:crypto";
import { config } from "./config.ts";
import { logFailure } from "./log.ts";
import { readJson, updateJson } from "./store.ts";
import type { CalendarEvent } from "./calendar.ts";

/**
 * Agenda personnel, rempli depuis la tablette (« Mardi 19:00 : truc à faire »), sans Outlook ni Google.
 * Gardé sur l'ordinateur (.data/events.json) et mêlé aux autres agendas dans getEvents ; rappel parlé facultatif.
 */

export type LocalEvent = {
  id: string;
  title: string;
  startMs: number;
  endMs: number;
  allDay: boolean;
  /** Rappel parlé : minutes avant le début (0 = à l'heure) ; absent : pas de rappel. */
  remindBefore?: number;
  remindedMs?: number;
};

export type EventInput = { title: string; startMs: number; endMs?: number; allDay: boolean; remindBefore?: number | null };

const storeFile = path.join(config.dataDir, "events.json");
const KEEP_MS = 30 * 86_400_000;
const DEFAULT_DURATION_MS = 60 * 60_000;

type Store = { items?: LocalEvent[] };
const emptyStore = (): Store => ({ items: [] });

async function load(): Promise<LocalEvent[]> {
  return (await readJson<Store>(storeFile, emptyStore)).items ?? [];
}

/** Relit le fichier, applique `change` à la liste, réécrit : une mise à jour faite pendant ce temps n'est pas perdue. */
function edit<R>(change: (items: LocalEvent[]) => R): Promise<R> {
  return updateJson<Store, R>(storeFile, emptyStore, (data) => {
    const items = data.items ?? [];
    const result = change(items);
    // Les événements finis depuis plus d'un mois ne servent plus.
    data.items = items.filter((e) => e.endMs > Date.now() - KEEP_MS).sort((a, b) => a.startMs - b.startMs);
    return result;
  });
}

/** Journée entière : de minuit au minuit suivant (heure de l'ordinateur) ; sinon une heure par défaut. */
function normalize(input: EventInput, id: string): LocalEvent {
  let { startMs, endMs } = input;
  if (input.allDay) {
    const day = new Date(startMs);
    day.setHours(0, 0, 0, 0);
    startMs = day.getTime();
    endMs = new Date(day).setDate(day.getDate() + 1);
  } else if (!endMs || endMs <= startMs) {
    endMs = startMs + DEFAULT_DURATION_MS;
  }
  const event: LocalEvent = { id, title: input.title.trim(), startMs, endMs, allDay: input.allDay };
  if (input.remindBefore != null && !input.allDay) event.remindBefore = input.remindBefore;
  return event;
}

export async function addEvent(input: EventInput): Promise<LocalEvent> {
  const event = normalize(input, randomUUID().slice(0, 8));
  await edit((items) => void items.push(event));
  return event;
}

/** Renvoie undefined si l'événement n'existe pas. Le rappel est réarmé (nouvelle heure). */
export async function updateEvent(id: string, input: EventInput): Promise<LocalEvent | undefined> {
  return edit((items) => {
    const i = items.findIndex((e) => e.id === id);
    if (i < 0) return undefined;
    items[i] = normalize(input, id);
    return items[i];
  });
}

export async function deleteEvent(id: string): Promise<boolean> {
  return edit((items) => {
    const i = items.findIndex((e) => e.id === id);
    if (i >= 0) items.splice(i, 1);
    return i >= 0;
  });
}

/** Événements qui touchent [from, to], au format des autres agendas (`localId` : modifiable depuis la tablette). */
export async function localEvents(from: Date, to: Date): Promise<CalendarEvent[]> {
  return (await load())
    .filter((e) => e.endMs > from.getTime() && e.startMs <= to.getTime())
    .map((e) => ({
      id: `local-${e.id}@${e.startMs}`,
      title: e.title,
      location: "",
      startMs: e.startMs,
      endMs: e.endMs,
      allDay: e.allDay,
      free: false,
      localId: e.id,
      remindBefore: e.remindBefore,
    }));
}

/**
 * Chaque minute : rappel parlé des événements dont l'heure (moins `remindBefore`) est arrivée, une fois.
 * `notify` renvoie false si la tablette n'a pas reçu le rappel : réessayé à la minute suivante (pas après le début + 1 h).
 */
export async function eventReminderTick(notify: (text: string) => Promise<boolean>) {
  const hhmm = (ms: number) => new Date(ms).toLocaleTimeString("fr-FR", { hour: "2-digit", minute: "2-digit" });
  const now = Date.now();
  const items = await load();
  const due = items.filter(
    (e) => e.remindBefore != null && !e.remindedMs && e.startMs - e.remindBefore * 60_000 <= now && now < e.startMs + 60 * 60_000,
  );
  for (const e of due) {
    const minutes = Math.round((e.startMs - now) / 60_000);
    const text = minutes > 0 ? `Dans ${minutes} min : ${e.title}` : `${hhmm(e.startMs)} : ${e.title}`;
    if (!(await notify(text))) continue;
    // Le fichier a pu changer pendant l'envoi : on le relit et on n'y marque que ce rappel.
    await edit((current) => {
      const item = current.find((x) => x.id === e.id);
      if (item) item.remindedMs = now;
    });
  }
}

export function startEventReminders(notify: (text: string) => Promise<boolean>) {
  setInterval(() => void eventReminderTick(notify).catch((e) => logFailure("Rappels d'agenda", e)), 30_000);
}
