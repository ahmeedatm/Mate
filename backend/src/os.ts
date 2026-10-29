import { execFileSync } from "node:child_process";
import os from "node:os";
import { run } from "./mac/run.ts";
import * as macAccess from "./mac/access.ts";
import * as macApps from "./mac/apps.ts";
import * as macAudio from "./mac/audio.ts";
import * as macBrowser from "./mac/browser.ts";
import * as macClipboard from "./mac/clipboard.ts";
import * as macCalls from "./mac/calls.ts";
import * as macDnd from "./mac/dnd.ts";
import { extensionFrontPage, extensionTabs } from "./browser-extension.ts";
import * as macKeys from "./mac/keys.ts";
import * as macMedia from "./mac/media.ts";
import * as macPointer from "./mac/pointer.ts";
import * as macSystem from "./mac/system.ts";
import * as macWindows from "./mac/windows.ts";
import * as win from "./win/index.ts";
import { checkOpenTarget } from "./open-guard.ts";
import { perfSampler, type Perf } from "./perf.ts";
import type { PointerAction, PointerButton, PointerGesture } from "./pointer.ts";
import type {
  AudioChange,
  ClipboardContent,
  Layout,
  MediaCommand,
  MixerChange,
  OpenAction,
  SystemCommand,
  WindowTarget,
} from "./screens.ts";

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
/** Instance de test (CI sur le Mac de quelqu'un) : rien ne s'affiche ni ne s'ouvre à l'écran. */
const headless = process.env.COMPANION_HEADLESS === "1";

export type { AudioState, MediaCommand, SystemCommand } from "./screens.ts";

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

/** Ouvre un lien (https, msteams…), un document ou un dossier avec l'appli associée ; jamais un programme ni un script (open-guard.ts). */
export async function openTarget(target: string) {
  if (!canControl) unsupported();
  checkOpenTarget(target, isMac ? "mac" : "windows");
  if (headless) return;
  if (isMac) return void (await run("open", [target]));
  return win.openTarget(target);
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

/** Applis installées : le profil de base du deck ne montre que celles-là. */
export async function isInstalled(name: string): Promise<boolean> {
  if (isWindows) return win.isInstalled(name);
  return isMac ? macApps.isInstalled(name) : false;
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
  await run("shortcuts", ["run", "--", name], 30_000);
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

/** Contenu du presse-papiers de l'ordinateur (jamais celui d'un gestionnaire de mots de passe : `protected`). */
export async function getClipboard(): Promise<ClipboardContent> {
  if (isMac) return macClipboard.getClipboard();
  if (isWindows) return win.getClipboard();
  unsupported();
}

export async function setClipboardText(text: string) {
  if (isMac) return macClipboard.setClipboardText(text);
  if (isWindows) return win.setClipboardText(text);
  unsupported();
}

/** Copie une image (jpg, png) comme image dans le presse-papiers ; `fullPath` : chemin complet, déjà vérifié par la route. */
export async function setClipboardImage(fullPath: string) {
  if (isMac) return macClipboard.setClipboardImage(fullPath);
  if (isWindows) return win.setClipboardImage(fullPath);
  unsupported();
}

export function getAudio() {
  if (isMac) return macAudio.getAudio();
  if (isWindows) return win.getAudio();
  unsupported();
}

/** Un seul échantillonneur par processus : il porte le cache d'une seconde, le point de départ du CPU et la mémoire du ventilateur. */
let perfSample: (() => Promise<Perf>) | undefined;

/** Processeur, mémoire et carte graphique de l'ordinateur ; sans carte lisible, pas de `gpu`. */
export function getPerf(): Promise<Perf> {
  if (!canControl) unsupported();
  perfSample ??= perfSampler({
    cpus: os.cpus,
    memory: isMac
      ? async () => ({ total: os.totalmem(), available: await macSystem.availableMemory() })
      : async () => ({ total: os.totalmem(), available: os.freemem() }),
    cpuPercent: isWindows ? win.cpuUtility : null,
    gpu: isWindows ? win.gpuPerf : null,
    // Horloge monotone : un changement d'heure du PC ne fige pas le cache ni le point de départ du processeur.
    now: () => performance.now(),
  });
  return perfSample();
}

/** Diagnostic brut du lecteur de carte graphique (Windows) ; sur Mac, pas de lecteur. */
export async function getPerfDetail(): Promise<unknown> {
  if (isWindows) return win.gpuPerfDetail();
  if (isMac) return { gpu: null };
  unsupported();
}

export function setAudio(change: AudioChange) {
  if (isMac) return macAudio.setAudio(change);
  if (isWindows) return win.setAudio(change);
  unsupported();
}

/** Ne pas déranger de l'ordinateur. null : état inconnu. */
export async function getDnd(): Promise<boolean | null> {
  if (isWindows) return win.getDnd();
  if (isMac) return macDnd.getDnd();
  unsupported();
}

export async function setDnd(on: boolean): Promise<boolean | null> {
  if (isWindows) return win.setDnd(on);
  if (isMac) return macDnd.setDnd(on);
  unsupported();
}

/** Pavé tactile : déplacement relatif du pointeur (pixels), défilement (crans) et boutons de la souris. */
export async function pointerMove(dx: number, dy: number) {
  if (isWindows) return win.pointerMove(dx, dy);
  if (isMac) return macPointer.pointerMove(dx, dy);
  unsupported();
}

export async function pointerScroll(sx: number, sy: number) {
  if (isWindows) return win.pointerScroll(sx, sy);
  if (isMac) return macPointer.pointerScroll(sx, sy);
  unsupported();
}

export async function pointerButton(button: PointerButton, action: PointerAction) {
  if (isWindows) return win.pointerButton(button, action);
  if (isMac) return macPointer.pointerButton(button, action);
  unsupported();
}

// Gestes : raccourci par système (table fermée). Le zoom est géré à part ; `null` = sans effet.
const GESTURE_KEYS: Record<Exclude<PointerGesture, "zoom-in" | "zoom-out">, { windows: string; mac: string | null }> = {
  back: { windows: "alt+left", mac: "cmd+[" },
  forward: { windows: "alt+right", mac: "cmd+]" },
  "task-view": { windows: "win+tab", mac: "ctrl+up" },
  desktop: { windows: "win+d", mac: "ctrl+down" },
  "app-previous": { windows: "alt+shift+tab", mac: "cmd+shift+tab" },
  "app-next": { windows: "alt+tab", mac: "cmd+tab" },
  search: { windows: "win+s", mac: "cmd+space" },
  "desktop-previous": { windows: "ctrl+win+left", mac: "ctrl+left" },
  "desktop-next": { windows: "ctrl+win+right", mac: "ctrl+right" },
  notifications: { windows: "win+n", mac: null },
};

/** Geste du pavé tactile (zoom, retour, vue des tâches…) traduit en raccourci selon le système. */
export async function pointerGesture(name: PointerGesture) {
  if (!isWindows && !isMac) unsupported();
  if (name === "zoom-in" || name === "zoom-out") {
    if (isWindows) return win.pointerZoom(name === "zoom-in" ? 1 : -1);
    return macKeys.sendKeys(name === "zoom-in" ? "cmd+=" : "cmd+-");
  }
  const combo = GESTURE_KEYS[name][isWindows ? "windows" : "mac"];
  if (combo) await sendKeys(combo);
}

/** Lecture en cours (vidéo, musique) et ses commandes : contrôles multimédias de Windows ; sur Mac, lecteurs et onglets. */
export const canMedia = isWindows || isMac;

export function mediaSessions() {
  if (isWindows) return win.mediaSessions();
  if (isMac) return macMedia.mediaSessions();
  unsupported();
}

export function mediaControl(action: win.MediaAction, app?: string, positionMs?: number) {
  if (isWindows) return win.mediaControl(action, app, positionMs);
  if (isMac) return macMedia.mediaControl(action, app, positionMs);
  unsupported();
}

export function mediaThumb(app: string, out: string) {
  if (isWindows) return win.mediaThumb(app, out);
  if (isMac) return macMedia.mediaThumb(app, out);
  unsupported();
}

/** Page ouverte dans le navigateur de l'ordinateur (le dernier utilisé), pour l'afficher sur la tablette. */
export function browserPage() {
  if (isMac) return macBrowser.browserPage();
  // Windows : l'extension du navigateur, si elle est installée, est plus fiable qu'UI Automation.
  if (isWindows) return extensionFrontPage() ?? win.browserPage();
  unsupported();
}

/** Onglets ouverts dans les navigateurs de l'ordinateur, pour choisir la page à afficher sur la tablette. */
export function browserTabs() {
  if (isMac) return macBrowser.browserTabs();
  // Windows : envoyés par l'extension du navigateur ; undefined si elle n'est pas installée (ou le navigateur fermé).
  if (isWindows) return extensionTabs();
  unsupported();
}

/** Mélangeur de l'onglet Son : sortie, micro, et volume par appli sur Windows ; `change` appliqué avant la lecture. */
export function mixer(change?: MixerChange) {
  if (isWindows) return win.mixer(change);
  if (isMac) return macAudio.mixer(change);
  unsupported();
}

/** Volume propre à une appli (mélangeur de volume) : Windows seulement, macOS n'en a pas. */
export function appAudio(app: string, change: { volume?: number; muted?: boolean } = {}) {
  if (isWindows) return win.appAudio(app, change);
  unsupported();
}

/**
 * L'appli est en appel : elle enregistre le micro (Windows) ; sur Mac, sa fenêtre d'appel est ouverte (Teams) ou,
 * si sa fenêtre ne se lit pas, elle tourne et un micro est utilisé.
 */
export async function isAppInCall(app: string): Promise<boolean> {
  if (isWindows) return (await win.appAudio(app)).capturing;
  return isMac ? macSystem.isAppInCall(app) : false;
}

/** L'appli tourne, même sans fenêtre (Discord réduit dans la zone de notification). */
export async function isAppRunning(app: string): Promise<boolean> {
  if (isWindows) return (await win.appAudio(app)).running || (await win.listWindows(app)).running;
  return isMac ? macSystem.isAppRunning(app) : false;
}

/**
 * Mac : le compagnon a-t-il l'autorisation Accessibilité ? null ailleurs (rien à autoriser). Instance de test : non,
 * sans demander à macOS, car la question seule ajoute son node à la liste de Réglages Système (un par job).
 */
export async function accessibility(): Promise<boolean | null> {
  if (isMac && headless) return false;
  return isMac ? macAccess.accessibilityTrusted() : null;
}

/** Mac : ouvre Réglages Système à la page Accessibilité, le compagnon déjà dans la liste. */
export async function openAccessibility() {
  if (isMac) return macAccess.openAccessibilitySettings();
  unsupported();
}

/** Boutons d'une fenêtre d'appli : lister leurs noms, en actionner un. */
export function listButtons(app: string, detail = false) {
  if (isWindows) return win.listButtons(app, detail);
  if (isMac) return macCalls.listButtons(app, detail);
  unsupported();
}

export function pressButton(app: string, names: string[]) {
  if (isWindows) return win.pressButton(app, names);
  if (isMac) return macCalls.pressButton(app, names);
  unsupported();
}

/** Appel en cours (salon, participants, micro) : Discord sous Windows, Teams sur Mac ; null si appli fermée. */
export async function voiceState(app: string) {
  if (isWindows) return win.voiceState(app);
  if (isMac && (await isAppRunning(app))) return macCalls.callState(app);
  return null;
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

/** Vue « Ouvertes » de la tablette : toutes les applis qui ont une fenêtre, avec l'écran de chacune. */
export function listOpenWindows() {
  if (isMac) return macWindows.listOpenWindows();
  if (isWindows) return win.listOpenWindows();
  unsupported();
}

/** Pilote une fenêtre de la vue « Ouvertes » ; `key` : celle de son appli dans la liste ; `opts` pour `place`. */
export function controlOpenWindow(key: string, window: WindowTarget, action: OpenAction, opts?: { display?: number; layout: Layout }) {
  if (isMac) return macWindows.controlOpenWindow(key, window, action, opts);
  if (isWindows) return win.controlOpenWindow(key, window, action, opts);
  unsupported();
}

/** Mac : déplace une page du balayage à trois doigts à la place `to` (Mission Control). */
export function movePage(id: number, to: number) {
  if (isMac) return macWindows.movePage(id, to);
  unsupported();
}

/** Mac : passe sur une page du balayage à trois doigts (vue « Ouvertes »). */
export function showPage(id: number) {
  if (isMac) return macWindows.showPage(id);
  unsupported();
}

export async function systemCommand(command: SystemCommand) {
  if (isWindows) return win.systemCommand(command);
  if (isMac) return macSystem.systemCommand(command);
  unsupported();
}

/** Appli au premier plan de l'ordinateur (Mac seulement : sert aux alertes des agents Claude). */
export async function frontApp(): Promise<{ name: string; bundleId: string }> {
  if (isMac) return macSystem.frontApp();
  unsupported();
}

/** Message bien visible sur l'ordinateur (code d'appairage), sans attendre qu'il soit fermé. */
export async function alert(title: string, text: string) {
  // Tests d'intégration et instance de test : pas de boîte réelle à chaque appairage.
  if (process.env.NO_ALERT || headless) return;
  if (isWindows) return win.alert(title, text);
  if (isMac) macSystem.alert(title, text);
}
