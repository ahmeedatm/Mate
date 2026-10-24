import fs from "node:fs/promises";
import path from "node:path";
import ical, { type ParameterValue, type VEvent } from "node-ical";
import { config } from "./config.ts";
import { accountEvents, connectedAccounts, disconnect } from "./accounts.ts";
import { localEvents } from "./events.ts";
import { meetingLink } from "./meeting-links.ts";

export type CalendarEvent = {
  /** UID de l'événement + début : distingue les occurrences d'une réunion récurrente. */
  id: string;
  title: string;
  location: string;
  startMs: number;
  endMs: number;
  allDay: boolean;
  /** Marqué « Libre » dans Outlook (n'ouvre pas Teams automatiquement). */
  free: boolean;
  /** Lien de la réunion Teams, trouvé dans la description (gardé sur l'ordinateur). */
  joinUrl?: string;
  /** Événement de l'agenda personnel (src/events.ts) : modifiable depuis la tablette. */
  localId?: string;
  remindBefore?: number;
};

const CACHE_MS = 5 * 60_000;
let cache: { at: number; key: string; events: CalendarEvent[] } | undefined;

function text(value: ParameterValue | undefined): string {
  if (value === undefined || value === null) return "";
  return typeof value === "string" ? value : String((value as { val?: unknown }).val ?? "");
}

async function fetchIcs(source: string) {
  return /^https?:\/\//i.test(source) ? ical.async.fromURL(source) : ical.sync.parseFile(source);
}

/** Du début du jour à la fin du `days`-ième jour (même plage pour la tablette et l'ordinateur : un seul cache). */
export function dayRange(days: number): [Date, Date] {
  const from = new Date();
  from.setHours(0, 0, 0, 0);
  return [from, new Date(from.getTime() + days * 86_400_000 - 1)];
}

// Liens d'agenda collés dans la page locale de l'ordinateur (http://127.0.0.1:3000/), en plus de CALENDAR_ICS_URLS.
const savedFile = path.join(config.dataDir, "calendar.json");

export async function savedCalendarUrls(): Promise<string[]> {
  const raw = await fs.readFile(savedFile, "utf8").catch(() => undefined);
  return raw ? ((JSON.parse(raw) as { urls?: string[] }).urls ?? []) : [];
}

export async function saveCalendarUrls(urls: string[]) {
  await fs.mkdir(config.dataDir, { recursive: true });
  await fs.writeFile(savedFile, JSON.stringify({ urls }, null, 2) + "\n", { mode: 0o600 });
  cache = undefined;
}

/** Compte connecté ou déconnecté : l'agenda est relu tout de suite. */
export function invalidateCalendar() {
  cache = undefined;
}

/** Cet ordinateur a un agenda : lien ICS ou compte connecté (Microsoft, Google). */
export async function hasCalendar(): Promise<boolean> {
  return (await calendarSources()).length > 0 || (await connectedAccounts()).length > 0;
}

/** Sources d'agenda de cet ordinateur ; "demo" donne l'agenda d'exemple. */
export async function calendarSources(): Promise<string[]> {
  return [...new Set([...config.icsUrls, ...(await savedCalendarUrls())])];
}

/** Agenda relié, vu depuis la tablette : jamais le lien ICS lui-même (il donne accès à l'agenda). */
export type LinkedCalendar = { id: string; kind: "microsoft" | "google" | "ics"; label: string; detail: string; removable: boolean; expired: boolean };

/** Nom lisible d'un lien ICS d'après son hôte. */
function icsLabel(url: string): string {
  if (url === "demo") return "Agenda d'exemple";
  const host = URL.parse(url)?.host ?? "";
  if (/outlook|office365|live\.com/i.test(host)) return "Outlook";
  if (/google/i.test(host)) return "Google Agenda";
  if (/icloud/i.test(host)) return "Agenda iCloud";
  return host || "Lien d'agenda";
}

export async function linkedCalendars(): Promise<LinkedCalendar[]> {
  const accounts = (await connectedAccounts()).map((a): LinkedCalendar => ({
    id: a.provider, kind: a.provider, label: a.label, detail: a.email, removable: true, expired: a.expired,
  }));
  // Liens du fichier .env (développeur) : affichés, pas retirables depuis la tablette.
  const env = config.icsUrls.map((u, i): LinkedCalendar => ({
    id: `env-${i}`, kind: "ics", label: icsLabel(u), detail: "Lien ICS (réglage de l'ordinateur)", removable: false, expired: false,
  }));
  const saved = (await savedCalendarUrls()).map((u, i): LinkedCalendar => ({
    id: `ics-${i}`, kind: "ics", label: icsLabel(u), detail: u === "demo" ? "Exemple" : "Lien ICS", removable: true, expired: false,
  }));
  return [...accounts, ...env, ...saved];
}

/** Retire un agenda relié (compte ou lien ICS collé). Faux : rien à retirer sous cet identifiant. */
export async function unlinkCalendar(id: string): Promise<boolean> {
  if (id === "microsoft" || id === "google") {
    if (!(await connectedAccounts()).some((a) => a.provider === id)) return false;
    await disconnect(id);
  } else {
    const index = /^ics-(\d+)$/.exec(id)?.[1];
    const urls = await savedCalendarUrls();
    if (index === undefined || Number(index) >= urls.length) return false;
    urls.splice(Number(index), 1);
    await saveCalendarUrls(urls);
  }
  cache = undefined;
  return true;
}

type Source = "ics" | "mock" | "none" | "local";

/**
 * Événements entre `from` et `to` : agendas reliés (liens ICS, comptes) puis agenda personnel de la tablette.
 * `error` : agenda relié injoignable (l'agenda personnel s'affiche quand même).
 */
export async function getEvents(from: Date, to: Date): Promise<{ source: Source; events: CalendarEvent[]; error?: string }> {
  const local = await localEvents(from, to);
  let remote: { source: Source; events: CalendarEvent[] };
  try {
    remote = await remoteEvents(from, to);
  } catch (e) {
    if (!local.length) throw e;
    return { source: "local", events: local, error: (e as Error).message };
  }
  if (!local.length) return remote;
  const events = [...remote.events, ...local].sort((a, b) => a.startMs - b.startMs);
  return { source: remote.source === "none" ? "local" : remote.source, events };
}

/**
 * Agendas reliés entre `from` et `to`, récurrences dépliées : liens ICS et comptes connectés. Une source en échec
 * n'empêche pas les autres ; tout en échec : l'erreur remonte.
 */
async function remoteEvents(from: Date, to: Date): Promise<{ source: Source; events: CalendarEvent[] }> {
  const sources = await calendarSources();
  const accounts = await connectedAccounts();
  if (sources.length === 0 && accounts.length === 0) return { source: "none", events: [] };
  if (accounts.length === 0 && sources.every((s) => s === "demo")) return { source: "mock", events: mockEvents(from) };

  const key = `${sources.join(",")}|${accounts.map((a) => a.email + a.expired).join(",")}|${from.getTime()}-${to.getTime()}`;
  if (cache && cache.key === key && Date.now() - cache.at < CACHE_MS) return { source: "ics", events: cache.events };

  const events: CalendarEvent[] = [];
  const errors: Error[] = accounts
    .filter((a) => a.expired)
    .map((a) => new Error(`Compte ${a.label} à reconnecter (page du compagnon sur l'ordinateur)`));
  let read = 0;
  if (accounts.some((a) => !a.expired)) {
    try {
      events.push(...(await accountEvents(from, to)));
      read++;
    } catch (e) {
      errors.push(e as Error);
    }
  }
  for (const source of sources.filter((s) => s !== "demo")) {
    let data;
    try {
      data = await fetchIcs(source);
      read++;
    } catch (e) {
      errors.push(e as Error);
      continue;
    }
    for (const component of Object.values(data)) {
      if (!component || component.type !== "VEVENT") continue;
      const event = component as VEvent;
      if (event.status === "CANCELLED") continue;
      for (const instance of ical.expandRecurringEvent(event, { from, to, expandOngoing: true })) {
        if (instance.event.status === "CANCELLED") continue;
        const startMs = instance.start.getTime();
        events.push({
          id: `${instance.event.uid}@${startMs}`,
          title: text(instance.summary) || "(sans titre)",
          location: text(instance.event.location),
          startMs,
          endMs: (instance.end ?? instance.start).getTime(),
          allDay: instance.isFullDay,
          free: instance.event.transparency === "TRANSPARENT",
          joinUrl: meetingLink(text(instance.event.description), text(instance.event.location)),
        });
      }
    }
  }
  if (read === 0 && errors.length) throw errors[0];
  events.sort((a, b) => a.startMs - b.startMs);
  cache = { at: Date.now(), key, events };
  return { source: "ics", events };
}

function mockEvents(day: Date): CalendarEvent[] {
  const at = (h: number, m = 0) => {
    const d = new Date(day);
    d.setHours(h, m, 0, 0);
    return d.getTime();
  };
  const teams = "https://teams.microsoft.com/l/meetup-join/exemple";
  return [
    { title: "Daily équipe", location: "Teams", startMs: at(9, 30), endMs: at(9, 45), joinUrl: teams },
    { title: "Revue de tickets QA", location: "Salle Turing", startMs: at(11), endMs: at(12) },
    { title: "Déjeuner", location: "", startMs: at(12, 30), endMs: at(13, 30) },
    { title: "Point hackathon", location: "Teams", startMs: at(15), endMs: at(15, 30), joinUrl: teams },
    { title: "Focus : tests de non-régression", location: "", startMs: at(16), endMs: at(18) },
  ].map((e) => ({ ...e, id: `mock-${e.startMs}`, allDay: false, free: false }));
}
