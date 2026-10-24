import { call } from "./helper.ts";
import { labelDisplays, type AppWindow, type Layout, type RawScreen, type WindowsInfo, type WindowTarget } from "../screens.ts";
import type { AudioState } from "../mac/audio.ts";

/**
 * Pilotage de Windows (10 et 11), mêmes fonctions que les modules de src/mac/ : voir src/os.ts.
 * Les applis sont celles du menu Démarrer (applis classiques et du Store), lancées par leur
 * identifiant (AppUserModelID) ; le code natif est dans native.cs.
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

const simple = (s: string) => s.toLowerCase().replace(/^microsoft\s+/, "").trim();

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
  const names = (await startApps(true)).map((a) => a.name);
  return [...new Set(names)].sort((a, b) => a.localeCompare(b, "fr", { sensitivity: "base" }));
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
  cmd: 0x11, command: 0x11, ctrl: 0x11, control: 0x11,
  shift: 0x10, alt: 0x12, option: 0x12,
  win: 0x5b, windows: 0x5b, super: 0x5b, meta: 0x5b,
};

const KEY_CODES: Record<string, number> = {
  return: 0x0d, enter: 0x0d, tab: 0x09, space: 0x20, delete: 0x08, backspace: 0x08, del: 0x2e, forwarddelete: 0x2e,
  esc: 0x1b, escape: 0x1b, left: 0x25, up: 0x26, right: 0x27, down: 0x28,
  home: 0x24, end: 0x23, pageup: 0x21, pagedown: 0x22, insert: 0x2d, printscreen: 0x2c,
  ...Object.fromEntries(Array.from({ length: 12 }, (_, i) => [`f${i + 1}`, 0x70 + i])),
};

export async function sendKeys(combo: string, app?: string) {
  const parts = combo.toLowerCase().split("+").map((p) => p.trim()).filter(Boolean);
  const key = parts.pop();
  if (!key) throw new Error(`Raccourci invalide : ${combo}`);
  const mods = parts.map((p) => {
    const vk = MODIFIERS[p];
    if (vk === undefined) throw new Error(`Modificateur inconnu : ${p}`);
    return vk;
  });
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

const MEDIA_KEYS = { "play-pause": 0xb3, next: 0xb0, previous: 0xb1 } as const;

export async function sendMediaKey(command: keyof typeof MEDIA_KEYS) {
  await call("keys", { mods: [], vk: MEDIA_KEYS[command] });
}

export async function typeText(text: string) {
  await call("text", { text });
}

// --- Son

export async function getAudio(): Promise<AudioState> {
  return call<AudioState>("audio");
}

export async function setAudio(change: { output?: number; outputDelta?: number; muted?: boolean; micMuted?: boolean }): Promise<AudioState> {
  const current = await getAudio();
  const clamp = (v: number) => Math.max(0, Math.min(100, Math.round(v)));
  let output: number | undefined;
  if (change.output !== undefined) output = clamp(change.output);
  else if (change.outputDelta !== undefined && current.output !== null) output = clamp(current.output + change.outputDelta);
  // Monter le volume réactive le son, comme les touches du clavier.
  const muted = change.muted ?? (output !== undefined && output > 0 && current.muted ? false : undefined);
  return call<AudioState>("audio", { output, muted, micMuted: change.micMuted });
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

// --- Lecture en cours (contrôles multimédias de Windows)

/** `app` : identifiant Windows de l'appli qui joue (navigateur, Spotify…) ; `name` : son nom lisible. */
export type MediaSession = {
  app: string; name: string; current: boolean; title: string; artist: string; album: string; hasThumb: boolean;
  status: string; canPlayPause: boolean; canNext: boolean; canPrevious: boolean; canSeek: boolean;
  positionMs: number | null; durationMs: number | null;
};
export type MediaAction = "play-pause" | "next" | "previous" | "seek";

const MEDIA_APPS: [RegExp, string][] = [
  [/chrome/i, "Chrome"], [/msedge/i, "Edge"], [/firefox|308046B0AF4A39CB/i, "Firefox"], [/opera/i, "Opera"],
  [/brave/i, "Brave"], [/spotify/i, "Spotify"], [/vlc/i, "VLC"], [/zunemusic|music\.ui/i, "Lecteur multimédia"],
];

function mediaAppName(app: string) {
  const known = MEDIA_APPS.find(([re]) => re.test(app));
  // "Spotify.exe", "Editeur.Appli_abc123!App" : le nom de l'exécutable ou du paquet.
  return known ? known[1] : app.replace(/\.exe$/i, "").replace(/_[a-z0-9]+!.*$/i, "").split(/[.\\]/).pop() || app;
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

/** Vignette de ce qui joue (pochette, image de la vidéo) écrite dans `out`. */
/** Page ouverte dans le navigateur au premier plan (le plus en avant des navigateurs) : voir helper.ps1. */
export function browserPage() {
  return call<{ found: boolean; url?: string; title?: string; browser?: string }>("browser");
}

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
export async function listButtons(app: string): Promise<{ buttons: string[]; total: number }> {
  const windows = await appWindowHandles(app);
  if (windows.length === 0) return { buttons: [], total: 0 };
  const raw = await call<{ items?: string[]; total?: number }>("press", { action: "list", windows }, 20_000);
  return { buttons: raw.items ?? [], total: raw.total ?? 0 };
}

/** Actionne le premier bouton de l'appli dont le nom est dans `names` ; null si aucun n'est (encore) là. */
export async function pressButton(app: string, names: string[]): Promise<string | null> {
  const windows = await appWindowHandles(app);
  if (windows.length === 0) return null;
  return (await call<{ pressed: string | null }>("press", { action: "invoke", windows, names }, 20_000)).pressed;
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

// --- Système

export async function systemCommand(command: "sleep-display" | "toggle-dark-mode" | "screenshot-clipboard" | "lock") {
  await call("system", { command });
}

/** Message au premier plan (code d'appairage), sans attendre qu'il soit fermé. */
export async function alert(title: string, text: string) {
  await call("alert", { title, text });
}
