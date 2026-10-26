import fs from "node:fs";
import { checkOpenTarget } from "../src/open-guard.ts";
import type { AudioChange, Display, Layout, MediaCommand, OpenAction, SystemCommand, WindowsInfo, WindowTarget } from "../src/screens.ts";

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
  /** Fenêtres par appli ; `elsewhere` : sur un autre bureau. */
  windows?: Record<string, { title: string; elsewhere?: boolean }[]>;
  /** Fenêtres qui apparaissent quand l'appli est lancée (launchApp). */
  onLaunch?: Record<string, { title: string }[]>;
  /** Boutons de la fenêtre de chaque appli (listButtons, pressButton). */
  buttons?: Record<string, string[]>;
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
export const canMedia = isWindows;

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

export async function getAudio() {
  return { output: 50, muted: false, micMuted: false };
}

export async function setAudio(change: AudioChange) {
  record("setAudio", change);
  return getAudio();
}

export async function getDnd(): Promise<boolean | null> {
  return read().dnd ?? false;
}

export async function setDnd(on: boolean): Promise<boolean | null> {
  record("setDnd", on);
  write({ ...read(), dnd: on });
  return on;
}

export async function mediaSessions() {
  return [];
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

export async function accessibility(): Promise<boolean | null> {
  return isMac ? true : null;
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

export async function voiceState(_app: string) {
  return null;
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

export async function listOpenWindows() {
  if (!canControl) unsupported();
  return { apps: [], displays: [display] };
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

export async function systemCommand(command: SystemCommand) {
  record("systemCommand", command);
}

export async function alert(title: string, text: string) {
  record("alert", title, text);
}
