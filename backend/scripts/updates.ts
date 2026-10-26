import fs from "node:fs";
import path from "node:path";
import { dataDir } from "../src/paths.ts";
import type { Update } from "../src/updates.ts";

/**
 * Alimente le « serveur Unowhy » (src/updates.ts) avec une release GitLab : `npm run updates:add -- companion-X.Y.Z.json`.
 * Le .json (entrée du manifeste) et l'APK qu'il nomme sont téléchargés de la release, côte à côte. L'APK est copié
 * dans <données>/updates/ et remplace la version précédente du même paquet ; les autres paquets (WebView…) restent.
 * Ordinateur installé par paquet : DATA_DIR=paquet.
 */

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

const [command, file] = process.argv.slice(2);
if (command !== "add" || !file) {
  console.error("Usage : npm run updates:add -- companion-X.Y.Z.json");
  process.exit(1);
}
try {
  add(path.resolve(file));
} catch (e) {
  console.error((e as Error).message);
  process.exit(1);
}
