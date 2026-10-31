import fs from "node:fs/promises";
import path from "node:path";
import { backendRoot } from "../config.ts";
import type { MediaCommand } from "../screens.ts";
import { sendToExtension } from "../browser-extension.ts";
import { fetchPublic } from "../net-guard.ts";
import { sendMediaKey } from "./keys.ts";
import { osascript, run } from "./run.ts";

/**
 * Lecture en cours sur le Mac (page Média de la tablette), sans aucun réglage : comme les contrôles multimédias de
 * Windows, MediaRemote (« À l'écoute » du Centre de contrôle) connaît ce qui joue dans n'importe quelle appli ou
 * navigateur. Depuis macOS 15.4, il ne répond qu'aux programmes d'Apple : NowPlaying.dylib est chargée par
 * /usr/bin/perl (src/mac/nowplaying/NowPlaying.m). Il ne connaît qu'une lecture : les autres (et toutes, s'il ne dit
 * rien) viennent du secours : Spotify et Musique par AppleScript, titre des onglets des sites connus, « joue » lu dans
 * les assertions d'énergie (`pmset`), commandes par l'extension du navigateur ou les touches multimédias.
 */
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
  /** Navigateur qui joue, quand `name` affiche le site à sa place ([enrichBrowserSession]). */
  browser?: string;
};
export type MediaAction = MediaCommand | "seek";

const DYLIB = path.join(backendRoot, "src", "mac", "NowPlaying.dylib");
const LOADER =
  '$ENV{NP_ACTION} = $ARGV[1]; $ENV{NP_ARG} = $ARGV[2] // ""; require DynaLoader; DynaLoader::dl_load_file($ARGV[0]) or die DynaLoader::dl_error()';

async function nowPlaying<T>(action: string, arg = ""): Promise<T | undefined> {
  const out = await run("/usr/bin/perl", ["-e", LOADER, DYLIB, action, arg], 5000).catch(() => "");
  try {
    return JSON.parse(out) as T;
  } catch {
    return undefined;
  }
}

export type RawNowPlaying = {
  bundle?: string | null;
  name?: string | null;
  title?: string | null;
  artist?: string | null;
  album?: string | null;
  duration?: number | null;
  elapsed?: number | null;
  rate?: number | null;
  playing?: boolean;
  timestampMs?: number | null;
  hasArt?: boolean | number;
};

const ms = (s: number | null | undefined) => (typeof s === "number" && isFinite(s) ? Math.round(s * 1000) : null);

/** Réponse de MediaRemote → session ; la position avance depuis l'instant où elle a été notée (`timestampMs`). */
export function sessionOfNowPlaying(raw: RawNowPlaying, nowMs = Date.now()): MediaSession | undefined {
  if (!raw.title) return undefined;
  const playing = raw.playing ?? (raw.rate ?? 0) > 0;
  const duration = ms(raw.duration);
  let position = ms(raw.elapsed);
  if (position !== null && playing && raw.timestampMs) position += Math.max(0, nowMs - raw.timestampMs) * (raw.rate || 1);
  if (position !== null && duration) position = Math.min(position, duration);
  return {
    app: raw.bundle || raw.name || "MediaRemote",
    name: raw.name || "",
    current: true,
    title: raw.title,
    artist: raw.artist ?? "",
    album: raw.album ?? "",
    hasThumb: !!raw.hasArt || !!raw.artist,
    status: playing ? "playing" : "paused",
    canPlayPause: true,
    canNext: true,
    canPrevious: true,
    canSeek: !!duration,
    positionMs: position === null ? null : Math.round(position),
    durationMs: duration,
  };
}

/** Durée pendant laquelle une lecture qui vient de jouer reste affichée (changement de morceau ; plusieurs Précédent de suite dans un navigateur lent dépassent 15 s). */
export const HOLD_MS = 30_000;

/**
 * Au changement de morceau d'un site (YouTube Music dans Opera GX), MediaRemote ne dit rien puis rend la main ~5 s à
 * la dernière appli qui avait une lecture (Spotify en pause, relevé du 29/10) : pendant `HOLD_MS` après la dernière
 * lecture vue, une autre appli en pause ou rien ne la remplacent pas. Une autre appli qui joue passe devant.
 */
export function heldSession(
  playing: { session: MediaSession; atMs: number } | undefined,
  fresh: MediaSession | undefined,
  nowMs: number,
): MediaSession | undefined {
  if (fresh?.status === "playing" || !playing || nowMs - playing.atMs > HOLD_MS) return fresh;
  return fresh && fresh.app === playing.session.app ? fresh : playing.session;
}

/** Image de la vidéo YouTube d'un onglet (aussi pour YouTube Music), connue par son adresse. */
export function youtubeThumb(url: string): string | undefined {
  const u = URL.parse(url);
  if (!u || !/(^|\.)youtube\.com$|^youtu\.be$/.test(u.host)) return undefined;
  const id = u.host === "youtu.be" ? u.pathname.slice(1) : (u.searchParams.get("v") ?? u.pathname.match(/^\/shorts\/([\w-]+)/)?.[1]);
  return id && /^[\w-]{6,20}$/.test(id) ? `https://i.ytimg.com/vi/${id}/hqdefault.jpg` : undefined;
}

/** Onglet YouTube de ce qui joue : titre de l'onglet = titre lu par MediaRemote. */
export function thumbOfTabs(tabs: { url: string; tabTitle: string }[], title: string): string | undefined {
  const wanted = title.toLowerCase();
  for (const t of tabs) {
    const site = siteName(t.url);
    const thumb = youtubeThumb(t.url);
    if (site && thumb && tabTitle(t.tabTitle, site).toLowerCase() === wanted) return thumb;
  }
  return undefined;
}

// --- Secours sans MediaRemote

/** Sites lus dans les onglets, et le nom montré sur la tablette. */
const SITES: [RegExp, string][] = [
  [/^music\.youtube\.com$/, "YouTube Music"],
  [/(^|\.)youtube\.com$|^youtu\.be$/, "YouTube"],
  [/^open\.spotify\.com$/, "Spotify"],
  [/(^|\.)deezer\.com$/, "Deezer"],
  [/(^|\.)soundcloud\.com$/, "SoundCloud"],
  [/(^|\.)twitch\.tv$/, "Twitch"],
  [/(^|\.)netflix\.com$/, "Netflix"],
  [/(^|\.)primevideo\.com$/, "Prime Video"],
  [/(^|\.)disneyplus\.com$/, "Disney+"],
];

export function siteName(url: string): string | undefined {
  const host = URL.parse(url)?.host.replace(/^www\.|^m\./, "") ?? "";
  return SITES.find(([re]) => re.test(host))?.[1];
}

/** Titre d'un onglet sans le compteur de notifications ni le nom du site : « (3) Clip - YouTube » → « Clip ». */
export function tabTitle(title: string, site: string): string {
  const bare = title.replace(/^\(\d+\+?\)\s*/, "").trim();
  const escaped = site.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const cleaned = bare.replace(new RegExp(`\\s*[-–|•·]\\s*${escaped}$`, "i"), "").trim();
  return cleaned.toLowerCase() === site.toLowerCase() ? "" : cleaned;
}

/** Spotify, Musique, puis les onglets des navigateurs ; `running()` ne lance pas l'appli. Firefox ne répond pas. */
const FALLBACK_SCRIPT = `
function run() {
  const out = [];
  function app(name) { try { const a = Application(name); return a.running() ? a : null; } catch (e) { return null; } }
  for (const name of ["Spotify", "Music"]) {
    const p = app(name);
    if (p) try {
      const t = p.currentTrack;
      out.push({ kind: "player", app: name, state: p.playerState(), title: t.name(), artist: t.artist(), album: t.album(),
        pos: p.playerPosition(), dur: name === "Spotify" ? t.duration() / 1000 : t.duration() });
    } catch (e) {}
  }
  for (const name of ["Google Chrome", "Safari", "Arc", "Microsoft Edge", "Brave Browser", "Vivaldi", "Opera"]) {
    const b = app(name);
    if (!b) continue;
    let wins = [];
    try { wins = b.windows(); } catch (e) {}
    for (const win of wins) try {
      const urls = win.tabs.url(), titles = name === "Safari" ? win.tabs.name() : win.tabs.title();
      let active = 0;
      try { active = name === "Safari" ? win.currentTab.index() : win.activeTabIndex(); } catch (e) {}
      urls.forEach((url, t) => out.push({ kind: "tab", app: name, url, tabTitle: titles[t] || "", active: t + 1 === active }));
    } catch (e) {}
  }
  return JSON.stringify(out);
}`;

type RawPlayer = { kind: "player"; app: string; state: string; title: string; artist: string; album: string; pos: number; dur: number };
type RawTab = { kind: "tab"; app: string; url: string; tabTitle: string; active?: boolean };

/** Programmes qui jouent du son (« Playing audio » dans les assertions d'énergie), en minuscules. */
export function playingApps(assertions: string): Set<string> {
  const names = new Set<string>();
  for (const m of assertions.matchAll(/pid \d+\(([^)]+)\):.*named: "Playing audio"/g)) names.add(m[1].toLowerCase());
  return names;
}

/** Identifiant d'un onglet de site sur la tablette (pastille, commandes, pochette) : un par site et par navigateur. */
const tabApp = (browser: string, site: string) => `${browser}|${site}`;

/** Secours : un lecteur ou un onglet de site connu par session ; un navigateur qui joue du son passe en lecture. */
export function fallbackSessions(raw: (RawPlayer | RawTab)[], playing: Set<string>): MediaSession[] {
  const sessions: MediaSession[] = [];
  for (const r of raw) {
    const base = { canPlayPause: true, canNext: true, canPrevious: true, current: false };
    if (r.kind === "player") {
      if (!r.title || r.state === "stopped") continue;
      const duration = ms(r.dur);
      sessions.push({
        ...base,
        app: r.app,
        name: r.app === "Music" ? "Musique" : r.app,
        title: r.title,
        artist: r.artist,
        album: r.album,
        hasThumb: !!r.artist,
        status: r.state === "playing" ? "playing" : "paused",
        canSeek: false,
        positionMs: ms(r.pos),
        durationMs: duration,
      });
      continue;
    }
    const site = siteName(r.url);
    const title = site ? tabTitle(r.tabTitle, site) : "";
    // Lecteur en pause (YouTube Music) : onglet sans titre ni morceau dans l'adresse, gardé (voir `mergeSessions`).
    if (!site || (!title && !PLAYER_SITES.has(site))) continue;
    const browser = r.app.toLowerCase();
    const sounding = [...playing].some((p) => browser.startsWith(p) || p.startsWith(browser.split(" ")[0]));
    const session: MediaSession = {
      ...base,
      app: tabApp(r.app, site),
      name: site,
      title,
      artist: "",
      album: "",
      hasThumb: !!youtubeThumb(r.url),
      status: sounding ? "playing" : "paused",
      canSeek: false,
      positionMs: null,
      durationMs: null,
    };
    // Un par site et par navigateur, celui qui a un titre de préférence.
    const same = sessions.findIndex((s) => s.app === session.app);
    if (same < 0) sessions.push(session);
    else if (title && !sessions[same].title) sessions[same] = session;
  }
  const current = sessions.find((s) => s.status === "playing") ?? sessions[0];
  if (current) current.current = true;
  return sessions;
}

/** Sites lecteurs : leur onglet reste une lecture en pause même sans titre. */
const PLAYER_SITES = new Set(["YouTube Music", "Spotify", "Deezer", "SoundCloud"]);

/** Dernier morceau vu par onglet lecteur (`app`), repris quand il est en pause sans titre. */
export type Recall = Map<string, { title: string; artist: string }>;

const sameTitle = (a: string, b: string) => {
  const [x, y] = [a.toLowerCase(), b.toLowerCase()];
  return !!x && !!y && (x.includes(y) || y.includes(x));
};

/**
 * Toutes les lectures du Mac, dans l'ordre du secours (Spotify, Musique, puis les onglets). Celle de MediaRemote prend
 * l'identifiant de sa jumelle du secours (même lecteur, onglet du même titre, sinon onglet lecteur sans titre du même
 * navigateur) : quand plusieurs jouent à la fois, MediaRemote passe de l'une à l'autre, et la pastille choisie sur la
 * tablette doit garder son identifiant et sa place. Sans jumelle, elle est en tête. Devant (`current`) : ce qui joue,
 * MediaRemote d'abord ; il peut rester figé sur une appli en pause pendant qu'un onglet joue (Spotify « unravel »
 * pendant YouTube Music dans Opera GX, relevé du 29/10). Les autres onglets du navigateur que MediaRemote voit jouer ne
 * jouent pas (`pmset` ne dit que le navigateur). Un onglet lecteur sans titre (YouTube Music en pause) reprend le
 * dernier morceau retenu dans `recall`, sinon le nom du site. `remote` : identifiant de la lecture de MediaRemote.
 */
export function mergeSessions(
  primary: MediaSession | undefined,
  others: MediaSession[],
  recall: Recall = new Map(),
): { sessions: MediaSession[]; remote?: string } {
  const browserOf = (o: MediaSession) => (o.app.includes("|") ? o.app.split("|")[0].toLowerCase() : "");
  // Navigateur de MediaRemote : `browser` quand son nom a été remplacé par le site (enrichBrowserSession).
  const ofPrimary = (o: MediaSession) => !!browserOf(o) && !!primary && extensionBrowser(primary).toLowerCase().startsWith(browserOf(o));
  const twin =
    primary &&
    (others.find((o) => PLAYERS[o.app] && PLAYERS[o.app] === PLAYERS[primary.app]) ??
      others.find((o) => sameTitle(o.title, primary.title)) ??
      others.find((o) => !o.title && ofPrimary(o)));
  const main = primary && (twin ? { ...primary, app: twin.app, name: twin.name } : primary);
  if (primary && twin && browserOf(twin)) recall.set(twin.app, { title: primary.title, artist: primary.artist });
  const all: MediaSession[] = main && !twin ? [main] : [];
  for (const o of others) {
    if (o === twin && main) {
      all.push(main);
      continue;
    }
    if (primary && sameTitle(o.title, primary.title)) continue;
    if (!o.title) {
      if (ofPrimary(o)) continue;
      const seen = recall.get(o.app);
      all.push({ ...o, title: seen?.title ?? o.name, artist: seen?.artist ?? "", hasThumb: o.hasThumb || !!seen?.artist });
      continue;
    }
    if (browserOf(o)) recall.set(o.app, { title: o.title, artist: o.artist });
    all.push(ofPrimary(o) && primary?.status === "playing" ? { ...o, status: "paused" } : o);
  }
  const front = (main?.status === "playing" ? main : undefined) ?? all.find((s) => s.status === "playing") ?? all[0];
  return { sessions: all.map((s) => ({ ...s, current: s === front })), remote: main?.app };
}

async function readPlayersAndTabs(): Promise<(RawPlayer | RawTab)[]> {
  return JSON.parse(await osascript(FALLBACK_SCRIPT, [], "JavaScript").catch(() => "[]"));
}

/** Titre que le navigateur donne à macOS quand la page ne décrit pas ce qu'elle joue (Chrome, en français ou en anglais). */
const GENERIC_TITLE = /un site est en train de lire|lit du contenu multimédia|a site is playing|is playing media/i;

/**
 * Session d'un navigateur au titre générique : titre de l'onglet et nom du site à la place, seulement pour un site de
 * lecture connu ([siteName]) de ce navigateur, l'onglet actif d'abord. Jamais un onglet quelconque : son titre (un
 * document, un intranet) s'afficherait sur la tablette posée sur le bureau. Rien de connu : inchangée.
 */
export function enrichBrowserSession(
  session: MediaSession,
  tabs: { app: string; url: string; tabTitle: string; active?: boolean }[],
): MediaSession {
  if (!GENERIC_TITLE.test(session.title)) return session;
  const mine = tabs.filter((t) => t.app === session.name || session.name.startsWith(t.app));
  // L'onglet actif d'abord : c'est le plus souvent celui qui joue.
  for (const t of [...mine.filter((t) => t.active), ...mine.filter((t) => !t.active)]) {
    const site = siteName(t.url);
    const title = site && tabTitle(t.tabTitle, site);
    if (site && title) return { ...session, name: site, title, browser: session.name };
  }
  return session;
}

/** Navigateur à qui l'extension doit envoyer les commandes : jamais le site affiché à la place de son nom. */
export function extensionBrowser(session: MediaSession): string {
  return session.browser ?? session.name;
}

// --- Interface du Mac pour os.ts

/** Lecteurs et onglets relus au plus toutes les 5 s, en arrière-plan (osascript, ~0,2 s) ; `pmset` (~10 ms) à chaque fois. */
const OTHERS_MS = 5_000;
let others: { raw: (RawPlayer | RawTab)[]; atMs: number } | undefined;
let othersPending: Promise<void> | undefined;

function refreshOthers(): Promise<void> {
  othersPending ??= readPlayersAndTabs()
    .then((raw) => void (others = { raw, atMs: Date.now() }))
    .finally(() => (othersPending = undefined));
  return othersPending;
}

async function playersAndTabs(): Promise<(RawPlayer | RawTab)[]> {
  if (!others) await refreshOthers();
  else if (Date.now() - others.atMs > OTHERS_MS) void refreshOthers();
  return others?.raw ?? [];
}

let pending: Promise<MediaSession[]> | undefined;
/** Dernières lectures envoyées à la tablette : commandes et pochettes visent l'une d'elles par son `app`. */
let known: MediaSession[] = [];
/** Appli de la lecture de MediaRemote (sa pochette vient de MediaRemote). */
let remoteApp: string | undefined;
/** Dernier morceau de chaque onglet lecteur (voir `mergeSessions`). */
const recall: Recall = new Map();
/** Dernière lecture que MediaRemote a vue jouer (voir `heldSession`). */
let lastPlaying: { session: MediaSession; atMs: number } | undefined;

/** Relu chaque seconde par la tablette : deux demandes simultanées partagent la même lecture. */
export function mediaSessions(): Promise<MediaSession[]> {
  pending ??= (async () => {
    const [raw, players, assertions] = await Promise.all([
      nowPlaying<RawNowPlaying>("get"),
      playersAndTabs(),
      run("pmset", ["-g", "assertions"]).catch(() => ""),
    ]);
    const now = Date.now();
    let fresh = raw && sessionOfNowPlaying(raw, now);
    // Navigateur sans description de ce qu'il joue : on lit ses onglets pour donner le vrai titre et le site.
    if (fresh && GENERIC_TITLE.test(fresh.title)) {
      fresh = enrichBrowserSession(
        fresh,
        players.filter((r): r is RawTab => r.kind === "tab"),
      );
    }
    if (fresh?.status === "playing") lastPlaying = { session: fresh, atMs: now };
    const session = heldSession(lastPlaying, fresh, now);
    const merged = mergeSessions(session, fallbackSessions(players, playingApps(assertions)), recall);
    known = merged.sessions;
    remoteApp = merged.remote;
    return known;
  })().finally(() => (pending = undefined));
  return pending;
}

/** Appli Spotify ou Musique (identifiant MediaRemote ou nom du secours) → son nom AppleScript. */
const PLAYERS: Record<string, string> = { "com.spotify.client": "Spotify", Spotify: "Spotify", "com.apple.Music": "Music", Music: "Music" };

/** Spotify ignore les commandes MediaRemote : Spotify et Musique sont pilotés par AppleScript. */
export function playerScript(app: string, action: MediaAction, positionMs?: number): string | undefined {
  const player = PLAYERS[app];
  if (!player) return undefined;
  const verb = {
    "play-pause": "playpause",
    next: "next track",
    previous: "previous track",
    seek: `set player position to ${Math.max(0, Math.round((positionMs ?? 0) / 1000))}`,
  }[action];
  return `tell application "${player}" to ${verb}`;
}

/**
 * Lecture choisie sur la tablette (`app`, sinon celle de devant). Spotify et Musique par AppleScript. Navigateur avec
 * l'extension « Mate » : elle agit dans la page (Opera GX en pause ne répond plus qu'à la vraie touche du
 * clavier). Sinon MediaRemote, qui vise sa propre lecture ; secours : touches multimédias. Un onglet sans extension :
 * refusé (MediaRemote ou la touche iraient à une autre appli).
 */
export async function mediaControl(action: MediaAction, app?: string, positionMs?: number): Promise<boolean> {
  // Nouvel état du lecteur ou de l'onglet relu sans attendre les 5 s.
  setTimeout(() => void refreshOthers(), 700);
  const target = known.find((s) => s.app === app) ?? known.find((s) => s.current);
  const script = target && playerScript(target.app, action, positionMs);
  if (script)
    return osascript(script).then(
      () => true,
      () => false,
    );
  // Navigateur : `name` pour MediaRemote (« Opera GX », ou `browser` quand `name` affiche le site), début de `app` pour
  // un onglet du secours (`name` = le site).
  if (target) {
    const command = { action, seconds: (positionMs ?? 0) / 1000, title: target.title };
    if ([extensionBrowser(target), target.app.split("|")[0]].some((b) => b && sendToExtension(b, command))) return true;
    if (target.app !== remoteApp) return false;
  }
  const arg = action === "seek" ? String((positionMs ?? 0) / 1000) : "";
  const res = await nowPlaying<{ ok?: boolean }>(action, arg);
  if (res?.ok) return true;
  if (action === "seek") return false;
  await sendMediaKey(action);
  return true;
}

/** Pochette d'un morceau dans la recherche publique d'Apple (sans clé) : titre + artiste. */
async function storeArtwork(title: string, artist: string): Promise<string | undefined> {
  const url = new URL("https://itunes.apple.com/search");
  url.search = new URLSearchParams({ term: `${title} ${artist}`, entity: "song", limit: "1" }).toString();
  const res = await fetchPublic(url, { maxBytes: 200_000, timeoutMs: 5000 }).catch(() => null);
  if (!res?.ok) return undefined;
  try {
    const art: unknown = JSON.parse(res.data.toString("utf8")).results?.[0]?.artworkUrl100;
    return typeof art === "string" ? art.replace(/\/\d+x\d+bb\./, "/600x600bb.") : undefined;
  } catch {
    return undefined;
  }
}

/**
 * Pochette d'une lecture (`app`, sinon celle de devant), écrite dans `out` : celle de MediaRemote pour sa lecture (les
 * navigateurs ne la donnent pas toujours), sinon l'image YouTube de l'onglet du même titre, sinon, pour un morceau
 * avec artiste, celle de la recherche d'Apple.
 */
export async function mediaThumb(app: string, out: string): Promise<boolean> {
  const session = known.find((s) => s.app === app) ?? known.find((s) => s.current);
  if (!session) return false;
  if (session.app === remoteApp && (await nowPlaying<{ ok?: boolean }>("art", out))?.ok === true) return true;
  const tabs = (await playersAndTabs()).filter((r): r is RawTab => r.kind === "tab");
  const url = thumbOfTabs(tabs, session.title) ?? (session.artist ? await storeArtwork(session.title, session.artist) : undefined);
  if (!url) return false;
  const res = await fetchPublic(url, { maxBytes: 2_000_000, timeoutMs: 5000 }).catch(() => null);
  if (!res?.ok || res.truncated) return false;
  await fs.writeFile(out, res.data);
  return true;
}
