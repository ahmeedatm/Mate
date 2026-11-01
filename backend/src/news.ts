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
  if (input === null) return removeJson(storeFile);
  await writeJson(storeFile, { feed: cleanFeedUrl(input) });
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

/** Texte d'un élément : CDATA ouvert, balises HTML retirées, entités décodées, espaces repliés. */
function plain(raw: string) {
  // CDATA : texte brut, éventuellement du HTML ; ses « & » ne sont pas des entités.
  const text = raw.replace(/<!\[CDATA\[([\s\S]*?)\]\]>/g, (_m, inner) => inner.replace(/<[^>]*>/g, "").replace(/&/g, "&amp;"));
  return decode(text.replace(/<[^>]*>/g, ""))
    .replace(/\s+/g, " ")
    .trim();
}

const element = (xml: string, tag: string) => xml.match(new RegExp(`<${tag}(?:\\s[^>]*)?>([\\s\\S]*?)</${tag}>`, "i"))?.[1];

/** Lien d'une entrée : `<link>…</link>` (RSS) ou `<link href>` (Atom, `alternate` ou sans `rel`). */
function linkOf(entry: string): string | null {
  const text = element(entry, "link");
  if (text !== undefined && plain(text)) return plain(text);
  for (const tag of entry.match(/<link\s[^>]*>/gi) ?? []) {
    const rel = tag.match(/\srel=["']([^"']*)["']/i)?.[1];
    const href = tag.match(/\shref=["']([^"']*)["']/i)?.[1];
    if (href && (!rel || rel === "alternate")) return decode(href).trim();
  }
  return null;
}

const isWebLink = (link: string) => /^https?:\/\//i.test(link);

/** Les premiers articles d'un flux RSS ou Atom ; ceux sans titre ou sans lien http(s) sont ignorés. */
export function parseFeed(xml: string): { title: string | null; items: NewsItem[] } {
  if (!/<(rss|feed|rdf:RDF)[\s>]/i.test(xml)) throw new Error("Ce n'est pas un flux RSS ou Atom");
  const body = xml.replace(/<(item|entry)[\s>][\s\S]*$/i, "");
  const title = element(body, "title");
  const items: NewsItem[] = [];
  for (const m of xml.matchAll(/<(item|entry)(?:\s[^>]*)?>([\s\S]*?)<\/\1>/gi)) {
    const entryTitle = plain(element(m[2], "title") ?? "").slice(0, MAX_TITLE);
    const link = linkOf(m[2]);
    if (entryTitle && link && isWebLink(link)) items.push({ title: entryTitle, link });
    if (items.length === MAX_ITEMS) break;
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
  if (!feed) return ((shown = []), { feed: null, title: null, items: [] });
  const parsed = parseFeed((await get(feed)).toString("utf8"));
  shown = parsed.items;
  return { feed, ...parsed };
}

/** Adresse de l'article `index` de la dernière liste montrée, ou null. */
export const shownLink = (index: number) => shown[index]?.link ?? null;
