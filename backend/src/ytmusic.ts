import { extensionTabs, sendToExtension } from "./browser-extension.ts";

/**
 * YouTube Music piloté depuis la tablette : l'onglet tourne dans le navigateur de l'ordinateur (le son sort de
 * l'ordinateur), l'extension (backend/extension/ytmusic.js) lit la page et pousse son état ici, la tablette le relit
 * et n'envoie qu'une action de la liste fermée `YT_MUSIC_OPS`, jamais de script.
 */

export type YtMusicTrack = {
  title: string;
  artist: string;
  album: string;
  cover: string | null;
  /** Secondes ; 0 si le lecteur ne les donne pas encore. */
  position: number;
  duration: number;
  like: "like" | "dislike" | "none";
};

export type YtMusicItem = { title: string; subtitle: string; cover: string | null };

export type YtMusicState = {
  playing: boolean;
  track: YtMusicTrack | null;
  repeat: "none" | "all" | "one" | null;
  /** File d'attente, `current` = le titre joué. */
  queue: (YtMusicItem & { current: boolean })[];
  /** Résultats de la dernière recherche, tant que l'onglet est sur la page de recherche. */
  query: string | null;
  results: YtMusicItem[];
};

export type YtMusicReport = { browser: string; state: YtMusicState };

export const YT_MUSIC_OPS = [
  "play-pause",
  "next",
  "previous",
  "seek",
  "like",
  "dislike",
  "shuffle",
  "repeat",
  "play-queue",
  "search",
  "play-result",
] as const;
export type YtMusicOp = (typeof YT_MUSIC_OPS)[number];
export type YtMusicAction = { op: YtMusicOp; index?: number; query?: string; seconds?: number };

/** L'extension renvoie l'état à chaque changement et toutes les 5 s : au-delà, l'onglet est fermé. */
const STALE_MS = 30_000;
export const MAX_ITEMS = 50;
const reports = new Map<string, YtMusicReport & { at: number }>();

/** Pochettes servies par Google seulement (la tablette les charge elle-même) ; en grand plutôt qu'en vignette. */
function cover(raw: string | null | undefined): string | null {
  const url = URL.parse(raw ?? "");
  if (!url || url.protocol !== "https:" || !/(^|\.)(googleusercontent\.com|ytimg\.com|ggpht\.com)$/i.test(url.hostname)) return null;
  return url.href.replace(/=w\d+-h\d+/, "=w544-h544");
}

const text = (s: string | undefined, max = 200) => (s ?? "").replace(/\s+/g, " ").trim().slice(0, max);
const item = (i: YtMusicItem): YtMusicItem => ({ title: text(i.title), subtitle: text(i.subtitle), cover: cover(i.cover) });

export function receiveYtMusic(report: YtMusicReport) {
  const s = report.state;
  const track = s.track && {
    title: text(s.track.title),
    artist: text(s.track.artist),
    album: text(s.track.album),
    cover: cover(s.track.cover),
    position: Math.max(0, s.track.position || 0),
    duration: Math.max(0, s.track.duration || 0),
    like: s.track.like,
  };
  reports.set(report.browser, {
    browser: report.browser,
    at: Date.now(),
    state: {
      playing: s.playing,
      track: track && track.title ? track : null,
      repeat: s.repeat,
      queue: s.queue.slice(0, MAX_ITEMS).map((q) => ({ ...item(q), current: q.current })),
      query: s.query === null ? null : text(s.query),
      results: s.results.slice(0, MAX_ITEMS).map(item),
    },
  });
}

/** Onglet YouTube Music le plus récent (un par navigateur), ou undefined s'il n'y en a plus. */
export function ytMusic(): (YtMusicReport & { at: number }) | undefined {
  const fresh = [...reports.values()].filter((r) => Date.now() - r.at < STALE_MS);
  // Celui qui joue d'abord, sinon le dernier qui a parlé.
  return fresh.sort((a, b) => Number(b.state.playing) - Number(a.state.playing) || b.at - a.at)[0];
}

/**
 * Action vers l'extension du navigateur de l'onglet ; false si elle n'écoute pas. Sans onglet, seule la recherche
 * part : vers le navigateur passé devant le plus récemment, qui ouvre YouTube Music sur les résultats.
 */
export function sendYtMusic(action: YtMusicAction): boolean {
  const browser = ytMusic()?.browser ?? (action.op === "search" ? extensionTabs()?.[0]?.browser : undefined);
  if (!browser) return false;
  return sendToExtension(browser, { action: "ytmusic", ...action });
}

/** Pour les tests : oublie tout. */
export function resetYtMusic() {
  reports.clear();
}
