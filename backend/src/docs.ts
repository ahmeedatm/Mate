import fs from "node:fs/promises";
import path from "node:path";
import { config } from "./config.ts";

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

/**
 * Nom sûr sur Mac comme sur Windows : sans dossier, sans caractère interdit ou de contrôle (un « : » ouvrirait un
 * flux NTFS), sans point de tête (le fichier serait caché de la liste) ni point ou espace final, sans nom réservé
 * (CON, NUL…), au plus [NAME_MAX] caractères, extension gardée. Rien d'exploitable : `Reçu <date>`.
 */
function shareName(filename: string) {
  const base = path.basename(filename.replace(/\\/g, "/"));
  const clean = base
    .replace(/[<>:"|?*\x00-\x1f]/g, "_")
    .replace(/^\.+/, "")
    .replace(/[. ]+$/, "")
    .trim();
  if (clean === "") return `Reçu ${stamp()}`;
  const ext = path.extname(clean).slice(0, 20);
  const stem = clean.slice(0, clean.length - path.extname(clean).length).slice(0, NAME_MAX - ext.length);
  return RESERVED_NAME.test(stem) ? `_${stem}${ext}` : stem + ext;
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
