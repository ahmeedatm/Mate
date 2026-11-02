/**
 * Paroles du titre joué par YouTube Music, cherchées par l'ordinateur dans LRCLIB (lrclib.net : base libre, sans clé
 * ni compte). La tablette ne demande que « les paroles du titre en cours » : ni titre ni adresse ne viennent d'elle.
 * Synchronisées (format LRC, une heure par ligne) quand LRCLIB les a, sinon le texte seul.
 */

export type LyricLine = { t: number | null; text: string };
export type Lyrics = { synced: boolean; instrumental: boolean; lines: LyricLine[] };
type Track = { title: string; artist: string; album: string; duration: number };
type LrclibTrack = { duration?: number; instrumental?: boolean; plainLyrics?: string | null; syncedLyrics?: string | null };

const BASE = (process.env.LYRICS_URL ?? "https://lrclib.net/api").replace(/\/$/, "");
// LRCLIB demande de se présenter.
const HEADERS = { "user-agent": "Mate (github.com/ahmeedatm/Mate)" };
/** Écart de durée accepté entre le titre joué et une réponse de la recherche libre (s). */
const DURATION_SLACK = 3;
const CACHE_MAX = 200;
const RETRY_MS = Number(process.env.LYRICS_RETRY_MS ?? 1_000);
const cache = new Map<string, Lyrics | null>();

/** « [01:02.50]Texte » → une ligne par heure (une ligne LRC peut en porter plusieurs), triées. */
export function parseLrc(lrc: string): LyricLine[] {
  const lines: LyricLine[] = [];
  for (const raw of lrc.split(/\r?\n/)) {
    const stamps = [...raw.matchAll(/\[(\d+):(\d{1,2}(?:[.:]\d+)?)\]/g)];
    if (stamps.length === 0) continue;
    const text = raw.replace(/\[[^\]]*\]/g, "").trim();
    for (const s of stamps) lines.push({ t: Number(s[1]) * 60 + Number(s[2].replace(":", ".")), text });
  }
  return lines.sort((a, b) => (a.t ?? 0) - (b.t ?? 0));
}

function toLyrics(found: LrclibTrack): Lyrics {
  if (found.instrumental) return { synced: false, instrumental: true, lines: [] };
  if (found.syncedLyrics) return { synced: true, instrumental: false, lines: parseLrc(found.syncedLyrics) };
  const text = (found.plainLyrics ?? "").split(/\r?\n/).map((line) => ({ t: null, text: line.trim() }));
  return { synced: false, instrumental: false, lines: text };
}

/** Réponse de LRCLIB, `undefined` si 404 ; un 503 (LRCLIB surchargé, fréquent) est réessayé une fois. */
async function lrclib(route: string, retry = true): Promise<unknown | undefined> {
  const res = await fetch(BASE + route, { headers: HEADERS, signal: AbortSignal.timeout(8_000) });
  if (res.status === 404) return undefined;
  if (res.status === 503 && retry) {
    await new Promise((resolve) => setTimeout(resolve, RETRY_MS));
    return lrclib(route, false);
  }
  if (!res.ok) throw Object.assign(new Error(`LRCLIB ${res.status}`), { technical: true });
  return res.json();
}

/** Artiste principal d'un titre à plusieurs (« Orelsan & Yamê », « A feat. B », « A, B ») : LRCLIB n'a souvent que lui. */
export const mainArtist = (artist: string) => artist.split(/\s*(?:&|,|\bfeat\.?|\bft\.?|\bx\b)\s*/i)[0].trim();

/**
 * Paroles d'un titre : correspondance exacte (avec puis sans l'album, puis avec l'artiste principal seul), puis
 * recherche libre à durée égale ; null si rien. Un essai en erreur n'arrête pas les suivants : l'erreur ne remonte que
 * si aucun n'a répondu.
 */
export async function lyricsFor(track: Track): Promise<Lyrics | null> {
  const key = [track.artist, track.title, Math.round(track.duration)].join("|").toLowerCase();
  if (cache.has(key)) return cache.get(key) ?? null;
  const q = (params: Record<string, string>) => new URLSearchParams(params).toString();
  const duration = String(Math.round(track.duration));
  const main = mainArtist(track.artist);
  const exact: Record<string, string>[] = [
    ...(track.album ? [{ artist_name: track.artist, track_name: track.title, album_name: track.album, duration }] : []),
    { artist_name: track.artist, track_name: track.title, duration },
    ...(main !== track.artist ? [{ artist_name: main, track_name: track.title, duration }] : []),
  ];
  let answered = false;
  let failure: unknown;
  let found: LrclibTrack | undefined;
  for (const params of exact) {
    try {
      found = (await lrclib(`/get?${q(params)}`)) as LrclibTrack | undefined;
      answered = true;
      if (found) break;
    } catch (e) {
      failure = e;
    }
  }
  if (!found) {
    try {
      const results = ((await lrclib(`/search?${q({ q: `${main} ${track.title}` })}`)) ?? []) as LrclibTrack[];
      answered = true;
      const close = results.filter((r) => !track.duration || Math.abs((r.duration ?? 0) - track.duration) <= DURATION_SLACK);
      found = close.find((r) => r.syncedLyrics) ?? close.find((r) => r.plainLyrics || r.instrumental);
    } catch (e) {
      failure = e;
    }
  }
  // Rien n'a répondu (LRCLIB en panne) : pas de cache, la tablette réessaiera.
  if (!found && !answered) throw failure;
  const lyrics = found ? toLyrics(found) : null;
  // « Rien trouvé » gardé seulement si tous les essais ont répondu (une recherche en erreur pourra réussir plus tard).
  if (lyrics || !failure) {
    if (cache.size >= CACHE_MAX) cache.delete(cache.keys().next().value!);
    cache.set(key, lyrics);
  }
  return lyrics;
}
