import fs from "node:fs/promises";
import path from "node:path";
import { warn } from "./log.ts";

/**
 * Fichiers JSON de .data/ : lecture tolérante, écriture atomique (fichier temporaire puis rename : une coupure
 * ne laisse jamais un fichier tronqué), écritures d'un même fichier mises en file (pas de mise à jour perdue).
 */

const queues = new Map<string, Promise<unknown>>();

function serial<T>(file: string, task: () => Promise<T>): Promise<T> {
  const next = (queues.get(file) ?? Promise.resolve()).catch(() => undefined).then(task);
  const tail = next.catch(() => undefined);
  queues.set(file, tail);
  void tail.then(() => queues.get(file) === tail && queues.delete(file));
  return next;
}

let tmpCount = 0;

async function writeNow(file: string, data: unknown, mode?: number) {
  await fs.mkdir(path.dirname(file), { recursive: true });
  const tmp = `${file}.${process.pid}.${tmpCount++}.tmp`;
  try {
    await fs.writeFile(tmp, JSON.stringify(data, null, 2) + "\n", { mode });
    await fs.rename(tmp, file);
  } catch (e) {
    await fs.rm(tmp, { force: true });
    throw e;
  }
}

/**
 * Contenu du fichier, ou `fallback()` s'il est absent, vide ou illisible (trace dans le journal). Un fichier abîmé
 * est mis de côté en `.bad` pour ne pas être écrasé sans trace ; `quarantine: false` pour un fichier écrit à la main.
 */
export async function readJson<T>(file: string, fallback: () => T, { quarantine = true } = {}): Promise<T> {
  let raw: string;
  try {
    raw = await fs.readFile(file, "utf8");
  } catch (e) {
    if ((e as NodeJS.ErrnoException).code !== "ENOENT")
      warn(`${path.basename(file)} illisible (${(e as Error).message}) : valeur par défaut`);
    return fallback();
  }
  try {
    const data = JSON.parse(raw) as T;
    if (data === null || typeof data !== "object") throw new Error("pas un objet JSON");
    return data;
  } catch (e) {
    warn(
      `${path.basename(file)} abîmé (${(e as Error).message}) : valeur par défaut${quarantine ? `, copie dans ${path.basename(file)}.bad` : ""}`,
    );
    if (quarantine) await fs.rename(file, file + ".bad").catch(() => undefined);
    return fallback();
  }
}

export const writeJson = (file: string, data: unknown, { mode }: { mode?: number } = {}) => serial(file, () => writeNow(file, data, mode));

/** Relit, applique `change` (qui modifie `data` sur place), écrit ; en file avec les autres écritures du fichier. */
export function updateJson<T, R = void>(
  file: string,
  fallback: () => T,
  change: (data: T) => R | Promise<R>,
  { mode, quarantine }: { mode?: number; quarantine?: boolean } = {},
): Promise<R> {
  return serial(file, async () => {
    const data = await readJson(file, fallback, { quarantine });
    const result = await change(data);
    await writeNow(file, data, mode);
    return result;
  });
}

export const removeJson = (file: string) => serial(file, () => fs.rm(file, { force: true }));
