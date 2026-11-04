import fs from "node:fs/promises";
import path from "node:path";
import { config } from "./config.ts";
import { EXECUTABLE } from "./open-guard.ts";

export type DocKind = "dir" | "pdf" | "image" | "text" | "other";

const KINDS: Record<string, DocKind> = {
  ".pdf": "pdf",
  ".png": "image",
  ".jpg": "image",
  ".jpeg": "image",
  ".gif": "image",
  ".webp": "image",
  ".txt": "text",
  ".md": "text",
  ".csv": "text",
  ".json": "text",
  ".log": "text",
};

export const PHOTOS_DIR = "Photos tablette";

/** Résout un chemin relatif au dossier partagé, en refusant toute sortie de ce dossier. */
export function resolveDoc(relative: string): string {
  const full = path.resolve(config.docsDir, "." + path.sep + relative);
  if (full !== config.docsDir && !full.startsWith(config.docsDir + path.sep)) {
    throw Object.assign(new Error("Chemin hors du dossier partagé"), { statusCode: 400 });
  }
  return full;
}

export function kindOf(name: string): DocKind {
  return KINDS[path.extname(name).toLowerCase()] ?? "other";
}

export async function listDocs(dir: string) {
  await fs.mkdir(config.docsDir, { recursive: true });
  const full = resolveDoc(dir);
  const entries = await fs.readdir(full, { withFileTypes: true });
  const items = await Promise.all(
    entries
      .filter((e) => !e.name.startsWith("."))
      .map(async (e) => {
        const stat = await fs.stat(path.join(full, e.name));
        return {
          name: e.name,
          path: path.relative(config.docsDir, path.join(full, e.name)),
          kind: e.isDirectory() ? ("dir" as const) : kindOf(e.name),
          size: stat.size,
          mtimeMs: Math.round(stat.mtimeMs),
        };
      }),
  );
  // Dossiers d'abord, puis fichiers du plus récent au plus ancien : un fichier
  // déposé depuis le Mac apparaît en tête de liste sur la tablette.
  items.sort((a, b) => Number(b.kind === "dir") - Number(a.kind === "dir") || b.mtimeMs - a.mtimeMs);
  return { dir: path.relative(config.docsDir, full), root: config.docsDir, entries: items };
}

/** Fiche d'un fichier du dossier partagé (même format que les entrées de listDocs), ou undefined. */
export async function docEntry(relative: string) {
  const full = resolveDoc(relative);
  const stat = await fs.stat(full).catch(() => undefined);
  if (!stat?.isFile()) return undefined;
  return {
    name: path.basename(full),
    path: path.relative(config.docsDir, full),
    kind: kindOf(full),
    size: stat.size,
    mtimeMs: Math.round(stat.mtimeMs),
  };
}

function stamp() {
  const d = new Date();
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}_${pad(d.getHours())}-${pad(d.getMinutes())}-${pad(d.getSeconds())}`;
}

export async function savePhoto(data: Buffer, extension = ".jpg") {
  const dir = path.join(config.docsDir, PHOTOS_DIR);
  await fs.mkdir(dir, { recursive: true });
  const name = `photo-${stamp()}${extension}`;
  await fs.writeFile(path.join(dir, name), data);
  return path.join(PHOTOS_DIR, name);
}

const RESERVED_NAME = /^(con|prn|aux|nul|com[1-9]|lpt[1-9])$/i;
/** Assez court pour qu'un « (n) » de plus tienne dans les 255 octets d'un nom de fichier. */
const NAME_MAX = 120;
/** Caractères de direction (U+202A–U+202E, U+2066–U+2069) et invisibles (U+200B–U+200F) : un RLO ferait lire le nom à l'envers (« exe » déguisé en « jpg »). */
const INVISIBLE = /[\u200B-\u200F\u202A-\u202E\u2066-\u2069]/g;
/** Seuls types qu'on dépose : rien qui s'exécute ou s'ouvre en lançant du code (exe, rdp, docm, html, lnk…). */
const SHARE_EXTENSIONS = new Set(
  ".pdf .jpg .jpeg .png .gif .webp .heic .txt .md .csv .json .log .docx .xlsx .pptx .odt .ods .odp .zip .mp3 .m4a .mp4 .mov".split(" "),
);

/** Dossiers que le système lance comme un programme (en plus de [EXECUTABLE] : `.app`, `.workflow`…). */
const BUNDLE_DIR = new Set([...EXECUTABLE, ".bundle", ".prefpane", ".saver", ".appex", ".xpc", ".kext", ".framework", ".plugin"]);

function refuseOpen(message: string): never {
  throw Object.assign(new Error(message), { statusCode: 400 });
}

/**
 * Garde de `/docs/open`. `open` suit les liens symboliques : c'est le chemin réel qui est jugé.
 * - il reste dans le dossier partagé (un lien `sh.pdf` vers `/bin/sh` ou `Apps` vers `/Applications` sort) ;
 * - un dossier est accepté, sauf un bundle lancé comme un programme (`.app`…) ; un nom à point (`v1.2`) passe ;
 * - un fichier doit être d'un type de [SHARE_EXTENSIONS] (liste blanche, comme le partage), par son nom comme par sa cible :
 *   une liste noire laisserait passer `.rdp`, `.docm`, `.html`, `.iso`, `.webloc`… ; un fichier sans extension (un
 *   exécutable que `open` lancerait) est refusé aussi.
 */
export async function checkOpenableDoc(full: string) {
  const notSupported = () => refuseOpen("Ouverture refusée : ce type de fichier n'est pas pris en charge");
  const ext = path.extname(full).toLowerCase();
  const real = await fs.realpath(full).catch(() => undefined);
  if (!real) {
    // Introuvable : un type refusé reste refusé (400), sinon la route répond 404 comme /docs/file.
    if (!SHARE_EXTENSIONS.has(ext)) notSupported();
    throw Object.assign(new Error("Fichier introuvable"), { statusCode: 404 });
  }
  const root = await fs.realpath(config.docsDir).catch(() => config.docsDir);
  if (real !== root && !real.startsWith(root + path.sep)) refuseOpen("Chemin hors du dossier partagé");
  const realExt = path.extname(real).toLowerCase();
  if ((await fs.stat(real)).isDirectory()) {
    if (BUNDLE_DIR.has(ext) || BUNDLE_DIR.has(realExt)) notSupported();
    return;
  }
  if (!SHARE_EXTENSIONS.has(ext) || !SHARE_EXTENSIONS.has(realExt)) notSupported();
}

/**
 * Nom sûr sur Mac comme sur Windows : sans dossier, sans caractère interdit, de contrôle, de direction ou invisible
 * (un « : » ouvrirait un flux NTFS), sans point de tête (le fichier serait caché de la liste) ni point ou espace
 * final, sans nom réservé (CON, NUL…) avant le premier point (`con.x.txt` l'est aussi), au plus [NAME_MAX]
 * caractères, extension gardée. Refusé (415) si l'extension n'est pas dans [SHARE_EXTENSIONS].
 */
function shareName(filename: string) {
  const base = path.basename(filename.replace(INVISIBLE, "").replace(/\\/g, "/"));
  const clean = base
    .replace(/[<>:"|?*\x00-\x1f]/g, "_")
    .replace(/^\.+/, "")
    .replace(/[. ]+$/, "")
    .trim();
  const ext = path.extname(clean).slice(0, 20);
  if (!SHARE_EXTENSIONS.has(ext.toLowerCase())) {
    throw Object.assign(new Error("Type de fichier non accepté"), { statusCode: 415 });
  }
  const name = clean.slice(0, clean.length - ext.length).slice(0, NAME_MAX - ext.length) + ext;
  return RESERVED_NAME.test(name.split(".")[0].trimEnd()) ? `_${name}` : name;
}

/**
 * Fichier partagé depuis une autre appli de la tablette (« Partager » > Mate) : déposé à la racine du dossier
 * partagé sous le nom de [shareName], jamais par-dessus un fichier déjà là : « nom (2).ext ».
 */
export async function saveShared(data: Buffer, filename: string) {
  await fs.mkdir(config.docsDir, { recursive: true });
  const safe = shareName(filename);
  const ext = path.extname(safe);
  const stem = safe.slice(0, safe.length - ext.length);
  for (let n = 1; ; n++) {
    const name = n === 1 ? safe : `${stem} (${n})${ext}`;
    const full = path.join(config.docsDir, name);
    try {
      // "wx" : échoue si le fichier existe, donc deux envois simultanés ne s'écrasent pas.
      await fs.writeFile(full, data, { flag: "wx" });
      return name;
    } catch (e) {
      if ((e as NodeJS.ErrnoException).code === "EEXIST") continue;
      // Disque plein, coupure : pas de fichier partiel listé comme un fichier valide.
      await fs.rm(full, { force: true });
      throw e;
    }
  }
}

export const FRAME_DIR = "Cadre photo";
/** Formats que la tablette décode (BitmapFactory, Android 7+). */
const FRAME_EXTENSIONS = new Set([".jpg", ".jpeg", ".png", ".webp"]);
/** Assez pour des mois sans répétition (une photo par minute en journée) ; au-delà, la tablette garde la place. */
export const FRAME_MAX = 500;

/**
 * Photos du cadre de la tablette : images du dossier « Cadre photo » du dossier partagé, sous-dossiers compris,
 * dans l'ordre des chemins. Le dossier est créé s'il manque, pour que la tablette puisse l'ouvrir sur l'ordinateur.
 */
export async function framePhotos() {
  const dir = path.join(config.docsDir, FRAME_DIR);
  await fs.mkdir(dir, { recursive: true });
  const names = (await fs.readdir(dir, { recursive: true }))
    .filter((name) => !name.split(path.sep).some((part) => part.startsWith(".")))
    .filter((name) => FRAME_EXTENSIONS.has(path.extname(name).toLowerCase()))
    .sort((a, b) => a.localeCompare(b));
  const photos = [];
  for (const name of names) {
    if (photos.length >= FRAME_MAX) break;
    const stat = await fs.stat(path.join(dir, name)).catch(() => undefined);
    if (!stat?.isFile()) continue;
    // Chemin avec des « / » : relu tel quel par /docs/file, sur Mac comme sur Windows.
    const relative = path.join(FRAME_DIR, name).split(path.sep).join("/");
    photos.push({ path: relative, size: stat.size, mtimeMs: Math.round(stat.mtimeMs) });
  }
  return { dir: FRAME_DIR, photos };
}
