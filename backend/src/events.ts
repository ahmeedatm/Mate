import fs from "node:fs/promises";
import path from "node:path";
import { randomUUID } from "node:crypto";
import { config } from "./config.ts";
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

async function load(): Promise<LocalEvent[]> {
  const raw = await fs.readFile(storeFile, "utf8").catch(() => undefined);
  return raw ? ((JSON.parse(raw) as { items?: LocalEvent[] }).items ?? []) : [];
}

async function save(items: LocalEvent[]) {
  // Les événements finis depuis plus d'un mois ne servent plus.
  const keep = items.filter((e) => e.endMs > Date.now() - KEEP_MS).sort((a, b) => a.startMs - b.startMs);
  await fs.mkdir(config.dataDir, { recursive: true });
  await fs.writeFile(storeFile, JSON.stringify({ items: keep }, null, 2) + "\n");
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
  const items = await load();
  const event = normalize(input, randomUUID().slice(0, 8));
  items.push(event);
  await save(items);
  return event;
}

/** Renvoie undefined si l'événement n'existe pas. Le rappel est réarmé (nouvelle heure). */
export async function updateEvent(id: string, input: EventInput): Promise<LocalEvent | undefined> {
  const items = await load();
  const i = items.findIndex((e) => e.id === id);
  if (i < 0) return undefined;
  items[i] = normalize(input, id);
  await save(items);
  return items[i];
}

export async function deleteEvent(id: string): Promise<boolean> {
  const items = await load();
  const left = items.filter((e) => e.id !== id);
  if (left.length === items.length) return false;
  await save(left);
  return true;
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
export function startEventReminders(notify: (text: string) => Promise<boolean>) {
  const hhmm = (ms: number) => new Date(ms).toLocaleTimeString("fr-FR", { hour: "2-digit", minute: "2-digit" });
  const tick = async () => {
    const now = Date.now();
    const items = await load();
    const due = items.filter(
      (e) => e.remindBefore != null && !e.remindedMs && e.startMs - e.remindBefore * 60_000 <= now && now < e.startMs + 60 * 60_000,
    );
    for (const e of due) {
      const minutes = Math.round((e.startMs - now) / 60_000);
      const text = minutes > 0 ? `Dans ${minutes} min : ${e.title}` : `${hhmm(e.startMs)} : ${e.title}`;
      if (!(await notify(text))) continue;
      e.remindedMs = now;
      await save(items);
    }
  };
  setInterval(() => void tick().catch(() => undefined), 30_000);
}
