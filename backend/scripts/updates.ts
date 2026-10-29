import fs from "node:fs";
import path from "node:path";
import { dataDir } from "../src/paths.ts";
import type { Backend, Update } from "../src/updates.ts";

/**
 * Alimente le « serveur Unowhy » (src/updates.ts) avec une release GitLab :
 * - `npm run updates:add -- companion-X.Y.Z.json` : le .json (entrée du manifeste) et l'APK qu'il nomme, téléchargés de
 *   la release côte à côte. L'APK remplace la version précédente du même paquet ; les autres paquets (WebView…) restent.
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
  const entry = JSON.parse(fs.readFileSync(entryFile, "utf8")) as Update;
  if (!entry.package || !Number.isInteger(entry.versionCode) || !entry.file || path.basename(entry.file) !== entry.file) {
    throw new Error(`${entryFile} : entrée de mise à jour invalide`);
  }
  const manifest = readManifest();
  const updates = manifest.updates ?? [];
  place(
    path.join(path.dirname(entryFile), entry.file),
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

const [command, file] = process.argv.slice(2);
if (command !== "add" || !file) {
  console.error("Usage : npm run updates:add -- companion-X.Y.Z.json | backend-X.Y.Z-<mac|windows>.tar.gz");
  process.exit(1);
}
try {
  if (file.endsWith(".tar.gz")) addBackend(path.resolve(file));
  else addApp(path.resolve(file));
} catch (e) {
  console.error((e as Error).message);
  process.exit(1);
}
