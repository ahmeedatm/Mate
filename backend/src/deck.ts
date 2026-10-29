import fs from "node:fs/promises";
import { existsSync } from "node:fs";
import path from "node:path";
import { createHash } from "node:crypto";
import { config } from "./config.ts";
import {
  appIcon,
  canControl,
  canMedia,
  isInstalled,
  launchApp,
  listWindows,
  openTarget,
  placeWindow,
  pressButton,
  runShortcut,
  sendKeys,
  sendMediaKey,
  systemCommand,
  typeText,
  type MediaCommand,
  type SystemCommand,
} from "./os.ts";
import type { Layout } from "./screens.ts";
import { loadKeys, loadLinks, loadSavedApps, saveApps, saveLinks, type DeckApp, type DeckLink } from "./perso.ts";
import { agentsAvailable } from "./agents-sources.ts";
import { hasCalendar } from "./calendar.ts";
import { jiraConfigured } from "./jira.ts";
import { appPluginOff, pluginOn, pluginsOff } from "./plugins.ts";
import { assertPublicUrl, fetchPublic } from "./net-guard.ts";

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
  /**
   * Appuie sur un bouton de la fenêtre de l'appli (UI Automation, Accessibilité sur Mac), le premier trouvé parmi
   * `names` ; `waitMs` : le bouton peut mettre ce temps à apparaître (panneau ouvert par l'étape d'avant).
   */
  | { type: "press"; app: string; names: string[]; waitMs?: number }
  /** Place les fenêtres d'une appli (lancée si besoin) ; `display` : libellé d'écran ("Mac", nom du moniteur). */
  | { type: "window"; app: string; display?: string; layout?: Layout };

/** Une macro enchaîne des étapes. */
export type Action = Step | { type: "macro"; steps: Step[] };

/** glyph : symbole affiché à la place de l'icône (emoji), utile pour les raccourcis. */
/**
 * when : page d'appli d'appel, touche montrée seulement en appel (`call`), hors appel (`idle`), pendant un partage
 * d'écran (`streaming`), en appel sans partage (`share` : « Partager », remplacé par « Arrêter le partage ») ou sur
 * l'écran d'avant-réunion de Teams (`lobby` : « Rejoindre maintenant »).
 * lit : touche allumée (rouge, comme Muet dans l'onglet Son) quand le micro est coupé (`muted`) ou le casque en
 * sourdine (`deafened`), ou la caméra coupée (`camera`, Teams sur Mac), d'après l'appel lu dans l'appli.
 */
type TileConfig = {
  label: string;
  action: Action;
  icon?: string;
  glyph?: string;
  color?: string;
  when?: "call" | "idle" | "streaming" | "share" | "lobby";
  lit?: "muted" | "deafened" | "camera";
};
/** `apps` : page d'applis que l'on complète depuis la tablette (voir saveApps) ; `meeting` : page affichée
 * par la tablette après « Rejoindre » (micro, caméra, raccrocher) ; `app` : page d'une appli (Discord, Teams…),
 * montrée seulement si l'appli est dans la page Apps ; sur la tablette ce n'est pas un onglet : toucher l'appli
 * ouvre sa page (sans la lancer), qui affiche son volume (Windows) ; `media` : lecture en cours au-dessus des touches,
 * et pour touches les sites ajoutés depuis la tablette (.data/links.json, vide au départ ; `tiles` est ignoré) ;
 * `sound: false` : pas de barre « Son de l'ordinateur » sur la page (Word, Excel : des raccourcis, pas de son) ;
 * `mail` : page Outlook, courrier du compte Microsoft relié au-dessus des touches (src/mail.ts). */
type DeckConfig = {
  pages: {
    title: string;
    apps?: boolean;
    meeting?: boolean;
    app?: string;
    media?: boolean;
    sound?: boolean;
    mail?: boolean;
    tiles: TileConfig[];
  }[];
};

export type Tile = TileConfig & { id: string };
/** `empty` : emplacement libre de la page Perso. */
export type DeckPage = {
  title: string;
  editable?: boolean;
  apps?: boolean;
  meeting?: boolean;
  app?: string;
  media?: boolean;
  sound?: boolean;
  mail?: boolean;
  /** Appli absente de la page Apps : sa page ne s'affiche que pendant un appel (`/deck/live`). */
  callOnly?: boolean;
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
  const base = (deck.pages.find((p) => p.apps)?.tiles ?? []).flatMap((t) =>
    t.action.type === "app" ? [{ label: t.label, name: t.action.name }] : [],
  );
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

// Teams dans le deck (nom du .app sur Mac, du menu Démarrer sur Windows) : page Réunion après « Rejoindre ».
const MEETING_APPS = /^(Microsoft )?Teams/i;

/**
 * Cartes de l'Accueil selon cet ordinateur : agenda toujours (agenda personnel rempli depuis la tablette), `linked` s'il
 * a aussi un agenda relié (lien ICS, compte), bouton Rejoindre si Teams est dans la page Apps.
 */
export async function homeFeatures() {
  const names = (await deckApps(await loadConfig())).map((a) => a.name);
  // `meetings` : bouton Rejoindre et option d'ouverture automatique. Toute réunion avec un lien de visio s'ouvre
  // (appli ou navigateur) ; `teams` : l'appli Teams est dans le deck (page Réunion et ses raccourcis).
  const teams = names.some((n) => MEETING_APPS.test(n)) && (await pluginOn("teams"));
  // `agents` : onglet et widget des agents Claude (Mac avec Claude Code installé). `off` : plugins coupés (plugins.ts).
  const agents = agentsAvailable() && (await pluginOn("claude"));
  // `jira` : onglet Jira (JIRA_SITE, JIRA_EMAIL et JIRA_API_TOKEN dans .env).
  const jira = jiraConfigured && (await pluginOn("jira"));
  return { agenda: true, linked: await hasCalendar(), meetings: canControl, teams, agents, jira, off: await pluginsOff() };
}

export async function loadDeck(): Promise<DeckPage[]> {
  const deck = await loadConfig();
  const apps = await deckApps(deck);
  // Pages d'applis absentes de la page Apps (pas installées, retirées depuis la tablette, ou installées après
  // l'enregistrement de la liste) : gardées pour les appels seulement (`callOnly`, onglet pendant l'appel, Répondre
  // depuis la tablette y mène).
  const names = new Set(apps.map((a) => a.name.toLowerCase()));
  // Page Média (`media` : lecture en cours au-dessus des touches) seulement si l'ordinateur sait la lire ; masquée
  // après la numérotation, les identifiants de tuiles ne bougent pas.
  // Pages des plugins coupés sur la tablette (Discord, Teams, Média) : masquées de la même façon.
  const mediaOn = canMedia && (await pluginOn("media"));
  const appOff = await Promise.all(deck.pages.map((page) => (page.app ? appPluginOff(page.app) : false)));
  const present = deck.pages.map((page, p) => (!page.media || mediaOn) && !appOff[p]);
  const links = await deckLinks();
  const pages = deck.pages
    .map((page, p): DeckPage =>
      page.apps
        ? {
            title: page.title,
            apps: true,
            tiles: apps.map((a) => ({ label: a.label, action: { type: "app", name: a.name }, id: appTileId(a.name) })),
          }
        : page.media
          ? { title: page.title, media: true, tiles: links.map((l) => ({ label: l.label, action: { type: "url", url: l.url }, id: l.id })) }
          : {
              title: page.title,
              meeting: page.meeting,
              app: page.app,
              media: page.media,
              sound: page.sound,
              mail: page.mail,
              ...(page.app && !names.has(page.app.toLowerCase()) ? { callOnly: true } : {}),
              tiles: page.tiles.map((tile, t) => ({ ...tile, id: `${p}-${t}` })),
            },
    )
    .filter((_, p) => present[p]);
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
    case "press": {
      const until = Date.now() + Math.min(action.waitMs ?? 0, 10_000);
      while (!(await pressButton(action.app, action.names))) {
        if (Date.now() >= until) throw new Error(`Bouton introuvable dans ${action.app}`);
        await sleep(300);
      }
      return;
    }
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
    case "app":
      return action.name;
    case "keys":
      return action.app;
    case "window":
      return action.app;
    case "macro":
      return action.steps.map(appOf).find(Boolean);
    default:
      return undefined;
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

  return appName ? appIconFile(appName) : cachedIcon("url:" + site!.origin, (out) => siteIcon(site!, out));
}

/** Icône d'une appli de l'ordinateur (tuile, vue « Ouvertes »), gardée en cache. */
export function appIconFile(name: string) {
  return cachedIcon(name, (out) => appIcon(name, out));
}

async function cachedIcon(key: string, make: (out: string) => Promise<boolean>): Promise<string | undefined> {
  const cached = path.join(config.cacheDir, "icons", createHash("sha1").update(key).digest("hex") + ".png");
  if (existsSync(cached)) return cached;
  await fs.mkdir(path.dirname(cached), { recursive: true });
  const ok = await make(cached).catch(() => false);
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

/** Plafonds de téléchargement : page ou manifeste, image. */
const TEXT_MAX_BYTES = 1_000_000;
const IMAGE_MAX_BYTES = 2_000_000;
const BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36";

/**
 * Icône d'un site : les PNG déclarés par la page et par son manifeste, puis /apple-touch-icon.png, tous lus sur le
 * site lui-même (pas de service d'icônes tiers : son nom ne part chez personne, docs/confidentialite.md). On garde la première entre 96 et 512 px (Twitch ne déclare que du 32 px mais a un
 * apple-touch-icon en 180 ; Canal+ déclare du 2048, trop lourd pour la tablette), sinon la plus proche ;
 * sous 48 px (Arte : 16 px partout), pas d'icône : la tablette affiche l'initiale plutôt qu'une image floue.
 */
async function siteIcon(site: URL, out: string): Promise<boolean> {
  // Site du réseau local : pas d'icône.
  await assertPublicUrl(site);
  const declared: { url: string; size: number }[] = [];
  const get = (url: string | URL, maxBytes: number) => fetchPublic(url, { maxBytes, headers: { "user-agent": BROWSER_UA } });
  try {
    const page = await get(site, TEXT_MAX_BYTES);
    const html = page.data.toString("utf8");
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
      const icons =
        (
          JSON.parse((await get(manifest, TEXT_MAX_BYTES)).data.toString("utf8")) as {
            icons?: { src?: string; sizes?: string; type?: string }[];
          }
        ).icons ?? [];
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
  const candidates = [...declared.map((d) => d.url), new URL("/apple-touch-icon.png", site).href];

  let best: { data: Buffer; width: number } | undefined;
  for (const url of [...new Set(candidates)]) {
    const data = await get(url, IMAGE_MAX_BYTES)
      .then((r) => (r.ok && !r.truncated ? r.data : undefined))
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
