import { execFileSync, spawn } from "node:child_process";
import os from "node:os";
import { osascript, run } from "./mac/run.ts";
import * as macApps from "./mac/apps.ts";
import * as macAudio from "./mac/audio.ts";
import * as macBrowser from "./mac/browser.ts";
import * as macKeys from "./mac/keys.ts";
import * as macWindows from "./mac/windows.ts";
import * as win from "./win/index.ts";
import type { Layout, WindowTarget } from "./screens.ts";

/**
 * Pilotage de l'ordinateur, une seule interface pour macOS (src/mac/) et Windows (src/win/).
 * Ailleurs (Linux), tout ce qui touche au pilotage répond 501 ; agenda, documents, tâches,
 * visualiseur et canal tablette marchent partout.
 */

export const isMac = process.platform === "darwin";
export const isWindows = process.platform === "win32";
/** Le deck, le son et les fenêtres sont pilotables. */
export const canControl = isMac || isWindows;
export const osName = isMac ? "mac" : isWindows ? "windows" : "other";

export type SystemCommand = "sleep-display" | "toggle-dark-mode" | "screenshot-clipboard" | "lock";
export type MediaCommand = "play-pause" | "next" | "previous";
export type { AudioState } from "./mac/audio.ts";

function unsupported(): never {
  throw Object.assign(new Error("Pilotage disponible sur macOS et Windows seulement"), { statusCode: 501 });
}

/** Nom affiché sur la tablette : celui du Mac (Réglages > Général > Informations), sinon le nom réseau. */
export function computerName(): string {
  const env = process.env.COMPUTER_NAME?.trim();
  if (env) return env;
  if (isMac) {
    try {
      // Synchrone : lu une fois au démarrage.
      const name = execFileSync("scutil", ["--get", "ComputerName"], { encoding: "utf8", timeout: 2_000 }).trim();
      // « (5) » : suffixe ajouté par macOS quand le nom existe déjà sur le réseau.
      if (name) return name.replace(/\s*\(\d+\)$/, "");
    } catch {
      // scutil absent : nom réseau.
    }
  }
  return os.hostname().replace(/\.local$/, "");
}

/** Ouvre un lien (https, msteams…) ou un fichier avec l'appli associée. */
export async function openTarget(target: string) {
  if (isMac) return void (await run("open", [target]));
  if (isWindows) return win.openTarget(target);
  unsupported();
}

/** Met l'appli au premier plan, en la lançant si besoin. */
export async function launchApp(name: string) {
  if (isMac) return void (await run("open", ["-a", name]));
  if (isWindows) return win.launchApp(name);
  unsupported();
}

export async function listApps(): Promise<string[]> {
  if (isMac) return macApps.listApps();
  if (isWindows) return win.listApps();
  return [];
}

/** Applis installées, gardées 5 min : le profil de base du deck ne montre que celles-là. */
let installed: { at: number; names: Set<string> } | undefined;
export async function isInstalled(name: string): Promise<boolean> {
  if (isWindows) return win.isInstalled(name);
  if (!isMac) return false;
  if (!installed || Date.now() - installed.at > 5 * 60_000) installed = { at: Date.now(), names: new Set(await macApps.listApps()) };
  return installed.names.has(name);
}

/** Écrit l'icône PNG (96 px) de l'appli dans `out` ; false si elle n'en a pas. */
export async function appIcon(name: string, out: string): Promise<boolean> {
  if (isMac) return macApps.appIcon(name, out);
  if (isWindows) return win.appIcon(name, out);
  return false;
}

/** Raccourcis de l'appli Raccourcis (macOS seulement). */
export async function listShortcuts(): Promise<string[]> {
  return isMac ? macApps.listShortcuts() : [];
}

export async function runShortcut(name: string) {
  if (!isMac) throw new Error("Les raccourcis Apple n'existent que sur Mac");
  await run("shortcuts", ["run", name], 30_000);
}

/** Raccourci au format "cmd+shift+m" (sur Windows, cmd = Ctrl ; win = touche Windows). */
export async function sendKeys(combo: string, app?: string) {
  if (isMac) return macKeys.sendKeys(combo, app);
  if (isWindows) return win.sendKeys(combo, app);
  unsupported();
}

export async function sendMediaKey(command: MediaCommand) {
  if (isMac) return macKeys.sendMediaKey(command);
  if (isWindows) return win.sendMediaKey(command);
  unsupported();
}

/** Colle un texte dans l'appli au premier plan. */
export async function typeText(text: string) {
  if (isMac) return macKeys.typeText(text);
  if (isWindows) return win.typeText(text);
  unsupported();
}

export function getAudio() {
  if (isMac) return macAudio.getAudio();
  if (isWindows) return win.getAudio();
  unsupported();
}

export function setAudio(change: { output?: number; outputDelta?: number; muted?: boolean; micMuted?: boolean }) {
  if (isMac) return macAudio.setAudio(change);
  if (isWindows) return win.setAudio(change);
  unsupported();
}

/** Lecture en cours (vidéo, musique) et ses commandes : Windows seulement pour l'instant. */
export const canMedia = isWindows;

export function mediaSessions() {
  if (isWindows) return win.mediaSessions();
  unsupported();
}

export function mediaControl(action: win.MediaAction, app?: string, positionMs?: number) {
  if (isWindows) return win.mediaControl(action, app, positionMs);
  unsupported();
}

export function mediaThumb(app: string, out: string) {
  if (isWindows) return win.mediaThumb(app, out);
  unsupported();
}

/** Page ouverte dans le navigateur de l'ordinateur (le dernier utilisé), pour l'afficher sur la tablette. */
export function browserPage() {
  if (isMac) return macBrowser.browserPage();
  if (isWindows) return win.browserPage();
  unsupported();
}

/** Volume propre à une appli (mélangeur de volume) : Windows seulement, macOS n'en a pas. */
export function appAudio(app: string, change: { volume?: number; muted?: boolean } = {}) {
  if (isWindows) return win.appAudio(app, change);
  unsupported();
}

export function listDisplays() {
  if (isMac) return macWindows.listDisplays();
  if (isWindows) return win.listDisplays();
  unsupported();
}

export function listWindows(app: string) {
  if (isMac) return macWindows.listWindows(app);
  if (isWindows) return win.listWindows(app);
  unsupported();
}

/** Met une fenêtre (ou toute l'appli, sans `window`) au premier plan. */
export function focusWindow(app: string, window?: WindowTarget) {
  if (isMac) return macWindows.focusWindow(app, window);
  if (isWindows) return win.focusWindow(app, window);
  unsupported();
}

/** Déplace une fenêtre (ou toutes) vers un écran et/ou une moitié d'écran, puis la met au premier plan. */
export function placeWindow(app: string, opts: { window?: WindowTarget; display?: number; layout: Layout }) {
  if (isMac) return macWindows.placeWindow(app, opts);
  if (isWindows) return win.placeWindow(app, opts);
  unsupported();
}

export async function systemCommand(command: SystemCommand) {
  if (isWindows) return win.systemCommand(command);
  if (!isMac) unsupported();
  switch (command) {
    case "sleep-display":
      return void (await run("pmset", ["displaysleepnow"]));
    case "toggle-dark-mode":
      return void (await osascript('tell application "System Events" to tell appearance preferences to set dark mode to not dark mode'));
    case "screenshot-clipboard":
      // Raccourci système plutôt que `screencapture` : ce dernier exige l'autorisation
      // Enregistrement de l'écran, sans laquelle il ne capture que le fond d'écran.
      return macKeys.sendKeys("cmd+ctrl+shift+3");
    case "lock":
      return macKeys.sendKeys("ctrl+cmd+q");
  }
}

/** Message bien visible sur l'ordinateur (code d'appairage), sans attendre qu'il soit fermé. */
export async function alert(title: string, text: string) {
  if (isWindows) return win.alert(title, text);
  if (!isMac) return;
  // Processus détaché : la boîte reste affichée sans bloquer le serveur, et se ferme seule après 2 min.
  const script = `on run argv
activate
display dialog (item 2 of argv) with title (item 1 of argv) buttons {"OK"} default button 1 giving up after 120
end run`;
  spawn("osascript", ["-e", script, title, text], { detached: true, stdio: "ignore" }).unref();
}
