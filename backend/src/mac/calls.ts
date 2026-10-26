import { osascript } from "./run.ts";

/**
 * Appel en cours dans une appli de visio (Teams) lu par l'API d'Accessibilité, comme Discord sous Windows.
 * Teams (moteur web Edge) n'expose ses boutons qu'une fois `AXEnhancedUserInterface` posé sur l'appli : le script
 * le pose à chaque lecture (sans effet visible), l'arbre arrive à la lecture suivante. Les boutons de l'appel
 * (« Couper le micro », « Partager », « Quitter »…) changent de nom selon l'état : c'est ce qui donne micro et partage.
 * Appelé par l'API C (AXUIElement…) plutôt que System Events : 34 ms au lieu de 2 s pour la fenêtre d'appel.
 */
const LEAVE_SOURCE = String.raw`^(Quitter|Raccrocher|Leave|Hang up)\b`;

const SCRIPT = `ObjC.import("AppKit");
ObjC.import("ApplicationServices");
function attr(el, name) {
  const r = Ref();
  return $.AXUIElementCopyAttributeValue(el, $(name), r) === 0 ? r[0] : null;
}
function str(el, name) {
  const v = attr(el, name);
  if (!v) return "";
  const o = ObjC.castRefToObject(v);
  return typeof o.js === "string" ? o.js : "";
}
function list(el, name) {
  const v = attr(el, name);
  if (!v) return [];
  const a = ObjC.castRefToObject(v), out = [];
  for (let i = 0; i < a.count; i++) out.push(a.objectAtIndex(i));
  return out;
}
function run(argv) {
  const [appName, pressJson] = argv;
  const apps = $.NSWorkspace.sharedWorkspace.runningApplications;
  let pid = 0;
  for (let i = 0; i < apps.count; i++) {
    const a = apps.objectAtIndex(i);
    if (a.localizedName.js === appName) pid = a.processIdentifier;
  }
  if (!pid) return JSON.stringify(null);
  const app = $.AXUIElementCreateApplication(pid);
  $.AXUIElementSetAttributeValue(app, $("AXEnhancedUserInterface"), $.kCFBooleanTrue);
  const press = pressJson ? JSON.parse(pressJson) : null;
  const windows = [];
  for (const w of list(app, "AXWindows")) {
    const buttons = [], stack = [w];
    while (stack.length) {
      const e = stack.pop();
      if (str(e, "AXRole") === "AXButton") {
        const name = str(e, "AXDescription") || str(e, "AXTitle");
        if (name) buttons.push({ name, e });
      }
      for (const k of list(e, "AXChildren")) stack.push(k);
    }
    windows.push({ title: str(w, "AXTitle"), buttons });
  }
  if (!press) return JSON.stringify({ windows: windows.map((w) => ({ title: w.title, buttons: w.buttons.map((b) => b.name) })) });
  // Un bouton « Partager » existe aussi dans les conversations : d'abord la fenêtre de l'appel.
  const leave = /${LEAVE_SOURCE}/i;
  const order = windows.filter((w) => w.buttons.some((b) => leave.test(b.name))).concat(windows);
  for (const w of order) {
    const b = w.buttons.find((b) => press.some((p) => b.name === p || b.name.startsWith(p + " ")));
    if (b) {
      $.AXUIElementPerformAction(b.e, $("AXPress"));
      return JSON.stringify({ pressed: b.name });
    }
  }
  return JSON.stringify({ pressed: null });
}`;

const LEAVE = new RegExp(LEAVE_SOURCE, "i");
const MIC_ON = /^(Couper|Désactiver) (le )?micro|^Mute\b/i;
const MIC_OFF = /^(Activer|Réactiver|Rétablir) (le )?micro|^Unmute\b/i;
const SHARING = /^Arrêter (le partage|la présentation)|^Stop (sharing|presenting)/i;

export type CallState = {
  found: boolean;
  connected: boolean;
  streaming: boolean;
  channel: string | null;
  people: string[];
  muted: boolean | null;
  deafened: boolean | null;
};

// /deck/live et /deck/voice lisent la même chose à quelques ms d'écart : une lecture sert aux deux.
const cache = new Map<string, { at: number; value: Promise<CallState | null> }>();

export function callState(app: string): Promise<CallState | null> {
  const hit = cache.get(app);
  if (hit && Date.now() - hit.at < 1500) return hit.value;
  const value = readCall(app);
  cache.set(app, { at: Date.now(), value });
  value.catch(() => cache.delete(app));
  return value;
}

async function readCall(app: string): Promise<CallState | null> {
  const raw = JSON.parse(await osascript(SCRIPT, [app], "JavaScript")) as { windows: { title: string; buttons: string[] }[] } | null;
  if (!raw) return null;
  const call = raw.windows.find((w) => w.buttons.some((b) => LEAVE.test(b)));
  if (!call)
    return { found: raw.windows.length > 0, connected: false, streaming: false, channel: null, people: [], muted: null, deafened: null };
  // « Affichage compact de réunion | Ahmed ATMANE » → « Ahmed ATMANE ».
  const channel =
    call.title
      .replace(/\s*\|\s*Microsoft Teams$/i, "")
      .split(" | ")
      .pop()
      ?.trim() || null;
  const muted = call.buttons.some((b) => MIC_OFF.test(b)) ? true : call.buttons.some((b) => MIC_ON.test(b)) ? false : null;
  return { found: true, connected: true, streaming: call.buttons.some((b) => SHARING.test(b)), channel, people: [], muted, deafened: null };
}

/** Actionne le premier bouton dont le nom (ou son début) est dans `names` ; null si aucun n'est là. */
export async function pressButton(app: string, names: string[]): Promise<string | null> {
  const raw = JSON.parse(await osascript(SCRIPT, [app, JSON.stringify(names)], "JavaScript")) as { pressed: string | null } | null;
  cache.delete(app);
  return raw?.pressed ?? null;
}
