import { call } from "./helper.ts";
import {
  labelDisplays,
  type AppWindow,
  type Layout,
  type OpenAction,
  type OpenApp,
  type OpenWindows,
  type RawScreen,
  type WindowsInfo,
  type WindowTarget,
} from "../screens.ts";
import { parseCombo, planAudio, sortedNames, whenClipboardAvailable } from "../driver-common.ts";
import type { AudioChange, AudioState, ClipboardContent, MediaCommand, MixerChange, MixerState, SystemCommand } from "../screens.ts";
import type { GpuPerf } from "../perf.ts";
import type { PointerAction, PointerButton } from "../pointer.ts";

/**
 * Pilotage de Windows (10 et 11), mêmes fonctions que les modules de src/mac/ : voir src/os.ts.
 * Les applis sont celles du menu Démarrer (applis classiques et du Store), lancées par leur
 * identifiant (AppUserModelID) ; le code natif est dans helper/Native*.cs.
 */

type StartApp = { name: string; id: string };

const HIDDEN_APPS = /uninstall|désinstall|readme|lisez-moi|documentation|release notes|notes de version|^aide|^help/i;
let cache: { at: number; apps: StartApp[] } | undefined;
let lastForced = 0;

async function startApps(force = false): Promise<StartApp[]> {
  if (cache && !force && Date.now() - cache.at < 5 * 60_000) return cache.apps;
  const apps = (await call<StartApp[]>("apps"))
    // Pas les liens web ni les désinstalleurs rangés dans le menu Démarrer.
    .filter((a) => a.name && a.id && !/^https?:/i.test(a.id) && !HIDDEN_APPS.test(a.name));
  cache = { at: Date.now(), apps };
  return apps;
}

const simple = (s: string) =>
  s
    .toLowerCase()
    .replace(/^microsoft\s+/, "")
    .trim();

function pick(apps: StartApp[], name: string): StartApp | undefined {
  const n = name.toLowerCase();
  return (
    apps.find((a) => a.name.toLowerCase() === n) ??
    // "Microsoft Outlook" (nom Mac) pour "Outlook", "Outlook" pour "Outlook (classic)".
    apps.find((a) => simple(a.name) === simple(name)) ??
    apps.find((a) => simple(a.name).startsWith(simple(name) + " "))
  );
}

/** Appli du menu Démarrer d'après son nom ; la liste est relue (au plus toutes les 30 s) si le nom est inconnu. */
export async function findApp(name: string): Promise<StartApp | undefined> {
  const found = pick(await startApps(), name);
  if (found || Date.now() - lastForced < 30_000) return found;
  lastForced = Date.now();
  return pick(await startApps(true), name);
}

async function requireApp(name: string) {
  const app = await findApp(name);
  if (!app) throw new Error(`Appli introuvable : ${name}`);
  return app;
}

export async function listApps(): Promise<string[]> {
  return sortedNames((await startApps(true)).map((a) => a.name));
}

export async function isInstalled(name: string) {
  return (await findApp(name)) !== undefined;
}

/** Comme `open -a` sur Mac : met l'appli devant si elle a une fenêtre, sinon la lance. */
export async function launchApp(name: string) {
  const app = await requireApp(name);
  const shown = await call<{ running: boolean }>("windows", { action: "focus", id: app.id, names: [name, app.name] });
  if (!shown.running) await call("launch", { id: app.id });
}

/** Lien ou fichier, ouvert par l'appli associée. */
export async function openTarget(target: string) {
  await call("open", { target });
}

export async function appIcon(name: string, out: string): Promise<boolean> {
  const app = await findApp(name);
  if (!app) return false;
  return call<boolean>("icon", { id: app.id, out, size: 96 }).catch(() => false);
}

// --- Clavier

const MODIFIERS: Record<string, number> = {
  // Les raccourcis écrits pour Mac (cmd+…) deviennent Ctrl+… : même sens dans la plupart des applis.
  cmd: 0x11,
  command: 0x11,
  ctrl: 0x11,
  control: 0x11,
  shift: 0x10,
  alt: 0x12,
  option: 0x12,
  win: 0x5b,
  windows: 0x5b,
  super: 0x5b,
  meta: 0x5b,
};

const KEY_CODES: Record<string, number> = {
  return: 0x0d,
  enter: 0x0d,
  tab: 0x09,
  space: 0x20,
  delete: 0x08,
  backspace: 0x08,
  del: 0x2e,
  forwarddelete: 0x2e,
  esc: 0x1b,
  escape: 0x1b,
  left: 0x25,
  up: 0x26,
  right: 0x27,
  down: 0x28,
  home: 0x24,
  end: 0x23,
  pageup: 0x21,
  pagedown: 0x22,
  insert: 0x2d,
  printscreen: 0x2c,
  ...Object.fromEntries(Array.from({ length: 12 }, (_, i) => [`f${i + 1}`, 0x70 + i])),
};

export async function sendKeys(combo: string, app?: string) {
  const { key, mods } = parseCombo(combo, MODIFIERS);
  let vk = KEY_CODES[key];
  // Lettres et chiffres : touche du même nom quelle que soit la disposition (chiffres de la rangée du haut).
  if (vk === undefined && /^[a-z0-9]$/.test(key)) vk = key.toUpperCase().charCodeAt(0);
  if (vk === undefined && key.length !== 1) throw new Error(`Touche inconnue : ${key}`);
  if (app) {
    await launchApp(app);
    await new Promise((resolve) => setTimeout(resolve, 400));
  }
  await call("keys", { mods: [...new Set(mods)], ...(vk !== undefined ? { vk } : { ch: key }) });
}

const MEDIA_KEYS: Record<MediaCommand, number> = { "play-pause": 0xb3, next: 0xb0, previous: 0xb1 };

export async function sendMediaKey(command: MediaCommand) {
  await call("keys", { mods: [], vk: MEDIA_KEYS[command] });
}

export async function typeText(text: string) {
  await call("text", { text });
}

// --- Presse-papiers

export async function getClipboard(): Promise<ClipboardContent> {
  // Lecture comme écritures : sans session de bureau, le pilote répond par l'erreur que `whenClipboardAvailable` change en 503.
  return whenClipboardAvailable(call<ClipboardContent>("clipget"));
}

export async function setClipboardText(text: string) {
  await whenClipboardAvailable(call("clipset", { text }));
}

/** Copie l'image du fichier (jpg, png) comme image dans le presse-papiers. */
export async function setClipboardImage(path: string) {
  await whenClipboardAvailable(call("clipimage", { path }));
}

// --- Son

export async function getAudio(): Promise<AudioState> {
  return call<AudioState>("audio");
}

export async function setAudio(change: AudioChange): Promise<AudioState> {
  const { output, muted } = planAudio(change, await getAudio());
  return call<AudioState>("audio", { output, muted, micMuted: change.micMuted });
}

// --- Performances

/** Carte graphique principale (usage, température, ventilateur) ; null si le pilote n'en lit aucune. */
export function gpuPerf(): Promise<GpuPerf | null> {
  return call<GpuPerf | null>("perf");
}

/** Activité du processeur (0-100) selon le Gestionnaire des tâches (« % Processor Utility ») ; null si le compteur est illisible. */
export function cpuUtility(): Promise<number | null> {
  return call<number | null>("cpu");
}

/** Lecture brute du pilote (toutes les cartes, tous les compteurs), pour diagnostiquer un PC à carte dédiée. */
export function gpuPerfDetail(): Promise<unknown> {
  return call("perf", { detail: true });
}

/**
 * Son d'une appli dans le mélangeur de volume ; `level` : niveau sonore instantané (0-100), pour savoir si elle joue ;
 * `capturing` : elle enregistre le micro, donc elle est en appel.
 */
export type AppAudio = { running: boolean; volume: number | null; muted: boolean; level: number; capturing: boolean };

/** Sessions audio dont le processus porte le nom de l'appli ("Microsoft Teams" : ms-teams.exe, Teams.exe). */
export async function appAudio(app: string, change: { volume?: number; muted?: boolean } = {}): Promise<AppAudio> {
  return call<AppAudio>("appaudio", { names: [app, simple(app)], volume: change.volume, muted: change.muted });
}

/** Mélangeur de volume : sortie, micro et une tranche par appli ; `change` appliqué avant la lecture. */
export async function mixer(change?: MixerChange): Promise<MixerState> {
  return call<MixerState>("mixer", change ?? {});
}

// --- Lecture en cours (contrôles multimédias de Windows)

/** `app` : identifiant Windows de l'appli qui joue (navigateur, Spotify…) ; `name` : son nom lisible. */
export type MediaSession = {
  app: string;
  name: string;
  current: boolean;
  title: string;
  artist: string;
  album: string;
  hasThumb: boolean;
  status: string;
  canPlayPause: boolean;
  canNext: boolean;
  canPrevious: boolean;
  canSeek: boolean;
  positionMs: number | null;
  durationMs: number | null;
};
export type MediaAction = "play-pause" | "next" | "previous" | "seek";

const MEDIA_APPS: [RegExp, string][] = [
  [/chrome/i, "Chrome"],
  [/msedge/i, "Edge"],
  [/firefox|308046B0AF4A39CB/i, "Firefox"],
  [/opera/i, "Opera"],
  [/brave/i, "Brave"],
  [/spotify/i, "Spotify"],
  [/vlc/i, "VLC"],
  [/zunemusic|music\.ui/i, "Lecteur multimédia"],
];

function mediaAppName(app: string) {
  const known = MEDIA_APPS.find(([re]) => re.test(app));
  // "Spotify.exe", "Editeur.Appli_abc123!App" : le nom de l'exécutable ou du paquet.
  return known
    ? known[1]
    : app
        .replace(/\.exe$/i, "")
        .replace(/_[a-z0-9]+!.*$/i, "")
        .split(/[.\\]/)
        .pop() || app;
}

const letters = (s: string) => s.toLowerCase().replace(/[^a-z0-9]/g, "");

/**
 * Lectures en cours, sans celles d'applis qui publient une session vide (Riot Client : « Riot Client », sans artiste
 * ni durée) : Windows la met parfois en avant, et Play visait alors une appli qui ne joue rien.
 */
export async function mediaSessions(): Promise<MediaSession[]> {
  const raw = await call<Omit<MediaSession, "name">[]>("media", { action: "list" });
  return raw
    .map((s) => ({ ...s, name: mediaAppName(s.app) }))
    .filter((s) => s.artist || s.durationMs !== null || (s.title && letters(s.title) !== letters(s.name)));
}

export async function mediaControl(action: MediaAction, app?: string, positionMs?: number) {
  return call<boolean>("media", { action, app, position: positionMs });
}

/** Page ouverte dans le navigateur au premier plan (le plus en avant des navigateurs) : voir helper/Program.cs. */
export function browserPage() {
  return call<{ found: boolean; url?: string; title?: string; browser?: string }>("browser");
}

/** Vignette de ce qui joue (pochette, image de la vidéo) écrite dans `out`. */
export async function mediaThumb(app: string, out: string) {
  return call<boolean>("media", { action: "thumb", app, out });
}

// --- Fenêtres et écrans

type RawWindows = { running: boolean; windows: AppWindow[]; displays: RawScreen[] };

async function windowsCall(app: string, req: Record<string, unknown>): Promise<WindowsInfo> {
  const found = await requireApp(app);
  const raw = await call<RawWindows>("windows", { ...req, id: found.id, names: [app, found.name] });
  return { app, running: raw.running, windows: raw.windows, displays: labelDisplays(raw.displays) };
}

// --- Boutons d'une fenêtre (UI Automation) : « Jouer » d'un launcher de jeu, une macro qui appuie sur un bouton.

async function appWindowHandles(app: string): Promise<number[]> {
  return (await listWindows(app)).windows.filter((w) => !w.elsewhere).map((w) => w.index);
}

/** Noms des éléments cliquables des fenêtres de l'appli (pour trouver celui à actionner) ; `total` : éléments vus en tout. */
export async function listButtons(app: string, detail = false): Promise<{ buttons: string[]; total: number }> {
  const windows = await appWindowHandles(app);
  if (windows.length === 0) return { buttons: [], total: 0 };
  const raw = await call<{ items?: string[]; total?: number }>("press", { action: "list", windows, detail }, 20_000);
  return { buttons: raw.items ?? [], total: raw.total ?? 0 };
}

/** Actionne le premier bouton de l'appli dont le nom est dans `names` ; null si aucun n'est (encore) là. */
export async function pressButton(app: string, names: string[]): Promise<string | null> {
  const windows = await appWindowHandles(app);
  if (windows.length === 0) return null;
  return (await call<{ pressed: string | null }>("press", { action: "invoke", windows, names }, 20_000)).pressed;
}

/** État vocal d'une appli d'appel (Discord) lu dans sa fenêtre ; `found` : faux si l'interface n'a pas été reconnue. */
export type VoiceState = {
  /** `streaming` : partage d'écran en cours (bouton « Arrêter de streamer »). */
  found: boolean;
  connected: boolean;
  streaming: boolean;
  channel: string | null;
  people: string[];
  muted: boolean | null;
  deafened: boolean | null;
  mic: string | null;
  deaf: string | null;
};

export async function voiceState(app: string): Promise<VoiceState | null> {
  const windows = await appWindowHandles(app);
  if (windows.length === 0) return null;
  return call<VoiceState>("voice", { windows }, 10_000);
}

export async function listDisplays() {
  return labelDisplays((await call<RawWindows>("windows", { action: "list" })).displays);
}

export function listWindows(app: string) {
  return windowsCall(app, { action: "list" });
}

export function focusWindow(app: string, window?: WindowTarget) {
  return windowsCall(app, { action: "focus", hwnd: window?.index });
}

export function placeWindow(app: string, opts: { window?: WindowTarget; display?: number; layout: Layout }) {
  return windowsCall(app, { action: "place", hwnd: opts.window?.index, display: opts.display, layout: opts.layout });
}

// --- Vue « Ouvertes » : toutes les fenêtres, groupées par appli (la plus en avant d'abord).

type RawOpen = { windows: (AppWindow & { app: string; exe: string })[]; displays: RawScreen[] };

function groupOpen(raw: RawOpen): OpenWindows {
  const apps = new Map<string, OpenApp>();
  for (const { app, exe, ...window } of raw.windows) {
    const key = `${app}\u0000${exe}`;
    const group = apps.get(key) ?? { key: exe, name: app, windows: [] };
    group.windows.push(window);
    apps.set(key, group);
  }
  return { apps: [...apps.values()], displays: labelDisplays(raw.displays) };
}

export async function listOpenWindows() {
  return groupOpen(await call<RawOpen>("allwindows", { action: "list" }));
}

/** Les fenêtres sont désignées par leur handle : `key` (programme) ne sert pas. */
export async function controlOpenWindow(
  _key: string,
  window: WindowTarget,
  action: OpenAction,
  opts?: { display?: number; layout: Layout },
) {
  // Pas de plein écran à part sous Windows : agrandir.
  await call("allwindows", { action: action === "fullscreen" ? "zoom" : action, hwnd: window.index, ...opts });
}

// --- Système

export async function systemCommand(command: SystemCommand) {
  await call("system", { command });
}

/** Ne pas déranger : lu dans les réglages, sans rien ouvrir (null : inconnu). */
export async function getDnd(): Promise<boolean | null> {
  return (await call<{ on: boolean | null }>("dnd", {})).on;
}

/** Bascule l'interrupteur du centre de notifications (affiché ~0,5 s), seulement si l'état doit changer. */
export async function setDnd(on: boolean): Promise<boolean | null> {
  return (await call<{ on: boolean | null }>("dnd", { on }, 10_000)).on;
}

/** Message au premier plan (code d'appairage), sans attendre qu'il soit fermé. */
export async function alert(title: string, text: string) {
  await call("alert", { title, text });
}

/** Pavé tactile : opération `pointer` du pilote (SendInput). */
export async function pointerMove(dx: number, dy: number) {
  await call("pointer", { kind: "move", dx, dy });
}

export async function pointerScroll(sx: number, sy: number) {
  await call("pointer", { kind: "scroll", sx, sy });
}

/** Zoom : Ctrl + molette, `steps` crans (positif = agrandir). */
export async function pointerZoom(steps: number) {
  await call("pointer", { kind: "zoom", steps });
}

export async function pointerButton(button: PointerButton, action: PointerAction) {
  await call("pointer", { kind: "button", button, action });
}
