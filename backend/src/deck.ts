import fs from "node:fs/promises";
import { existsSync } from "node:fs";
import path from "node:path";
import { createHash } from "node:crypto";
import { config } from "./config.ts";
import {
  appIcon, canMedia, isInstalled, launchApp, listWindows, openTarget, placeWindow, runShortcut, sendKeys, sendMediaKey,
  systemCommand, typeText, type MediaCommand, type SystemCommand,
} from "./os.ts";
import type { Layout } from "./screens.ts";
import { loadKeys, loadLinks, loadSavedApps, saveApps, saveLinks, type DeckApp, type DeckLink } from "./perso.ts";
import { calendarSources } from "./calendar.ts";

/**
 * Étapes possibles d'une tuile. La tablette n'envoie qu'un identifiant de tuile : seules les actions
 * de config/deck.json et des touches perso (.data/keys.json, voir perso.ts) peuvent être exécutées.
 */
export type Step =
  | { type: "app"; name: string }
  | { type: "url"; url: string }
  | { type: "keys"; keys: string; app?: string }
  | { type: "shortcut"; name: string }
  | { type: "media"; command: MediaCommand }
  | { type: "system"; command: SystemCommand }
  /** Colle un texte dans l'appli au premier plan. */
  | { type: "text"; text: string }
  | { type: "wait"; ms: number }
  /** Place les fenêtres d'une appli (lancée si besoin) ; `display` : libellé d'écran ("Mac", nom du moniteur). */
  | { type: "window"; app: string; display?: string; layout?: Layout };

/** Une macro enchaîne des étapes. */
export type Action = Step | { type: "macro"; steps: Step[] };

/** glyph : symbole affiché à la place de l'icône (emoji), utile pour les raccourcis. */
type TileConfig = { label: string; action: Action; icon?: string; glyph?: string; color?: string };
/** `apps` : page d'applis que l'on complète depuis la tablette (voir saveApps) ; `meeting` : page affichée
 * par la tablette après « Rejoindre » (micro, caméra, raccrocher) ; `app` : page d'une appli (Discord, Teams…),
 * montrée seulement si l'appli est dans la page Apps ; sur la tablette ce n'est pas un onglet : toucher l'appli
 * ouvre sa page (sans la lancer), qui affiche son volume (Windows) ; `media` : lecture en cours au-dessus des touches,
 * et pour touches les sites ajoutés depuis la tablette (.data/links.json, vide au départ ; `tiles` est ignoré). */
type DeckConfig = { pages: { title: string; apps?: boolean; meeting?: boolean; app?: string; media?: boolean; tiles: TileConfig[] }[] };

export type Tile = TileConfig & { id: string };
/** `empty` : emplacement libre de la page Perso. */
export type DeckPage = {
  title: string; editable?: boolean; apps?: boolean; meeting?: boolean; app?: string; media?: boolean;
  tiles: (Tile | { id: string; empty: true })[];
};

export const PERSO_PREFIX = "k-";

async function loadConfig(): Promise<DeckConfig> {
  const file = existsSync(config.deckFile) ? config.deckFile : config.deckExampleFile;
  return JSON.parse(await fs.readFile(file, "utf8")) as DeckConfig;
}

/** Identifiant tiré du nom de l'appli : stable quand on en retire une autre (cache d'icônes de la tablette). */
const appTileId = (name: string) => "a-" + createHash("sha1").update(name).digest("hex").slice(0, 10);

/** Applis de la page Apps : la liste enregistrée, sinon celles du profil de base installées sur cet ordinateur. */
async function deckApps(deck: DeckConfig): Promise<DeckApp[]> {
  const saved = await loadSavedApps();
  if (saved) return saved;
  const base = (deck.pages.find((p) => p.apps)?.tiles ?? [])
    .flatMap((t) => (t.action.type === "app" ? [{ label: t.label, name: t.action.name }] : []));
  const present = await Promise.all(base.map((a) => isInstalled(a.name).catch(() => true)));
  return base.filter((_, i) => present[i]);
}

export async function addDeckApp(name: string) {
  const apps = await deckApps(await loadConfig());
  if (!apps.some((a) => a.name === name)) {
    apps.push({ label: name.replace(/^Microsoft /, ""), name });
    await saveApps(apps);
  }
  return appTileId(name);
}

export async function removeDeckApp(id: string) {
  const apps = await deckApps(await loadConfig());
  await saveApps(apps.filter((a) => appTileId(a.name) !== id));
}

export const resetDeckApps = () => saveApps(null);

// --- Page Média : sites de vidéo et de musique choisis depuis la tablette.

const linkTileId = (url: string) => "l-" + createHash("sha1").update(url).digest("hex").slice(0, 10);

/** Proposés dans la liste de la tablette ; on peut aussi coller n'importe quelle adresse. */
export const LINK_SUGGESTIONS: DeckLink[] = [
  { label: "YouTube", url: "https://www.youtube.com" },
  { label: "YouTube Music", url: "https://music.youtube.com" },
  { label: "Twitch", url: "https://www.twitch.tv" },
  { label: "Netflix", url: "https://www.netflix.com" },
  { label: "Crunchyroll", url: "https://www.crunchyroll.com" },
  { label: "ADN", url: "https://animationdigitalnetwork.com" },
  { label: "Prime Video", url: "https://www.primevideo.com" },
  { label: "Disney+", url: "https://www.disneyplus.com" },
  { label: "Canal+", url: "https://www.canalplus.com" },
  { label: "france.tv", url: "https://www.france.tv" },
  { label: "Arte", url: "https://www.arte.tv" },
  { label: "Spotify", url: "https://open.spotify.com" },
  { label: "Deezer", url: "https://www.deezer.com" },
  { label: "SoundCloud", url: "https://soundcloud.com" },
];

export async function deckLinks() {
  return (await loadLinks()).map((l) => ({ ...l, id: linkTileId(l.url) }));
}

/** `url` tapée sur la tablette : « netflix.com » devient https://netflix.com ; nom par défaut tiré de l'adresse. */
export async function addDeckLink(url: string, label?: string) {
  const parsed = new URL(/^[a-z][a-z0-9+.-]*:\/\//i.test(url.trim()) ? url.trim() : "https://" + url.trim());
  if (parsed.protocol !== "http:" && parsed.protocol !== "https:") throw new Error("Adresse web attendue (https://…)");
  const href = parsed.pathname === "/" && !parsed.search && !parsed.hash ? parsed.origin : parsed.href;
  const name = label?.trim() || parsed.hostname.replace(/^www\./, "");
  const links = await loadLinks();
  if (!links.some((l) => l.url === href)) {
    links.push({ label: name, url: href });
    await saveLinks(links);
  }
  return linkTileId(href);
}

export async function removeDeckLink(id: string) {
  await saveLinks((await loadLinks()).filter((l) => linkTileId(l.url) !== id));
}

// Appli qui donne un sens au bouton Rejoindre (nom du .app sur Mac, du menu Démarrer sur Windows).
const MEETING_APPS = /^(Microsoft )?Teams/i;

/**
 * Cartes de l'Accueil selon cet ordinateur : agenda s'il en a un (lien ICS), bouton Rejoindre si Teams
 * est dans la page Apps.
 */
export async function homeFeatures() {
  const names = (await deckApps(await loadConfig())).map((a) => a.name);
  return { agenda: (await calendarSources()).length > 0, meetings: names.some((n) => MEETING_APPS.test(n)) };
}

export async function loadDeck(): Promise<DeckPage[]> {
  const deck = await loadConfig();
  const apps = await deckApps(deck);
  // Pages d'applis absentes de la page Apps (pas installées, ou retirées depuis la tablette) : masquées après
  // la numérotation, les identifiants de tuiles ne bougent pas.
  const names = new Set(apps.map((a) => a.name.toLowerCase()));
  // Page Média (`media` : lecture en cours au-dessus des touches) seulement si l'ordinateur sait la lire.
  const present = deck.pages.map((page) => (!page.app || names.has(page.app.toLowerCase())) && (!page.media || canMedia));
  const links = await deckLinks();
  const pages = deck.pages.map((page, p): DeckPage =>
    page.apps
      ? {
          title: page.title,
          apps: true,
          tiles: apps.map((a) => ({ label: a.label, action: { type: "app", name: a.name }, id: appTileId(a.name) })),
        }
      : page.media
      ? { title: page.title, media: true, tiles: links.map((l) => ({ label: l.label, action: { type: "url", url: l.url }, id: l.id })) }
      : {
          title: page.title, meeting: page.meeting, app: page.app, media: page.media,
          tiles: page.tiles.map((tile, t) => ({ ...tile, id: `${p}-${t}` })),
        },
  ).filter((_, p) => present[p]);
  const keys = await loadKeys();
  pages.push({
    title: "Perso",
    editable: true,
    tiles: keys.map((key, slot) => {
      const id = `${PERSO_PREFIX}${slot}`;
      if (!key) return { id, empty: true as const };
      const action: Action = key.steps.length === 1 ? key.steps[0] : { type: "macro", steps: key.steps };
      return { id, label: key.label, glyph: key.glyph, color: key.color, action };
    }),
  });
  return pages;
}

export async function findTile(id: string): Promise<Tile | undefined> {
  return (await loadDeck()).flatMap((p) => p.tiles).find((t): t is Tile => t.id === id && !("empty" in t));
}

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

export async function runAction(action: Action): Promise<unknown> {
  switch (action.type) {
    case "macro":
      for (const [i, step] of action.steps.entries()) {
        await runAction(step);
        // Laisser l'appli arriver au premier plan avant d'y envoyer des touches.
        if (step.type === "app" && i < action.steps.length - 1) await sleep(600);
      }
      return;
    case "app":
      return launchApp(action.name);
    case "url":
      if (!/^(https?|mailto|msteams|ms-teams|slack|vscode|zoommtg|x-apple[\w.-]*|ms-settings):/i.test(action.url)) {
        throw new Error(`Schéma d'URL non autorisé : ${action.url}`);
      }
      return openTarget(action.url);
    case "keys":
      return sendKeys(action.keys, action.app);
    case "shortcut":
      return runShortcut(action.name);
    case "media":
      return sendMediaKey(action.command);
    case "text":
      return typeText(action.text);
    case "wait":
      return sleep(Math.min(action.ms, 10_000));
    case "window":
      return placeAppWindows(action.app, action.display, action.layout ?? "keep");
    case "system":
      return systemCommand(action.command);
  }
  throw new Error(`Action inconnue : ${JSON.stringify(action)}`);
}

/** Lance l'appli si besoin, attend sa première fenêtre (6 s max), puis la place. */
async function placeAppWindows(app: string, displayLabel: string | undefined, layout: Layout) {
  // Fenêtres des autres bureaux comprises : open -a fait passer sur le bureau de l'appli.
  const visible = (i: { windows: { elsewhere: boolean }[] }) => i.windows.some((w) => !w.elsewhere);
  let info = await listWindows(app);
  if (!visible(info)) {
    await launchApp(app);
    for (let i = 0; i < 12 && !visible(info); i++) {
      await sleep(500);
      info = await listWindows(app);
    }
  }
  // Écran absent (moniteur débranché) : on reste sur l'écran actuel de chaque fenêtre.
  const display = info.displays.find((d) => d.label.toLowerCase() === displayLabel?.toLowerCase())?.id;
  return placeWindow(app, { display, layout });
}

// --- Icônes : celle de l'appli (.icns sur Mac, menu Démarrer sur Windows), en PNG 96 px mis en cache.

/** Appli dont l'icône représente l'action (la première appli citée, pour une macro). */
function appOf(action: Action): string | undefined {
  switch (action.type) {
    case "app": return action.name;
    case "keys": return action.app;
    case "window": return action.app;
    case "macro": return action.steps.map(appOf).find(Boolean);
    default: return undefined;
  }
}

export async function tileIcon(tile: Tile): Promise<string | undefined> {
  if (tile.icon) {
    // Icône perso : chemin absolu ou relatif au dossier config/.
    const custom = path.resolve(path.dirname(config.deckFile), tile.icon);
    return existsSync(custom) ? custom : undefined;
  }
  const appName = appOf(tile.action);
  const site = appName ? undefined : urlOf(tile.action);
  if (!appName && !site) return undefined;

  const key = appName ?? "url:" + site!.origin;
  const cached = path.join(config.cacheDir, "icons", createHash("sha1").update(key).digest("hex") + ".png");
  if (existsSync(cached)) return cached;

  await fs.mkdir(path.dirname(cached), { recursive: true });
  const ok = appName ? await appIcon(appName, cached).catch(() => false) : await siteIcon(site!, cached).catch(() => false);
  return ok && existsSync(cached) ? cached : undefined;
}

/** Site ouvert par l'action (le premier lien d'une macro), pour une tuile sans appli. */
function urlOf(action: Action): URL | undefined {
  const url = action.type === "url" ? action.url : action.type === "macro" ? action.steps.find((s) => s.type === "url")?.url : undefined;
  if (!url) return undefined;
  try {
    const parsed = new URL(url);
    return parsed.protocol === "http:" || parsed.protocol === "https:" ? parsed : undefined;
  } catch {
    return undefined;
  }
}

const BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36";

/**
 * Icône d'un site : les PNG déclarés par la page et par son manifeste, puis /apple-touch-icon.png, puis le service
 * d'icônes de Google. On garde la première entre 96 et 512 px (Twitch ne déclare que du 32 px mais a un
 * apple-touch-icon en 180 ; Canal+ déclare du 2048, trop lourd pour la tablette), sinon la plus proche ;
 * sous 48 px (Arte : 16 px partout), pas d'icône : la tablette affiche l'initiale plutôt qu'une image floue.
 */
async function siteIcon(site: URL, out: string): Promise<boolean> {
  const declared: { url: string; size: number }[] = [];
  const get = (url: string | URL) => fetch(url, { headers: { "user-agent": BROWSER_UA }, signal: AbortSignal.timeout(8000) });
  try {
    const page = await get(site);
    const html = (await page.text()).slice(0, 1_000_000);
    let manifest: string | undefined;
    for (const tag of html.match(/<link\b[^>]*>/gi) ?? []) {
      const attr = (name: string) => tag.match(new RegExp(`\\b${name}\\s*=\\s*["']?([^"'\\s>]+(?:\\s[^"'>=]+)*)`, "i"))?.[1];
      const rel = attr("rel")?.toLowerCase() ?? "";
      const href = attr("href");
      if (!href) continue;
      if (rel === "manifest") manifest = new URL(href, page.url).href;
      if (!/(^|\s)(icon|apple-touch-icon)/.test(rel) || /\.(svg|ico)(\?|$)/i.test(href)) continue;
      const size = Number(attr("sizes")?.match(/(\d+)x\d+/i)?.[1] ?? 0);
      declared.push({ url: new URL(href, page.url).href, size: size || (rel.includes("apple") ? 180 : 0) });
    }
    if (manifest) {
      const icons = ((await (await get(manifest)).json()) as { icons?: { src?: string; sizes?: string; type?: string }[] }).icons ?? [];
      for (const icon of icons) {
        if (!icon.src || /svg/i.test(icon.type ?? "") || /\.(svg|ico)(\?|$)/i.test(icon.src)) continue;
        declared.push({ url: new URL(icon.src, manifest).href, size: Number(icon.sizes?.match(/(\d+)x\d+/i)?.[1] ?? 0) });
      }
    }
  } catch {
    // Page ou manifeste injoignable : on passe aux adresses standard.
  }
  // Tailles utiles d'abord (la plus petite au-dessus de 96 px), puis les autres de la plus grande à la plus petite.
  const fits = (size: number) => size >= 96 && size <= 512;
  declared.sort((a, b) => (fits(a.size) !== fits(b.size) ? (fits(a.size) ? -1 : 1) : fits(a.size) ? a.size - b.size : b.size - a.size));
  const candidates = [
    ...declared.map((d) => d.url),
    new URL("/apple-touch-icon.png", site).href,
    `https://www.google.com/s2/favicons?domain=${encodeURIComponent(site.hostname)}&sz=128`,
  ];

  let best: { data: Buffer; width: number } | undefined;
  for (const url of [...new Set(candidates)]) {
    const data = await get(url)
      .then(async (r) => (r.ok ? Buffer.from(await r.arrayBuffer()) : undefined))
      .catch(() => undefined);
    const width = data && imageWidth(data);
    if (!data || width === undefined || width > 1024) continue;
    if (!best || Math.abs(width - 128) < Math.abs(best.width - 128)) best = { data, width };
    if (fits(width)) break;
  }
  if (!best || best.width < 48) return false;
  await fs.writeFile(out, best.data);
  return true;
}

/** Largeur d'un PNG (en-tête IHDR) ; un JPEG est accepté tel quel ; undefined si ce n'est pas une image lisible. */
function imageWidth(data: Buffer): number | undefined {
  if (data.length > 24 && data.readUInt32BE(0) === 0x89504e47) return data.readUInt32BE(16);
  if (data.length > 3 && data[0] === 0xff && data[1] === 0xd8) return 96;
  return undefined;
}
