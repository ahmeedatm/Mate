// Serveur central des mises à jour de la tablette : `node scripts/updates-publish.mjs dist updates`.
// Lit l'entrée companion-X.Y.Z.json produite par android-release, copie l'APK qu'elle nomme dans <sortie>/
// et écrit <sortie>/manifest.json, même forme que GET /updates du backend (taille et SHA-256 compris).
// Avec UPDATES_URL (paquet générique du registre, …/packages/generic/<nom>/<version>), dépose l'APK puis
// manifest.json et supprime les anciens fichiers du paquet : seule la dernière version est servie.
// Jeton : CI_JOB_TOKEN en CI, GITLAB_TOKEN (jeton personnel) pour un essai local.
import fs from "node:fs";
import path from "node:path";
import { createHash } from "node:crypto";

const [distDir, outDir] = process.argv.slice(2);
if (!distDir || !outDir) {
  console.error("Usage : node scripts/updates-publish.mjs <dist> <sortie>");
  process.exit(1);
}

const entries = fs.readdirSync(distDir).filter((f) => /^companion-\d+\.\d+\.\d+\.json$/.test(f));
if (entries.length !== 1) {
  console.error(`${distDir} : une entrée companion-X.Y.Z.json attendue, ${entries.length} trouvée(s)`);
  process.exit(1);
}

const entry = JSON.parse(fs.readFileSync(path.join(distDir, entries[0]), "utf8"));
const { package: pkg, label, versionCode, versionName, file } = entry;
if (!pkg || !label || !Number.isInteger(versionCode) || !versionName || !file || path.basename(file) !== file) {
  console.error(`${entries[0]} : entrée de mise à jour invalide`);
  process.exit(1);
}

const apk = fs.readFileSync(path.join(distDir, file));
const update = {
  package: pkg,
  label,
  versionCode,
  versionName,
  file,
  size: apk.length,
  sha256: createHash("sha256").update(apk).digest("hex"),
};
const manifest = JSON.stringify({ updates: [update] }, null, 2) + "\n";

fs.rmSync(outDir, { recursive: true, force: true });
fs.mkdirSync(outDir, { recursive: true });
fs.writeFileSync(path.join(outDir, file), apk);
fs.writeFileSync(path.join(outDir, "manifest.json"), manifest);
console.log(JSON.stringify(update));

const url = process.env.UPDATES_URL;
if (url) await publish(url.replace(/\/+$/, ""));

async function publish(url) {
  const match = url.match(/^(.+\/projects\/[^/]+)\/packages\/generic\/([^/]+)\/([^/]+)$/);
  if (!match) throw new Error(`UPDATES_URL « ${url} » : attendu …/projects/<id>/packages/generic/<nom>/<version>`);
  const [, projectApi, name, version] = match;
  const auth = process.env.CI_JOB_TOKEN
    ? { "JOB-TOKEN": process.env.CI_JOB_TOKEN }
    : { "PRIVATE-TOKEN": process.env.GITLAB_TOKEN ?? "" };

  async function api(method, target, body) {
    const res = await fetch(target, { method, headers: auth, body });
    if (!res.ok) throw new Error(`${method} ${target.replace(projectApi, "")} : HTTP ${res.status}`);
    return res;
  }

  // APK d'abord : le manifest ne désigne jamais un fichier absent.
  await api("PUT", `${url}/${file}`, apk);
  await api("PUT", `${url}/manifest.json`, manifest);

  // Le téléchargement rend le dernier fichier déposé sous un nom ; les autres sont supprimés.
  const packages = await (await api("GET", `${projectApi}/packages?package_type=generic&package_name=${name}&per_page=100`)).json();
  const found = packages.find((p) => p.name === name && p.version === version);
  if (!found) throw new Error(`paquet ${name}/${version} introuvable après dépôt`);
  const files = await (await api("GET", `${projectApi}/packages/${found.id}/package_files?per_page=100`)).json();
  const keep = new Set(
    ["manifest.json", file].map((n) => Math.max(...files.filter((f) => f.file_name === n).map((f) => f.id))),
  );
  for (const f of files.filter((f) => !keep.has(f.id))) {
    await api("DELETE", `${projectApi}/packages/${found.id}/package_files/${f.id}`);
    console.log(`supprimé : ${f.file_name} (${f.id})`);
  }
  console.log(`publié : ${url}/manifest.json`);
}
