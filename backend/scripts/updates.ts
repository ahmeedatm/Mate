import { createHash } from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { dataDir } from "../src/paths.ts";
import type { Update } from "../src/updates.ts";

/**
 * Alimente le « serveur Unowhy » (src/updates.ts) avec une release GitLab : `npm run updates:add -- companion-X.Y.Z.json`.
 * Le .json (entrée du manifeste) et l'APK qu'il nomme sont téléchargés de la release, côte à côte. L'APK est copié
 * dans <données>/updates/ et remplace la version précédente du même paquet ; les autres paquets (WebView…) restent.
 * Ordinateur installé par paquet : DATA_DIR=paquet.
 *
 * `npm run updates:essai` : même effet avec l'essai publié depuis une MR (job essai-updates, paquet générique
 * companion-updates/essai du registre). Jeton : GITLAB_TOKEN (personnel, read_api) ou UPDATE_DEPLOY_TOKEN (deploy token
 * des tablettes), dans l'environnement ou backend/.env. UPDATES_URL : autre paquet (tests).
 */

const ESSAI_URL = "https://gitlab.unowhy.dev/api/v4/projects/891/packages/generic/companion-updates/essai";

function add(entryFile: string) {
  const entry = JSON.parse(fs.readFileSync(entryFile, "utf8")) as Update;
  if (!entry.package || !Number.isInteger(entry.versionCode) || !entry.file || path.basename(entry.file) !== entry.file) {
    throw new Error(`${entryFile} : entrée de mise à jour invalide`);
  }
  const dir = path.join(dataDir, "updates");
  const manifestFile = path.join(dir, "manifest.json");
  fs.mkdirSync(dir, { recursive: true });
  const updates = fs.existsSync(manifestFile)
    ? ((JSON.parse(fs.readFileSync(manifestFile, "utf8")) as { updates?: Update[] }).updates ?? [])
    : [];

  fs.copyFileSync(path.join(path.dirname(entryFile), entry.file), path.join(dir, entry.file));
  for (const old of updates) {
    if (old.package === entry.package && old.file !== entry.file) fs.rmSync(path.join(dir, path.basename(old.file)), { force: true });
  }
  const next = [...updates.filter((u) => u.package !== entry.package), entry];
  fs.writeFileSync(manifestFile, JSON.stringify({ updates: next }, null, 2) + "\n");
  console.log(`${entry.package} ${entry.versionName} (${entry.versionCode}) annoncé dans ${manifestFile}`);
}

/** Télécharge le manifest et les APK du paquet d'essai, vérifie taille et SHA-256, puis les ajoute comme `add`. */
async function essai() {
  const base = (process.env.UPDATES_URL || ESSAI_URL).replace(/\/+$/, "");
  const headers: Record<string, string> = process.env.GITLAB_TOKEN
    ? { "PRIVATE-TOKEN": process.env.GITLAB_TOKEN }
    : process.env.UPDATE_DEPLOY_TOKEN
      ? { "Deploy-Token": process.env.UPDATE_DEPLOY_TOKEN }
      : {};
  if (!Object.keys(headers).length) throw new Error("Jeton absent : GITLAB_TOKEN (jeton personnel) ou UPDATE_DEPLOY_TOKEN");

  async function download(file: string) {
    const res = await fetch(`${base}/${file}`, { headers });
    if (res.status === 401 || res.status === 403) throw new Error(`${base} : jeton refusé (HTTP ${res.status})`);
    if (res.status === 404) throw new Error(`${base}/${file} : introuvable, aucun essai publié ?`);
    if (!res.ok) throw new Error(`${base}/${file} : HTTP ${res.status}`);
    return Buffer.from(await res.arrayBuffer());
  }

  const { updates } = JSON.parse((await download("manifest.json")).toString("utf8")) as {
    updates?: (Update & { size?: number; sha256?: string })[];
  };
  if (!updates?.length) throw new Error(`${base}/manifest.json : aucune mise à jour annoncée`);
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "companion-essai-"));
  try {
    for (const { size, sha256, ...entry } of updates) {
      if (!entry.file || path.basename(entry.file) !== entry.file) throw new Error(`${base}/manifest.json : entrée invalide`);
      const apk = await download(entry.file);
      if (apk.length !== size || createHash("sha256").update(apk).digest("hex") !== sha256) {
        throw new Error(`${entry.file} : taille ou SHA-256 différent du manifest, rien n'est changé`);
      }
      fs.writeFileSync(path.join(tmp, entry.file), apk);
      const entryFile = path.join(tmp, `${entry.file}.json`);
      fs.writeFileSync(entryFile, JSON.stringify(entry));
      add(entryFile);
    }
  } finally {
    fs.rmSync(tmp, { recursive: true, force: true });
  }
}

const [command, file] = process.argv.slice(2);
if (!(command === "add" && file) && command !== "essai") {
  console.error("Usage : npm run updates:add -- companion-X.Y.Z.json, ou npm run updates:essai");
  process.exit(1);
}
try {
  if (command === "add") add(path.resolve(file));
  else await essai();
} catch (e) {
  console.error((e as Error).message);
  process.exit(1);
}
