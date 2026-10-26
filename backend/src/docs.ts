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

export async function savePhoto(data: Buffer, extension = ".jpg") {
  const dir = path.join(config.docsDir, PHOTOS_DIR);
  await fs.mkdir(dir, { recursive: true });
  const d = new Date();
  const pad = (n: number) => String(n).padStart(2, "0");
  const stamp = `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}_${pad(d.getHours())}-${pad(d.getMinutes())}-${pad(d.getSeconds())}`;
  const name = `photo-${stamp}${extension}`;
  await fs.writeFile(path.join(dir, name), data);
  return path.join(PHOTOS_DIR, name);
}
