import fs from "node:fs";
import { clipboardUnavailable } from "../src/driver-common.ts";
import { checkOpenTarget } from "../src/open-guard.ts";
import type { Perf } from "../src/perf.ts";
import type { MediaSession, VoiceState } from "../src/win/index.ts";
import type {
  AudioChange,
  ClipboardContent,
  Display,
  Layout,
  MediaCommand,
  MixerChange,
  MixerState,
  OpenAction,
  OpenWindows,
  SystemCommand,
  WindowsInfo,
  WindowTarget,
} from "../src/screens.ts";

/**
 * Remplace src/os.ts dans les tests (voir fake-os-hook.ts) : rien ne pilote l'ordinateur, chaque action est notée.
 * Mêmes exports que src/os.ts : un export ajouté là-bas sans être ajouté ici fait échouer les tests au chargement.
 *
 * Scénario (applis installées, fenêtres, boutons…) : `state` en mémoire, ou le fichier FAKE_OS_STATE (serveur lancé
 * par test/server.ts, relu à chaque appel). Actions : `calls` en mémoire, et une ligne JSON par action dans FAKE_OS_LOG.
 */

export type FakeState = {
  /** Applis installées (isInstalled, listApps). */
  installed?: string[];
  running?: string[];
  inCall?: string[];
  dnd?: boolean | null;
  /** Autorisation Accessibilité du Mac (accessibility) ; absente : accordée. */
  accessibility?: boolean;
  /** Presse-papiers de l'ordinateur (getClipboard) ; les écritures le remplacent. Vide par défaut. */
  clipboard?: ClipboardContent;
  /** Pas de presse-papiers (session sans bureau) : getClipboard répond 503. */
  clipboardUnavailable?: boolean;
  /** Mesure renvoyée par getPerf (sinon une mesure fixe). */
  perf?: Perf;
  /** Fenêtres par appli ; `elsewhere` : sur un autre bureau. */
  windows?: Record<string, { title: string; elsewhere?: boolean }[]>;
  /** Fenêtres qui apparaissent quand l'appli est lancée (launchApp). */
  onLaunch?: Record<string, { title: string }[]>;
  /** Boutons de la fenêtre de chaque appli (listButtons, pressButton). */
  buttons?: Record<string, string[]>;
  /** État vocal par appli d'appel (voiceState : appel Discord, micro coupé…) ; absent = pas d'appel lu. */
  voice?: Record<string, VoiceState & { lobby?: boolean }>;
  /** Fenêtres de la vue « Ouvertes » (listOpenWindows) ; absentes : aucune appli, l'écran factice. */
  openWindows?: OpenWindows;
  /** Lecture en cours (mediaSessions) ; absente : rien ne joue. */
  media?: MediaSession[];
  /** Mélangeur de l'onglet Son (mixer) : modifié par les changements reçus. */
  mixer?: MixerState;
  /** Appli au premier plan (frontApp) ; absente : Safari. */
  front?: { name: string; bundleId: string };
  /** Fonctions qui échouent (« Appli absente ») : `"sendKeys"`, ou `"openTarget msteams:"` pour un début de premier argument. */
  fail?: string[];
};

export type FakeCall = { fn: string; args: unknown[] };

export const state: FakeState = {};
export const calls: FakeCall[] = [];

const read = (): FakeState => {
  const file = process.env.FAKE_OS_STATE;
  return file && fs.existsSync(file) ? JSON.parse(fs.readFileSync(file, "utf8")) : state;
};

function write(next: FakeState) {
  if (process.env.FAKE_OS_STATE) fs.writeFileSync(process.env.FAKE_OS_STATE, JSON.stringify(next));
  else Object.assign(state, next);
}

function record(fn: string, ...args: unknown[]) {
  // Comme src/os.ts : hors Mac et Windows, toute action répond 501 (sauf alert, sans effet, et runShortcut, propre au Mac).
  if (!canControl && fn !== "alert" && fn !== "runShortcut") unsupported();
  const call = `${fn} ${args[0] ?? ""}`;
  if (read().fail?.some((f) => f === fn || (f.includes(" ") && call.startsWith(f)))) throw new Error(`${fn} a échoué (test)`);
  // Arguments facultatifs absents : retirés, comme ils le seraient une fois passés en JSON.
  while (args.length && args.at(-1) === undefined) args.pop();
  calls.push({ fn, args });
  if (process.env.FAKE_OS_LOG) fs.appendFileSync(process.env.FAKE_OS_LOG, JSON.stringify({ fn, args }) + "\n");
}

// "other" : système non pilotable (Linux), le serveur répond 501 sur le deck.
const fakeOs = process.env.FAKE_OS === "windows" || process.env.FAKE_OS === "other" ? process.env.FAKE_OS : "mac";
export const isMac = fakeOs === "mac";
export const isWindows = fakeOs === "windows";
export const canControl = fakeOs !== "other";
export const osName = fakeOs;
export const canMedia = isWindows || isMac;

export type { AudioState, MediaCommand, SystemCommand } from "../src/screens.ts";

// Comme src/os.ts : hors Mac et Windows, le pilotage répond 501.
function unsupported(): never {
  throw Object.assign(new Error("Pilotage disponible sur macOS et Windows seulement"), { statusCode: 501 });
}

const display: Display = { id: 1, label: "Écran", main: true, x: 0, y: 0, w: 1920, h: 1080 };

export function computerName() {
  return process.env.COMPUTER_NAME?.trim() || "Ordi factice";
}

export async function openTarget(target: string) {
  if (!canControl) unsupported();
  // Vraie garde (src/open-guard.ts) : un programme ou un script est refusé avant toute ouverture.
  checkOpenTarget(target, isMac ? "mac" : "windows");
  record("openTarget", target);
}

export async function launchApp(name: string) {
  record("launchApp", name);
  const opened = read().onLaunch?.[name];
  if (opened) write({ ...read(), windows: { ...read().windows, [name]: opened } });
}

export async function listApps() {
  return read().installed ?? [];
}

export async function isInstalled(name: string) {
  return (read().installed ?? []).includes(name);
}

export async function appIcon(_name: string, _out: string) {
  return false;
}

export async function listShortcuts(): Promise<string[]> {
  return [];
}

export async function runShortcut(name: string) {
  record("runShortcut", name);
}

export async function sendKeys(combo: string, app?: string) {
  record("sendKeys", combo, app);
}

export async function sendMediaKey(command: MediaCommand) {
  record("sendMediaKey", command);
}

export async function typeText(text: string) {
  record("typeText", text);
}

export async function getClipboard(): Promise<ClipboardContent> {
  if (read().clipboardUnavailable) clipboardUnavailable();
  if (read().fail?.includes("getClipboard")) throw new Error("getClipboard a échoué (test)");
  return read().clipboard ?? { kind: "empty" };
}

/** Le contenu lui-même sert de repère : il change quand le scénario ou une écriture change le presse-papiers. */
export async function clipboardStamp(): Promise<string> {
  if (read().clipboardUnavailable) clipboardUnavailable();
  if (!canControl) unsupported();
  return JSON.stringify(read().clipboard ?? { kind: "empty" });
}

export async function setClipboardText(text: string) {
  if (read().clipboardUnavailable) clipboardUnavailable();
  record("setClipboardText", text);
  write({ ...read(), clipboard: { kind: "text", text } });
}

export async function setClipboardImage(fullPath: string) {
  if (read().clipboardUnavailable) clipboardUnavailable();
  record("setClipboardImage", fullPath);
  write({ ...read(), clipboard: { kind: "image" } });
}

export async function getAudio() {
  return { output: 50, muted: false, micMuted: false };
}

export async function setAudio(change: AudioChange) {
  record("setAudio", change);
  return getAudio();
}

export async function getPerf(): Promise<Perf> {
  if (!canControl) unsupported();
  return read().perf ?? { cpu: 12, ram: { used: 4, total: 16, percent: 25 } };
}

export async function getPerfDetail(): Promise<unknown> {
  return { gpu: null };
}

export async function getDnd(): Promise<boolean | null> {
  return read().dnd ?? false;
}

export async function setDnd(on: boolean): Promise<boolean | null> {
  record("setDnd", on);
  write({ ...read(), dnd: on });
  return on;
}

export async function pointerMove(dx: number, dy: number) {
  record("pointerMove", dx, dy);
}

export async function pointerScroll(sx: number, sy: number) {
  record("pointerScroll", sx, sy);
}

export async function pointerButton(button: string, action: string) {
  record("pointerButton", button, action);
}

export async function pointerGesture(name: string) {
  record("pointerGesture", name);
}

export async function mediaSessions(): Promise<MediaSession[]> {
  return read().media ?? [];
}

export async function mediaControl(action: string, app?: string, positionMs?: number) {
  record("mediaControl", action, app, positionMs);
}

export async function mediaThumb(_app: string, _out: string) {
  return false;
}

export async function browserPage() {
  return undefined;
}

export async function browserTabs() {
  return undefined;
}

export async function mixer(change?: MixerChange): Promise<MixerState> {
  const current = read().mixer ?? {
    master: { id: "master", name: "master", volume: 50, muted: false, level: 0 },
    mic: { id: "mic", name: "mic", volume: 80, muted: false, level: 0 },
    apps: [],
  };
  if (!change) return current;
  record("mixer", change);
  const { id, ...values } = change;
  const apply = (c: MixerState["master"]) => (c.id === id ? { ...c, ...values } : c);
  const next = { master: apply(current.master), mic: apply(current.mic), apps: current.apps?.map(apply) ?? null };
  write({ ...read(), mixer: next });
  return next;
}

export async function appAudio(app: string, change: { volume?: number; muted?: boolean } = {}) {
  if (Object.keys(change).length) record("appAudio", app, change);
  return { app, running: false, capturing: false, volume: null, muted: null };
}

export async function isAppInCall(app: string) {
  return (read().inCall ?? []).includes(app);
}

export async function isAppRunning(app: string) {
  return (read().running ?? []).includes(app);
}

export async function frontApp(): Promise<{ name: string; bundleId: string }> {
  if (!isMac) unsupported();
  return read().front ?? { name: "Safari", bundleId: "com.apple.Safari" };
}

export async function accessibility(): Promise<boolean | null> {
  return isMac ? (read().accessibility ?? true) : null;
}

export async function openAccessibility() {
  record("openAccessibility");
}

export async function listButtons(app: string, _detail = false) {
  const buttons = read().buttons?.[app] ?? [];
  return { buttons, total: buttons.length };
}

/** Le premier bouton trouvé parmi `names`, null s'il n'y en a aucun. */
export async function pressButton(app: string, names: string[]): Promise<string | null> {
  const pressed = names.find((n) => read().buttons?.[app]?.includes(n)) ?? null;
  if (pressed) record("pressButton", app, pressed);
  return pressed;
}

export async function voiceState(app: string): Promise<VoiceState | null> {
  return read().voice?.[app] ?? null;
}

export async function listDisplays() {
  return [display];
}

export async function listWindows(app: string): Promise<WindowsInfo> {
  const windows = (read().windows?.[app] ?? []).map((w, index) => ({
    index,
    title: w.title,
    minimized: false,
    fullscreen: false,
    display: w.elsewhere ? null : display.id,
    elsewhere: w.elsewhere ?? false,
  }));
  return { app, running: windows.length > 0, windows, displays: [display] };
}

export async function focusWindow(app: string, window?: WindowTarget) {
  record("focusWindow", app, window);
  return listWindows(app);
}

export async function placeWindow(app: string, opts: { window?: WindowTarget; display?: number; layout: Layout }) {
  record("placeWindow", app, opts);
  return listWindows(app);
}

export async function listOpenWindows(): Promise<OpenWindows> {
  if (!canControl) unsupported();
  return read().openWindows ?? { apps: [], displays: [display] };
}

export async function controlOpenWindow(
  key: string,
  window: WindowTarget,
  action: OpenAction,
  opts?: { display?: number; layout: Layout },
) {
  record("controlOpenWindow", key, window, action, opts);
  return listOpenWindows();
}

export async function movePage(id: number, to: number) {
  if (!canControl) unsupported();
  record("movePage", id, to);
}

export async function showPage(id: number) {
  if (!canControl) unsupported();
  record("showPage", id);
}

export async function systemCommand(command: SystemCommand) {
  record("systemCommand", command);
}

export async function alert(title: string, text: string) {
  record("alert", title, text);
}
