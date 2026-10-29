import fs from "node:fs/promises";
import { createReadStream } from "node:fs";
import { createHash } from "node:crypto";
import path from "node:path";
import { config } from "./config.ts";
import { readJson } from "./store.ts";

/**
 * « Serveur Unowhy » de mises à jour, simulé par le backend pour la démo du hackathon : la tablette (device owner)
 * y trouve les nouvelles versions du moteur web (WebView) et du Companion, les télécharge et les installe seule,
 * en kiosque. En production, ce serait l'infrastructure SQOOL (MDM, OTA) d'Unowhy.
 *
 * Dossier .data/updates/ : les APK et manifest.json, écrit à la main :
 * { "updates": [{ "package": "com.google.android.webview", "label": "Navigateur web", "versionCode": 1, "versionName": "…", "file": "webview.apk" }] }
 * Empreinte SHA-256 et taille calculées ici : la tablette vérifie le fichier reçu, Android vérifie la signature.
 * Clé `backends` (npm run updates:add -- backend-X.Y.Z-mac.tar.gz) : archives du backend d'une release, même forme que
 * dans le manifest du serveur central ; la tablette en envoie une à l'ordinateur actif (POST /companion/backend/update).
 */
const dir = path.join(config.dataDir, "updates");

export type Update = { package: string; label: string; versionCode: number; versionName: string; file: string };
export type Backend = { os: "mac" | "windows"; file: string; version: string };

const hashes = new Map<string, { mtimeMs: number; size: number; sha256: string }>();

async function fingerprint(file: string) {
  const full = path.join(dir, file);
  const stat = await fs.stat(full);
  const known = hashes.get(file);
  if (known && known.mtimeMs === stat.mtimeMs) return known;
  const hash = createHash("sha256");
  for await (const chunk of createReadStream(full)) hash.update(chunk as Buffer);
  const entry = { mtimeMs: stat.mtimeMs, size: stat.size, sha256: hash.digest("hex") };
  hashes.set(file, entry);
  return entry;
}

// Nom de fichier simple seulement : pas de chemin qui sortirait du dossier.
const plain = (file: string | undefined) => !!file && path.basename(file) === file;

async function manifest(): Promise<{ updates: Update[]; backends: Backend[] }> {
  // Écrit à la main : un fichier abîmé est signalé, jamais déplacé.
  const manifest = await readJson<{ updates?: Update[]; backends?: Backend[] }>(path.join(dir, "manifest.json"), () => ({}), {
    quarantine: false,
  });
  return {
    updates: (manifest.updates ?? []).filter((u) => plain(u.file)),
    backends: (manifest.backends ?? []).filter((b) => plain(b.file) && (b.os === "mac" || b.os === "windows")),
  };
}

const withFingerprint = <T extends { file: string }>(entries: T[]) =>
  Promise.all(entries.map(async (e) => ({ ...e, ...(await fingerprint(e.file).then(({ size, sha256 }) => ({ size, sha256 }))) })));

export async function listUpdates() {
  return withFingerprint((await manifest()).updates);
}

export async function listBackends() {
  return withFingerprint((await manifest()).backends);
}

/** Chemin du fichier d'une mise à jour ou d'une archive annoncée, undefined sinon. */
export async function updateFile(file: string) {
  const { updates, backends } = await manifest();
  return [...updates, ...backends].some((u) => u.file === file) ? path.join(dir, file) : undefined;
}
