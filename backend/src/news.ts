import path from "node:path";
import { config } from "./config.ts";
import { fetchPublic } from "./net-guard.ts";
import { readJson, removeJson, writeJson } from "./store.ts";

/** Tuile Actualités : les derniers titres d'un flux RSS ou Atom, réglé sur l'ordinateur (news.json), lu par le compagnon. */

export type NewsItem = { title: string; link: string };
export type News = { feed: string | null; title: string | null; items: NewsItem[] };

export const MAX_ITEMS = 5;
const MAX_FEED_BYTES = 1_000_000;
const MAX_TITLE = 200;
const storeFile = path.join(config.dataDir, "news.json");

/** Adresse du flux : http(s) sans identifiant (un mot de passe n'a rien à faire dans un réglage lisible). */
function cleanFeedUrl(input: string): string {
  let url: URL;
  try {
    url = new URL(input.trim());
  } catch {
    throw new Error("Adresse du flux non valide");
  }
  if ((url.protocol !== "http:" && url.protocol !== "https:") || url.username || url.password)
    throw new Error("Adresse du flux non valide (https://… sans identifiant)");
  return url.href;
}

export const getNewsFeed = async () => (await readJson<{ feed?: string }>(storeFile, () => ({}))).feed ?? null;

/** Règle le flux ; `null` le retire. Erreur si l'adresse n'est pas acceptable. */
export async function setNewsFeed(input: string | null) {
  const feed = input === null ? null : cleanFeedUrl(input);
  shown = [];
  await (feed === null ? removeJson(storeFile) : writeJson(storeFile, { feed }));
}

const ENTITIES: Record<string, string> = {
  amp: "&",
  lt: "<",
  gt: ">",
  quot: '"',
  apos: "'",
  nbsp: " ",
  egrave: "è",
  eacute: "é",
  agrave: "à",
};

const decode = (text: string) =>
  text
    .replace(/&#x([0-9a-f]+);/gi, (m, h) => safeChar(parseInt(h, 16), m))
    .replace(/&#(\d+);/g, (m, d) => safeChar(Number(d), m))
    .replace(/&([a-z]+);/gi, (m, name) => ENTITIES[name.toLowerCase()] ?? m);

const safeChar = (code: number, fallback: string) => (code > 0 && code <= 0x10ffff ? String.fromCodePoint(code) : fallback);

const MAX_ENTRIES_SCANNED = 50;
const MAX_ENTRY_CHARS = 20_000;
const SPACE_OR_END = " \t\r\n>/";

/** Balises retirées d'un coup de balayage (pas de regex : un flux piégé de « < » sans « > » ne doit pas ralentir le compagnon). */
function stripTags(text: string) {
  let out = "";
  let at = 0;
  for (;;) {
    const open = text.indexOf("<", at);
    if (open < 0) return out + text.slice(at);
    out += text.slice(at, open);
    const close = text.indexOf(">", open);
    if (close < 0) return out;
    at = close + 1;
  }
}

/** Texte d'un élément : CDATA ouvert, balises HTML retirées, entités décodées, espaces repliés. */
function plain(raw: string) {
  // CDATA : texte brut, éventuellement du HTML ; ses « & » ne sont pas des entités.
  const text = raw.replace(/<!\[CDATA\[([\s\S]*?)\]\]>/g, (_m, inner) => stripTags(inner).replace(/&/g, "&amp;"));
  return decode(stripTags(text)).replace(/\s+/g, " ").trim();
}

/** Position de la prochaine balise ouvrante `<tag` (et non `<tagada`) à partir de `from` dans `low` (minuscules), ou -1. */
function findOpen(low: string, tag: string, from = 0): number {
  for (let at = low.indexOf(`<${tag}`, from); at >= 0; at = low.indexOf(`<${tag}`, at + 1))
    if (SPACE_OR_END.includes(low[at + tag.length + 1] ?? ">")) return at;
  return -1;
}

/** Contenu du premier `<tag>…</tag>` ; rien si la balise est auto-fermante ou jamais refermée. */
function element(xml: string, tag: string, low = xml.toLowerCase()) {
  const open = findOpen(low, tag);
  const gt = open < 0 ? -1 : low.indexOf(">", open);
  if (gt < 0 || low[gt - 1] === "/") return undefined;
  const close = low.indexOf(`</${tag}>`, gt);
  return close < 0 ? undefined : xml.slice(gt + 1, close);
}

/** Lien d'une entrée : `<link>…</link>` (RSS) ou `<link href>` (Atom, `alternate` ou sans `rel`). */
function linkOf(entry: string): string | null {
  const low = entry.toLowerCase();
  const text = element(entry, "link", low);
  if (text !== undefined && plain(text)) return plain(text);
  for (let at = findOpen(low, "link"); at >= 0; at = findOpen(low, "link", at + 1)) {
    const end = low.indexOf(">", at);
    if (end < 0) break;
    const tag = entry.slice(at, end);
    const rel = tag.match(/\srel=["']([^"']*)["']/i)?.[1];
    const href = tag.match(/\shref=["']([^"']*)["']/i)?.[1];
    if (href && (!rel || rel === "alternate")) return decode(href).trim();
  }
  return null;
}

const isWebLink = (link: string) => /^https?:\/\//i.test(link);

/** Début de la prochaine entrée (`<item` RSS ou `<entry` Atom) à partir de `from`, ou -1. */
function nextEntry(low: string, from: number) {
  const starts = [findOpen(low, "item", from), findOpen(low, "entry", from)].filter((at) => at >= 0);
  return starts.length ? Math.min(...starts) : -1;
}

/** Les premiers articles d'un flux RSS ou Atom ; ceux sans titre ou sans lien http(s) sont ignorés. Un seul balayage, sans regex à retour arrière. */
export function parseFeed(xml: string): { title: string | null; items: NewsItem[] } {
  if (!/<(rss|feed|rdf:RDF)[\s>]/i.test(xml)) throw new Error("Ce n'est pas un flux RSS ou Atom");
  const low = xml.toLowerCase();
  let at = nextEntry(low, 0);
  const title = element(at < 0 ? xml : xml.slice(0, at), "title");
  const items: NewsItem[] = [];
  for (let scanned = 0; at >= 0 && scanned < MAX_ENTRIES_SCANNED && items.length < MAX_ITEMS; scanned++) {
    const name = low.startsWith("<item", at) ? "item" : "entry";
    const close = low.indexOf(`</${name}>`, at);
    if (close < 0) break;
    const entry = xml.slice(at, Math.min(close, at + MAX_ENTRY_CHARS));
    const entryTitle = plain(element(entry, "title") ?? "").slice(0, MAX_TITLE);
    const link = linkOf(entry);
    if (entryTitle && link && isWebLink(link)) items.push({ title: entryTitle, link });
    at = nextEntry(low, close);
  }
  return { title: title ? plain(title) || null : null, items };
}

type Fetcher = (url: string) => Promise<Buffer>;

const download: Fetcher = async (url) => {
  const res = await fetchPublic(url, { maxBytes: MAX_FEED_BYTES });
  if (!res.ok) throw new Error("Le flux ne répond pas");
  return res.data;
};

/** Derniers articles montrés à la tablette : un toucher ouvre celui-là seulement (jamais une adresse venue de la tablette). */
let shown: NewsItem[] = [];

export async function loadNews(get: Fetcher = download): Promise<News> {
  const feed = await getNewsFeed();
  // Un échec ou l'absence de flux ne laisse pas ouvrir les liens de la liste d'avant.
  shown = [];
  if (!feed) return { feed: null, title: null, items: [] };
  const parsed = parseFeed((await get(feed)).toString("utf8"));
  shown = parsed.items;
  return { feed, ...parsed };
}

/** Adresse de l'article `index` de la dernière liste montrée, ou null. */
export const shownLink = (index: number) => shown[index]?.link ?? null;
