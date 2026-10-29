// Serveur central des mises à jour de la tablette. Trois usages :
//
//   node scripts/updates-publish.mjs dist updates                  release : paquet …/companion-updates/latest
//   node scripts/updates-publish.mjs --essai <iid> dist essais     essai de la MR <iid> : paquet …/companion-updates/essais
//   node scripts/updates-publish.mjs --retire <iid> essais         retire l'essai de la MR <iid> (fusion ou abandon)
//
// Release : lit l'entrée companion-X.Y.Z.json produite par android-release, copie l'APK qu'elle nomme dans <sortie>/ et
// écrit <sortie>/manifest.json (même forme que GET /updates du backend, taille et SHA-256 compris). Les installeurs de
// l'ordinateur présents dans <dist> (Compagnon-Unowhy-Mac.zip, Compagnon-Unowhy-Windows.exe) sont copiés aussi et décrits
// dans manifest.json, clé `installers` : la tablette les télécharge pour les servir sur le WiFi.
// Essai : même entrée (companion-X.Y.Z-essai.N.json d'essai-android), plus dist/backend-mac.tar.gz et
// dist/backend-windows.tar.gz si le backend de la MR a changé. Le manifest {"essais":[…]} a une entrée par MR (numéro,
// titre MR_TITLE, version, APK, backends) : une MR ne remplace que la sienne.
// Avec UPDATES_URL (paquet générique du registre, …/packages/generic/<nom>/<version>), dépose les fichiers puis
// manifest.json et supprime les fichiers que le manifest ne nomme plus. Essai : le manifest du registre est lu puis
// réécrit ; les jobs essai-updates se suivent (resource_group), --retire se lance quand aucun ne publie.
// Jeton : CI_JOB_TOKEN en CI, GITLAB_TOKEN (jeton personnel, scope api) en local.
import fs from "node:fs";
import path from "node:path";
import { createHash } from "node:crypto";

const BACKEND_PLATFORMS = ["mac", "windows"];
const ESSAI_PACKAGE = "com.unowhy.companion.essai";
const MAX_TITLE = 200;
const INSTALLERS = { mac: "Compagnon-Unowhy-Mac.zip", windows: "Compagnon-Unowhy-Windows.exe" };

const fingerprint = (buf) => ({ size: buf.length, sha256: createHash("sha256").update(buf).digest("hex") });

function usage() {
  console.error(
    "Usage : node scripts/updates-publish.mjs <dist> <sortie>\n" +
      "        node scripts/updates-publish.mjs --essai <iid> <dist> <sortie>\n" +
      "        node scripts/updates-publish.mjs --retire <iid> <sortie>",
  );
  process.exit(1);
}

/** Entrée companion-X.Y.Z[-essai.N].json du dossier `dist`, vérifiée. */
function readEntry(distDir, essai) {
  const entries = fs.readdirSync(distDir).filter((f) => /^companion-\d+\.\d+\.\d+(-essai\.\d+)?\.json$/.test(f));
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
  if (essai && (pkg !== ESSAI_PACKAGE || !/-essai\.\d+$/.test(versionName))) {
    console.error(`${entries[0]} : un essai a le paquet ${ESSAI_PACKAGE} et une version X.Y.Z-essai.N`);
    process.exit(1);
  }
  return { package: pkg, label, versionCode, versionName, file };
}

/** Paquet générique du registre : dépôt, lecture, suppression. */
function registry(url) {
  const match = url.replace(/\/+$/, "").match(/^(.+\/projects\/[^/]+)\/packages\/generic\/([^/]+)\/([^/]+)$/);
  if (!match) throw new Error(`UPDATES_URL « ${url} » : attendu …/projects/<id>/packages/generic/<nom>/<version>`);
  const [, projectApi, name, version] = match;
  const base = `${projectApi}/packages/generic/${name}/${version}`;
  const auth = process.env.CI_JOB_TOKEN ? { "JOB-TOKEN": process.env.CI_JOB_TOKEN } : { "PRIVATE-TOKEN": process.env.GITLAB_TOKEN ?? "" };

  async function api(method, target, body, okCodes = []) {
    const res = await fetch(target, { method, headers: auth, body });
    if (!res.ok && !okCodes.includes(res.status)) throw new Error(`${method} ${target.replace(projectApi, "")} : HTTP ${res.status}`);
    return res;
  }

  async function pages(target) {
    const all = [];
    for (let page = 1; ; page++) {
      const batch = await (await api("GET", `${target}${target.includes("?") ? "&" : "?"}per_page=100&page=${page}`)).json();
      all.push(...batch);
      if (batch.length < 100) return all;
    }
  }

  return {
    base,
    put: (file, body) => api("PUT", `${base}/${file}`, body),
    /** Manifest déjà publié, null s'il n'y en a pas. */
    async manifest() {
      const res = await api("GET", `${base}/manifest.json`, undefined, [404]);
      return res.status === 404 ? null : res.json();
    },
    /** Supprime les fichiers du paquet que `keep` ne nomme pas, et les anciens doublons d'un même nom (le dernier déposé est servi). */
    async prune(keep) {
      const packages = await pages(`${projectApi}/packages?package_type=generic&package_name=${name}`);
      const found = packages.find((p) => p.name === name && p.version === version);
      if (!found) throw new Error(`paquet ${name}/${version} introuvable après dépôt`);
      const files = await pages(`${projectApi}/packages/${found.id}/package_files`);
      const latest = new Map();
      for (const f of files) if (keep.has(f.file_name)) latest.set(f.file_name, Math.max(latest.get(f.file_name) ?? 0, f.id));
      for (const f of files.filter((f) => latest.get(f.file_name) !== f.id)) {
        await api("DELETE", `${projectApi}/packages/${found.id}/package_files/${f.id}`);
        console.log(`supprimé : ${f.file_name} (${f.id})`);
      }
      console.log(`publié : ${base}/manifest.json`);
    },
  };
}

function writeOut(outDir, files) {
  fs.rmSync(outDir, { recursive: true, force: true });
  fs.mkdirSync(outDir, { recursive: true });
  for (const [name, content] of Object.entries(files)) fs.writeFileSync(path.join(outDir, name), content);
}

const arg = process.argv.slice(2);
const mode = arg[0] === "--essai" ? "essai" : arg[0] === "--retire" ? "retire" : "release";
if (mode !== "release") arg.shift();
const iid = mode === "release" ? undefined : Number(arg.shift());
if (mode !== "release" && !(Number.isInteger(iid) && iid > 0)) usage();
const url = process.env.UPDATES_URL;

if (mode === "release") {
  const [distDir, outDir] = arg;
  if (!distDir || !outDir) usage();
  const entry = readEntry(distDir, false);
  const apk = fs.readFileSync(path.join(distDir, entry.file));
  const update = { ...entry, ...fingerprint(apk) };
  const files = { [entry.file]: apk };
  const installers = [];
  for (const [os, file] of Object.entries(INSTALLERS)) {
    if (!fs.existsSync(path.join(distDir, file))) continue;
    files[file] = fs.readFileSync(path.join(distDir, file));
    installers.push({ os, file, ...fingerprint(files[file]), version: entry.versionName });
  }
  const manifest = JSON.stringify({ updates: [update], ...(installers.length ? { installers } : {}) }, null, 2) + "\n";
  writeOut(outDir, { ...files, "manifest.json": manifest });
  console.log(JSON.stringify(update));
  if (installers.length) console.log(`installeurs : ${installers.map((i) => i.file).join(", ")}`);
  if (url) {
    const reg = registry(url);
    // Fichiers d'abord : le manifest ne désigne jamais un fichier absent.
    for (const [file, data] of Object.entries(files)) await reg.put(file, data);
    await reg.put("manifest.json", manifest);
    await reg.prune(new Set(["manifest.json", ...Object.keys(files)]));
  }
} else {
  if (!url) {
    console.error("UPDATES_URL : paquet des essais attendu (…/packages/generic/companion-updates/essais)");
    process.exit(1);
  }
  const reg = registry(url);
  const essais = ((await reg.manifest())?.essais ?? []).filter((e) => e.mr !== iid);
  const files = {};
  if (mode === "essai") {
    const [distDir, outDir] = arg;
    if (!distDir || !outDir) usage();
    const entry = readEntry(distDir, true);
    const apk = fs.readFileSync(path.join(distDir, entry.file));
    files[entry.file] = apk;
    const backend = {};
    for (const platform of BACKEND_PLATFORMS) {
      const archive = path.join(distDir, `backend-${platform}.tar.gz`);
      if (!fs.existsSync(archive)) continue;
      const data = fs.readFileSync(archive);
      const file = `backend-${entry.versionName}-${platform}.tar.gz`;
      files[file] = data;
      backend[platform] = { file, ...fingerprint(data) };
    }
    if (Object.keys(backend).length && Object.keys(backend).length !== BACKEND_PLATFORMS.length) {
      console.error(`${distDir} : une archive backend par plateforme (${BACKEND_PLATFORMS.join(", ")}) ou aucune`);
      process.exit(1);
    }
    const title = (process.env.MR_TITLE ?? "").trim().slice(0, MAX_TITLE);
    essais.push({ mr: iid, title, ...entry, ...fingerprint(apk), ...(Object.keys(backend).length ? { backend } : {}) });
    essais.sort((a, b) => a.mr - b.mr);
    console.log(JSON.stringify(essais.find((e) => e.mr === iid)));
    const manifest = JSON.stringify({ essais }, null, 2) + "\n";
    writeOut(outDir, { ...files, "manifest.json": manifest });
    // Fichiers d'abord : le manifest ne désigne jamais un fichier absent.
    for (const [file, data] of Object.entries(files)) await reg.put(file, data);
    await reg.put("manifest.json", manifest);
  } else {
    const [outDir] = arg;
    if (!outDir) usage();
    const manifest = JSON.stringify({ essais }, null, 2) + "\n";
    writeOut(outDir, { "manifest.json": manifest });
    await reg.put("manifest.json", manifest);
    console.log(`MR !${iid} retirée des essais (${essais.length} restant(s))`);
  }
  const keep = new Set(["manifest.json"]);
  for (const e of essais) {
    keep.add(e.file);
    for (const b of Object.values(e.backend ?? {})) keep.add(b.file);
  }
  await reg.prune(keep);
}
