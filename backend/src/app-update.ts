import { spawn } from "node:child_process";
import { createHash } from "node:crypto";
import fs from "node:fs";
import { once } from "node:events";
import path from "node:path";
import { appPath, sh } from "./portable.ts";
import { tabletAddress } from "./tablet.ts";

/**
 * Mise à jour de l'appli portable depuis la tablette : elle sert sur le WiFi (port 8080) les installeurs qu'elle a
 * téléchargés du serveur central, avec leur version et leur SHA-256 (`/installers.json`). Le panneau télécharge celui de
 * ce système, vérifie taille et empreinte, remplace l'exe ou l'appli sur place et la relance : le nouveau lanceur
 * demande à l'ancien de quitter (POST /local/quit) puis extrait son runtime.
 * L'empreinte vient de la tablette elle-même, sur le réseau local : elle protège d'un fichier abîmé, pas d'un réseau hostile.
 */

export type Installer = { file: string; size: number; sha256: string; version: string };
const PORT = 8080;
const MAX_SIZE = 500 * 1024 * 1024;
const platform = process.platform === "win32" ? "windows" : "mac";

/** Installeur de `os` dans la réponse de `/installers.json` ; entrée incomplète ou nom de fichier suspect : ignorée. */
export function pickInstaller(json: unknown, os: "mac" | "windows"): Installer | undefined {
  const list = (json as { installers?: unknown })?.installers;
  if (!Array.isArray(list)) return undefined;
  for (const i of list as Record<string, unknown>[]) {
    if (i?.os !== os || typeof i.file !== "string" || typeof i.version !== "string" || typeof i.sha256 !== "string") continue;
    if (!/^[^/\\.][^/\\]*$/.test(i.file) || !/^[0-9a-f]{64}$/i.test(i.sha256) || !Number.isInteger(i.size) || (i.size as number) <= 0)
      continue;
    if ((i.size as number) > MAX_SIZE) continue;
    return { file: i.file, size: i.size as number, sha256: i.sha256.toLowerCase(), version: i.version };
  }
  return undefined;
}

const numbers = (v: string) => /^(\d+)\.(\d+)\.(\d+)$/.exec(v)?.slice(1).map(Number);

/** `candidate` strictement plus récente que `current`. Lancé depuis le dépôt (« dev ») ou version illisible : jamais. */
export function isNewer(candidate: string, current: string): boolean {
  const a = numbers(candidate);
  const b = numbers(current);
  if (!a || !b) return false;
  for (let i = 0; i < 3; i++) if (a[i] !== b[i]) return a[i] > b[i];
  return false;
}

function fail(message: string, statusCode = 409): never {
  throw Object.assign(new Error(message), { statusCode });
}

async function announced(): Promise<{ base: string; installer: Installer | undefined }> {
  const ip = tabletAddress();
  if (!ip) fail("La tablette n'est pas connectée en WiFi : ouvre le compagnon sur la tablette, puis réessaie.");
  const base = `http://${ip}:${PORT}`;
  try {
    const res = await fetch(`${base}/installers.json`, { signal: AbortSignal.timeout(5_000) });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    return { base, installer: pickInstaller(await res.json(), platform) };
  } catch {
    return fail("La tablette ne répond pas sur le port 8080 : mets à jour son appli, puis réessaie.", 502);
  }
}

/** Version proposée par la tablette si elle est plus récente que celle-ci ; null sinon. */
export async function checkUpdate(current: string): Promise<{ current: string; available: string | null }> {
  const { installer } = await announced();
  return { current, available: installer && isNewer(installer.version, current) ? installer.version : null };
}

/** Télécharge dans `file`, taille et SHA-256 vérifiés ; le fichier est supprimé s'ils ne correspondent pas. */
async function download(url: string, file: string, expected: Installer) {
  const res = await fetch(url, { signal: AbortSignal.timeout(10 * 60_000) });
  if (!res.ok || !res.body) fail(`Téléchargement impossible (HTTP ${res.status})`, 502);
  const hash = createHash("sha256");
  const out = fs.createWriteStream(file);
  let size = 0;
  try {
    for await (const chunk of res.body as unknown as AsyncIterable<Uint8Array>) {
      size += chunk.length;
      if (size > expected.size) fail("Fichier plus gros qu'annoncé : abandon.", 502);
      hash.update(chunk);
      if (!out.write(chunk)) await once(out, "drain");
    }
    out.end();
    await once(out, "finish");
    if (size !== expected.size || hash.digest("hex") !== expected.sha256)
      fail("Fichier abîmé (taille ou SHA-256 différents) : rien n'est changé.", 502);
  } catch (e) {
    out.destroy();
    fs.rmSync(file, { force: true });
    throw e;
  }
}

/**
 * Remplace l'appli par la version de la tablette puis la relance (après la réponse : `launch` est appelé par la route).
 * Windows : l'exe en marche se renomme (`.old`, effacé par le nouveau lanceur), le nouveau prend sa place.
 * Mac : le zip est extrait à côté, l'ancienne appli passe en `.old` (effacée par le nouveau lanceur).
 */
export async function installUpdate(current: string): Promise<{ version: string; launch: () => void }> {
  if (!appPath) fail("Disponible dans l'appli portable seulement");
  const { base, installer } = await announced();
  if (!installer || !isNewer(installer.version, current)) fail("Aucune version plus récente sur la tablette.");
  const old = `${appPath}.old`;
  if (platform === "windows") {
    const fresh = `${appPath}.new`;
    await download(`${base}/${encodeURIComponent(installer.file)}`, fresh, installer);
    try {
      fs.rmSync(old, { force: true });
      fs.renameSync(appPath, old);
      fs.renameSync(fresh, appPath);
    } catch (e) {
      if (fs.existsSync(old) && !fs.existsSync(appPath)) fs.renameSync(old, appPath);
      fs.rmSync(fresh, { force: true });
      fail(`Remplacement impossible : ${(e as Error).message}`, 500);
    }
    return {
      version: installer.version,
      launch: () => spawn(appPath!, [], { detached: true, stdio: "ignore", windowsHide: true }).unref(),
    };
  }
  const work = `${appPath}.new`;
  fs.rmSync(work, { recursive: true, force: true });
  fs.mkdirSync(work, { recursive: true });
  try {
    const zip = path.join(work, installer.file);
    await download(`${base}/${encodeURIComponent(installer.file)}`, zip, installer);
    const unzipped = path.join(work, "app");
    const r = await sh("ditto", ["-x", "-k", zip, unzipped], 120_000);
    const fresh = fs.existsSync(unzipped) ? findApp(unzipped) : undefined;
    if (!r.ok || !fresh) fail("Archive illisible : rien n'est changé.", 502);
    fs.rmSync(old, { recursive: true, force: true });
    fs.renameSync(appPath, old);
    try {
      fs.renameSync(fresh, appPath);
    } catch (e) {
      fs.renameSync(old, appPath);
      fail(`Remplacement impossible : ${(e as Error).message}`, 500);
    }
  } finally {
    fs.rmSync(work, { recursive: true, force: true });
  }
  return { version: installer.version, launch: () => spawn("open", [appPath!], { detached: true, stdio: "ignore" }).unref() };
}

/** « Compagnon Unowhy.app » dans le zip, à la racine ou dans son dossier. */
function findApp(dir: string): string | undefined {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory() && entry.name.endsWith(".app")) return full;
    if (entry.isDirectory() && !entry.name.startsWith("__MACOSX")) {
      const inner = findApp(full);
      if (inner) return inner;
    }
  }
  return undefined;
}
