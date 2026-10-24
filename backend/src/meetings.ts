import fs from "node:fs/promises";
import path from "node:path";
import { config } from "./config.ts";
import { dayRange, getEvents, type CalendarEvent } from "./calendar.ts";
import { openTarget } from "./os.ts";

// Réunions Teams de l'agenda : bouton « Rejoindre » de la tablette et ouverture automatique
// 1 minute avant (option, désactivée par défaut). Teams affiche d'abord son écran d'avant-réunion
// (micro, caméra) : rien n'est diffusé sans un clic sur « Rejoindre maintenant ».

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

/** Ouvre la réunion dans l'appli Teams (lien msteams:), sinon dans le navigateur. */
async function openTeams(url: string) {
  const app = url.replace(/^https:\/\/teams\.microsoft\.com\//, "msteams:/");
  if (app !== url) {
    try {
      await openTarget(app);
      return;
    } catch {
      // Teams absent ou lien non pris en charge : le navigateur propose d'ouvrir Teams ou le web.
    }
  }
  await openTarget(url);
}

/** Rejoint une réunion du jour d'après son identifiant d'agenda. */
export async function joinMeeting(id: string): Promise<CalendarEvent | undefined> {
  const { events } = await getEvents(...dayRange(1));
  const event = events.find((e) => e.id === id);
  if (!event?.joinUrl) return undefined;
  await openTeams(event.joinUrl);
  return event;
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
    await openTeams(event.joinUrl!);
    await notify(`Teams ouvert : ${event.title}`);
  };
  setInterval(() => void tick().catch(() => undefined), 15_000);
}
