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
 * /usr/bin/perl (src/mac/nowplaying/NowPlaying.m). Si MediaRemote ne dit rien (fermé par une mise à jour de macOS),
 * secours : Spotify et Musique par AppleScript, titre des onglets des sites connus, « joue » lu dans les assertions
 * d'énergie (`pmset`), commandes par les touches multimédias.
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

/** Secours : un lecteur ou un onglet de site connu par session ; un navigateur qui joue du son passe en lecture. */
export function fallbackSessions(raw: (RawPlayer | RawTab)[], playing: Set<string>): MediaSession[] {
  const sessions: MediaSession[] = [];
  for (const r of raw) {
    const base = { canPlayPause: true, canNext: true, canPrevious: true, hasThumb: false, current: false };
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
        status: r.state === "playing" ? "playing" : "paused",
        canSeek: false,
        positionMs: ms(r.pos),
        durationMs: duration,
      });
      continue;
    }
    const site = siteName(r.url);
    const title = site && tabTitle(r.tabTitle, site);
    if (!site || !title) continue;
    const browser = r.app.toLowerCase();
    const sounding = [...playing].some((p) => browser.startsWith(p) || p.startsWith(browser.split(" ")[0]));
    sessions.push({
      ...base,
      app: r.app,
      name: site,
      title,
      artist: "",
      album: "",
      status: sounding ? "playing" : "paused",
      canSeek: false,
      positionMs: null,
      durationMs: null,
    });
  }
  const current = sessions.find((s) => s.status === "playing") ?? sessions[0];
  if (current) current.current = true;
  return sessions;
}

async function readPlayersAndTabs(): Promise<(RawPlayer | RawTab)[]> {
  return JSON.parse(await osascript(FALLBACK_SCRIPT, [], "JavaScript").catch(() => "[]"));
}

async function fallback(): Promise<MediaSession[]> {
  const [raw, assertions] = await Promise.all([readPlayersAndTabs(), run("pmset", ["-g", "assertions"]).catch(() => "")]);
  return fallbackSessions(raw, playingApps(assertions));
}

/** Titre que le navigateur donne à macOS quand la page ne décrit pas ce qu'elle joue (Chrome, en français ou en anglais). */
const GENERIC_TITLE = /un site est en train de lire|lit du contenu multimédia|a site is playing|is playing media/i;

/**
 * Session d'un navigateur au titre générique : titre de l'onglet et nom du site à la place. D'abord un onglet d'un site
 * connu ([siteName]) de ce navigateur, sinon l'onglet actif d'une de ses fenêtres (nom = domaine). Rien de lisible : inchangée.
 */
export function enrichBrowserSession(
  session: MediaSession,
  tabs: { app: string; url: string; tabTitle: string; active?: boolean }[],
): MediaSession {
  if (!GENERIC_TITLE.test(session.title)) return session;
  const mine = tabs.filter((t) => t.app === session.name || session.name.startsWith(t.app));
  for (const t of mine) {
    const site = siteName(t.url);
    const title = site && tabTitle(t.tabTitle, site);
    if (site && title) return { ...session, name: site, title };
  }
  const active = mine.find((t) => t.active && t.tabTitle.trim());
  const host = active && URL.parse(active.url)?.host.replace(/^www\.|^m\./, "");
  return active && host ? { ...session, name: host, title: active.tabTitle.trim() } : session;
}

// --- Interface du Mac pour os.ts

let pending: Promise<MediaSession[]> | undefined;
/** Dernière lecture vue, pour la pochette (`/system/media/thumb` ne donne que l'appli). */
let last: MediaSession | undefined;
/** Dernière lecture que MediaRemote a vue jouer (voir `heldSession`). */
let lastPlaying: { session: MediaSession; atMs: number } | undefined;

/** Relu chaque seconde par la tablette : deux demandes simultanées partagent la même lecture. */
export function mediaSessions(): Promise<MediaSession[]> {
  pending ??= (async () => {
    const raw = await nowPlaying<RawNowPlaying>("get");
    const now = Date.now();
    let fresh = raw && sessionOfNowPlaying(raw, now);
    // Navigateur sans description de ce qu'il joue : on lit ses onglets pour donner le vrai titre et le site.
    if (fresh && GENERIC_TITLE.test(fresh.title)) {
      const tabs = (await readPlayersAndTabs()).filter((r): r is RawTab => r.kind === "tab");
      fresh = enrichBrowserSession(fresh, tabs);
    }
    if (fresh?.status === "playing") lastPlaying = { session: fresh, atMs: now };
    const session = heldSession(lastPlaying, fresh, now);
    const sessions = session ? [session] : await fallback();
    last = sessions.find((s) => s.current);
    return sessions;
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
 * Spotify et Musique par AppleScript. Navigateur qui joue avec l'extension « Compagnon Unowhy » : elle agit dans la page (Opera GX en pause ne répond plus
 * qu'à la vraie touche du clavier). Sinon MediaRemote, qui vise ce qui joue (`app` ignoré : une seule lecture en
 * cours sur Mac) ; secours : touches multimédias.
 */
export async function mediaControl(action: MediaAction, _app?: string, positionMs?: number): Promise<boolean> {
  const playing = last;
  const script = playing && playerScript(playing.app, action, positionMs);
  if (script)
    return osascript(script).then(
      () => true,
      () => false,
    );
  if (playing?.name && sendToExtension(playing.name, { action, seconds: (positionMs ?? 0) / 1000, title: playing.title })) return true;
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
 * Pochette de ce qui joue, écrite dans `out` : celle de MediaRemote (les navigateurs ne la donnent pas toujours),
 * sinon l'image YouTube de l'onglet qui joue, sinon, pour un morceau avec artiste, celle de la recherche d'Apple.
 */
export async function mediaThumb(_app: string, out: string): Promise<boolean> {
  if ((await nowPlaying<{ ok?: boolean }>("art", out))?.ok === true) return true;
  const playing = last;
  if (!playing) return false;
  const tabs = (await readPlayersAndTabs()).filter((r): r is RawTab => r.kind === "tab");
  const url = thumbOfTabs(tabs, playing.title) ?? (playing.artist ? await storeArtwork(playing.title, playing.artist) : undefined);
  if (!url) return false;
  const res = await fetchPublic(url, { maxBytes: 2_000_000, timeoutMs: 5000 }).catch(() => null);
  if (!res?.ok || res.truncated) return false;
  await fs.writeFile(out, res.data);
  return true;
}
