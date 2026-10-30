import { execFileSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { dataDir } from "../src/paths.ts";
import type { Backend, Update } from "../src/updates.ts";

/**
 * Alimente le serveur de mises à jour de l'ordinateur (src/updates.ts) :
 * - `npm run updates:add -- release [tag]` : télécharge la release GitHub (`latest` par défaut, dépôt UPDATES_REPO,
 *   ahmeedatm/Mate par défaut) avec la CLI `gh` (connectée : `gh auth login`), puis ajoute son APK et les archives du
 *   backend qu'annonce son manifest.json.
 * - `npm run updates:add -- companion-X.Y.Z.json` : le .json (entrée du manifeste) et l'APK qu'il nomme, côte à côte.
 *   L'APK remplace la version précédente du même paquet ; les autres paquets (WebView…) restent.
 * - `npm run updates:add -- backend-X.Y.Z-mac.tar.gz` (ou -windows) : archive du backend d'une release, que la tablette
 *   envoie à l'ordinateur actif ; remplace l'archive précédente de la même plateforme.
 * Fichiers copiés dans <données>/updates/. Ordinateur installé par paquet : DATA_DIR=paquet.
 */

type Manifest = { updates?: Update[]; backends?: Backend[] };

const dir = path.join(dataDir, "updates");
const manifestFile = path.join(dir, "manifest.json");

function readManifest(): Manifest {
  fs.mkdirSync(dir, { recursive: true });
  return fs.existsSync(manifestFile) ? (JSON.parse(fs.readFileSync(manifestFile, "utf8")) as Manifest) : {};
}

const writeManifest = (manifest: Manifest) => fs.writeFileSync(manifestFile, JSON.stringify(manifest, null, 2) + "\n");

/** Copie `file` et retire les fichiers des entrées remplacées (`old`). */
function place(from: string, file: string, old: { file: string }[]) {
  fs.copyFileSync(from, path.join(dir, file));
  for (const o of old) if (o.file !== file) fs.rmSync(path.join(dir, path.basename(o.file)), { force: true });
}

function addApp(entryFile: string) {
  addEntry(JSON.parse(fs.readFileSync(entryFile, "utf8")) as Update, path.dirname(entryFile), entryFile);
}

/** `from` : dossier de l'APK que nomme `entry`. */
function addEntry(entry: Update, from: string, origin: string) {
  if (!entry.package || !Number.isInteger(entry.versionCode) || !entry.file || path.basename(entry.file) !== entry.file) {
    throw new Error(`${origin} : entrée de mise à jour invalide`);
  }
  const { package: pkg, label, versionCode, versionName, file } = entry;
  entry = { package: pkg, label, versionCode, versionName, file };
  const manifest = readManifest();
  const updates = manifest.updates ?? [];
  place(
    path.join(from, entry.file),
    entry.file,
    updates.filter((u) => u.package === entry.package),
  );
  writeManifest({ ...manifest, updates: [...updates.filter((u) => u.package !== entry.package), entry] });
  console.log(`${entry.package} ${entry.versionName} (${entry.versionCode}) annoncé dans ${manifestFile}`);
}

function addBackend(archive: string) {
  const file = path.basename(archive);
  const match = /^backend-(\d+\.\d+\.\d+)-(mac|windows)\.tar\.gz$/.exec(file);
  if (!match) throw new Error(`${file} : backend-X.Y.Z-mac.tar.gz ou backend-X.Y.Z-windows.tar.gz attendu`);
  const backend: Backend = { os: match[2] as Backend["os"], file, version: match[1] };
  const manifest = readManifest();
  const backends = manifest.backends ?? [];
  place(
    archive,
    file,
    backends.filter((b) => b.os === backend.os),
  );
  writeManifest({ ...manifest, backends: [...backends.filter((b) => b.os !== backend.os), backend] });
  console.log(`backend ${backend.os} ${backend.version} annoncé dans ${manifestFile}`);
}

/** Release GitHub téléchargée dans un dossier temporaire : APK et archives backend de son manifest.json. */
function addRelease(tag: string) {
  const repo = process.env.UPDATES_REPO ?? "ahmeedatm/Mate";
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "mate-release-"));
  try {
    const download = (patterns: string[]) =>
      execFileSync(
        "gh",
        ["release", "download", tag, "-R", repo, "--dir", tmp, "--clobber", ...patterns.flatMap((p) => ["--pattern", p])],
        {
          stdio: ["ignore", "inherit", "inherit"],
        },
      );
    download(["manifest.json"]);
    const announced = JSON.parse(fs.readFileSync(path.join(tmp, "manifest.json"), "utf8")) as Manifest;
    // Pas les installeurs de l'ordinateur : l'ordinateur ne sert que l'APK et les archives du backend.
    download([...(announced.updates ?? []), ...(announced.backends ?? [])].map((e) => path.basename(e.file)));
    for (const update of announced.updates ?? []) addEntry(update, tmp, `${repo} ${tag}`);
    for (const backend of announced.backends ?? []) addBackend(path.join(tmp, path.basename(backend.file)));
  } finally {
    fs.rmSync(tmp, { recursive: true, force: true });
  }
}

const [command, file, tag] = process.argv.slice(2);
if (command !== "add" || !file) {
  console.error("Usage : npm run updates:add -- release [tag] | companion-X.Y.Z.json | backend-X.Y.Z-<mac|windows>.tar.gz");
  process.exit(1);
}
try {
  if (file === "release") addRelease(tag ?? "latest");
  else if (file.endsWith(".tar.gz")) addBackend(path.resolve(file));
  else addApp(path.resolve(file));
} catch (e) {
  console.error((e as Error).message);
  process.exit(1);
}
