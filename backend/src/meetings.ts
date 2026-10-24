import fs from "node:fs/promises";
import path from "node:path";
import { config } from "./config.ts";
import { dayRange, getEvents, type CalendarEvent } from "./calendar.ts";
import { meetingKind, type MeetingKind } from "./meeting-links.ts";
import { openTarget } from "./os.ts";

// Réunions en ligne de l'agenda (Teams, Google Meet, Zoom, Webex) : bouton « Rejoindre » de la tablette et
// ouverture automatique 1 minute avant (option, désactivée par défaut). Chaque service affiche d'abord son écran
// d'avant-réunion (micro, caméra) : rien n'est diffusé sans un clic sur « Rejoindre ».

const storeFile = path.join(config.dataDir, "meetings.json");
const AUTO_JOIN_BEFORE_MS = 60_000;

export async function autoJoinEnabled(): Promise<boolean> {
  try {
    return JSON.parse(await fs.readFile(storeFile, "utf8")).autoJoin === true;
  } catch {
    return false;
  }
}

export async function setAutoJoin(enabled: boolean) {
  await fs.mkdir(config.dataDir, { recursive: true });
  await fs.writeFile(storeFile, JSON.stringify({ autoJoin: enabled }, null, 2) + "\n");
}

/** Ouvre le lien dans l'appli du service si possible (Teams : msteams:, Zoom : zoommtg:), sinon dans le navigateur. */
async function openMeeting(url: string) {
  const app = appLink(url);
  if (app) {
    try {
      await openTarget(app);
      return;
    } catch {
      // Appli absente ou lien non pris en charge : le navigateur propose d'ouvrir l'appli ou la version web.
    }
  }
  await openTarget(url);
}

function appLink(url: string): string | undefined {
  if (meetingKind(url) === "teams") return url.replace(/^https:\/\/teams\.microsoft\.com\//, "msteams:/");
  const zoom = /^https:\/\/(?:[\w-]+\.)?zoom\.us\/j\/(\d+)(?:\?pwd=([\w.-]+))?/.exec(url);
  if (zoom) return `zoommtg://zoom.us/join?confno=${zoom[1]}${zoom[2] ? `&pwd=${zoom[2]}` : ""}`;
  return undefined;
}

const SERVICE_NAMES: Record<MeetingKind, string> = { teams: "Teams", meet: "Google Meet", zoom: "Zoom", webex: "Webex" };

/** Rejoint une réunion du jour d'après son identifiant d'agenda. */
export async function joinMeeting(id: string): Promise<{ event: CalendarEvent; kind?: MeetingKind } | undefined> {
  const { events } = await getEvents(...dayRange(1));
  const event = events.find((e) => e.id === id);
  if (!event?.joinUrl) return undefined;
  await openMeeting(event.joinUrl);
  return { event, kind: meetingKind(event.joinUrl) };
}

/** Toutes les 15 s : ouvre la réunion qui commence dans la minute, une fois, si l'option est cochée. */
export function startAutoJoin(notify: (text: string) => Promise<unknown>) {
  const joined = new Set<string>();
  const tick = async () => {
    if (!(await autoJoinEnabled())) return;
    const now = Date.now();
    const { events } = await getEvents(...dayRange(1));
    // Deux réunions au même moment : seule la première est ouverte.
    const event = events.find(
      (e) => e.joinUrl && !e.allDay && !e.free && e.startMs - AUTO_JOIN_BEFORE_MS <= now && now < e.startMs,
    );
    if (!event || joined.has(String(event.startMs))) return;
    joined.add(String(event.startMs));
    await openMeeting(event.joinUrl!);
    const kind = meetingKind(event.joinUrl!);
    await notify(`${kind ? SERVICE_NAMES[kind] : "Réunion"} ouvert : ${event.title}`);
  };
  setInterval(() => void tick().catch(() => undefined), 15_000);
}
