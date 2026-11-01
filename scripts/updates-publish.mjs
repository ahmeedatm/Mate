// Mises à jour de la tablette, publiées dans des releases GitHub. Quatre usages :
//
//   node scripts/updates-publish.mjs dist updates                  release : tag `latest`
//   node scripts/updates-publish.mjs --essai <pr> dist essais      essai de la PR <pr> : tag `essais` (pré-release)
//   node scripts/updates-publish.mjs --retire <pr> essais          retire l'essai de la PR <pr> (fusion ou abandon)
//   node scripts/updates-publish.mjs --purge-fermees essais        retire les essais des PR qui ne sont plus ouvertes
//
// Release : lit l'entrée companion-X.Y.Z.json produite par la compilation de l'APK, copie l'APK qu'elle nomme dans
// <sortie>/ et écrit <sortie>/manifest.json (même forme que GET /updates du backend, taille et SHA-256 compris). Les
// installeurs de l'ordinateur présents dans <dist> (Mate-Mac.zip, Mate-Windows.exe) sont copiés aussi et décrits
// dans manifest.json, clé `installers` : la tablette les télécharge pour les servir sur le WiFi. Les archives du backend
// (dist/backend-mac.tar.gz et backend-windows.tar.gz, les deux ou aucune) deviennent backend-X.Y.Z-<os>.tar.gz, clé
// `backends` : la tablette en envoie une à l'ordinateur actif, qui se met à jour seul.
// Essai : même entrée (companion-X.Y.Z-essai.N.json), plus dist/backend-mac.tar.gz et dist/backend-windows.tar.gz si le
// backend de la PR a changé. Le manifest {"essais":[…]} a une entrée par PR (clé `mr` = numéro de la PR, titre PR_TITLE,
// version, APK, backends) : une PR ne remplace que la sienne.
// Avec UPDATES_REPO (« propriétaire/dépôt »), publie par la CLI `gh` (GH_TOKEN en CI, ou `gh auth login` en local) : crée
// la release du tag si elle manque, envoie les fichiers (--clobber), une copie manifest.bak.json puis manifest.json (relue
// si une coupure a emporté manifest.json), et supprime les assets que le manifest ne nomme plus. Essai : le manifest de la release est lu puis réécrit ; les publications doivent se suivre
// (`concurrency` du workflow), --retire aussi. Sans UPDATES_REPO, une release n'écrit que <sortie> (essai local).
// --purge-fermees retire d'un coup les entrées dont la PR n'est plus ouverte (`gh pr view`, fusionnée ou fermée).
import fs from "node:fs";
import path from "node:path";
import { createHash } from "node:crypto";
import { execFileSync } from "node:child_process";

const BACKEND_PLATFORMS = ["mac", "windows"];
const ESSAI_PACKAGE = "app.mate.essai";
const MAX_TITLE = 200;
const INSTALLERS = {
  mac: "Mate-Mac.zip",
  windows: "Mate-Windows.exe",
};

const fingerprint = (buf) => ({
  size: buf.length,
  sha256: createHash("sha256").update(buf).digest("hex"),
});

function usage() {
  console.error(
    "Usage : node scripts/updates-publish.mjs <dist> <sortie>\n" +
      "        node scripts/updates-publish.mjs --essai <pr> <dist> <sortie>\n" +
      "        node scripts/updates-publish.mjs --retire <pr> <sortie>\n" +
      "        node scripts/updates-publish.mjs --purge-fermees <sortie>",
  );
  process.exit(1);
}

/** Entrée companion-X.Y.Z[-essai.N].json du dossier `dist`, vérifiée. */
function readEntry(distDir, essai) {
  const entries = fs
    .readdirSync(distDir)
    .filter((f) => /^companion-\d+\.\d+\.\d+(-essai\.\d+)?\.json$/.test(f));
  if (entries.length !== 1) {
    console.error(
      `${distDir} : une entrée companion-X.Y.Z.json attendue, ${entries.length} trouvée(s)`,
    );
    process.exit(1);
  }
  const entry = JSON.parse(
    fs.readFileSync(path.join(distDir, entries[0]), "utf8"),
  );
  const { package: pkg, label, versionCode, versionName, file } = entry;
  if (
    !pkg ||
    !label ||
    !Number.isInteger(versionCode) ||
    !versionName ||
    !file ||
    path.basename(file) !== file
  ) {
    console.error(`${entries[0]} : entrée de mise à jour invalide`);
    process.exit(1);
  }
  if (essai && (pkg !== ESSAI_PACKAGE || !/-essai\.\d+$/.test(versionName))) {
    console.error(
      `${entries[0]} : un essai a le paquet ${ESSAI_PACKAGE} et une version X.Y.Z-essai.N`,
    );
    process.exit(1);
  }
  return { package: pkg, label, versionCode, versionName, file };
}

/** Copie du manifest, envoyée juste avant lui (voir `publish`). */
const BACKUP = "manifest.bak.json";

const TAGS = {
  latest: { title: "Mate : dernière version", prerelease: false },
  essais: { title: "Mate : essais de PR", prerelease: true },
};

/** Release GitHub d'un tag (`latest` ou `essais`), pilotée par la CLI `gh`. */
function release(repo, tag) {
  if (!/^[\w.-]+\/[\w.-]+$/.test(repo))
    throw new Error(`UPDATES_REPO « ${repo} » : attendu propriétaire/dépôt`);

  const gh = (args) =>
    execFileSync("gh", [...args, "-R", repo], {
      encoding: "utf8",
      stdio: ["ignore", "pipe", "pipe"],
      maxBuffer: 1 << 28,
    });
  const notFound = (e) => /release not found|not found/i.test(`${e.stderr}`);

  /** Noms des assets, null si la release n'existe pas. */
  function assets() {
    try {
      return JSON.parse(
        gh(["release", "view", tag, "--json", "assets"]),
      ).assets.map((a) => a.name);
    } catch (e) {
      if (notFound(e)) return null;
      throw e;
    }
  }

  const download = (name) =>
    JSON.parse(
      gh(["release", "download", tag, "--pattern", name, "--output", "-"]),
    );

  return {
    /**
     * Manifest déjà publié, null s'il n'y en a pas. Absent mais copie présente (coupure pendant son envoi : --clobber
     * l'a déjà effacé) : la copie, sinon la publication suivante repartirait de rien et `prune` effacerait tous les essais.
     */
    manifest() {
      const names = assets();
      if (names?.includes("manifest.json")) return download("manifest.json");
      if (names?.includes(BACKUP)) return download(BACKUP);
      return null;
    },
    /**
     * Envoie `files` (chemins), une copie du manifest puis `manifestPath` : le manifest ne désigne jamais un fichier
     * absent, et l'un des deux existe toujours (`--clobber` efface avant d'envoyer).
     */
    publish(files, manifestPath) {
      if (assets() === null)
        gh([
          "release",
          "create",
          tag,
          "--title",
          TAGS[tag].title,
          "--notes",
          "Fichiers lus par les tablettes Mate (scripts/updates-publish.mjs) : ne pas modifier à la main.",
          ...(TAGS[tag].prerelease ? ["--prerelease"] : []),
          "--latest=false",
        ]);
      if (files.length) gh(["release", "upload", tag, ...files, "--clobber"]);
      const backup = path.join(path.dirname(manifestPath), BACKUP);
      fs.copyFileSync(manifestPath, backup);
      gh(["release", "upload", tag, backup, "--clobber"]);
      gh(["release", "upload", tag, manifestPath, "--clobber"]);
    },
    /** Supprime les assets que `keep` ne nomme pas (la copie du manifest reste). */
    prune(keep) {
      for (const name of assets() ?? []) {
        if (keep.has(name) || name === BACKUP) continue;
        gh(["release", "delete-asset", tag, name, "--yes"]);
        console.log(`supprimé : ${name}`);
      }
      console.log(`publié : ${repo} ${tag}`);
    },
  };
}

/** La PR n'est plus ouverte (fusionnée ou fermée) ; une PR illisible reste (dite sur stderr). */
function prClosed(repo, number) {
  try {
    const state = execFileSync(
      "gh",
      [
        "pr",
        "view",
        String(number),
        "--json",
        "state",
        "--jq",
        ".state",
        "-R",
        repo,
      ],
      { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] },
    ).trim();
    return state !== "OPEN";
  } catch (e) {
    console.error(`PR #${number} illisible, essai gardé : ${e.stderr}`.trim());
    return false;
  }
}

/**
 * Archives dist/backend-<os>.tar.gz : une par plateforme ou aucune, sinon arrêt. `add(fichier publié, contenu)` ;
 * renvoie leurs entrées {os, file, size, sha256, version}.
 */
function backendArchives(distDir, version, add) {
  const backends = [];
  for (const os of BACKEND_PLATFORMS) {
    const archive = path.join(distDir, `backend-${os}.tar.gz`);
    if (!fs.existsSync(archive)) continue;
    const data = fs.readFileSync(archive);
    const file = `backend-${version}-${os}.tar.gz`;
    add(file, data);
    backends.push({ os, file, ...fingerprint(data), version });
  }
  if (backends.length && backends.length !== BACKEND_PLATFORMS.length) {
    console.error(
      `${distDir} : une archive backend par plateforme (${BACKEND_PLATFORMS.join(", ")}) ou aucune`,
    );
    process.exit(1);
  }
  return backends;
}

function writeOut(outDir, files) {
  fs.rmSync(outDir, { recursive: true, force: true });
  fs.mkdirSync(outDir, { recursive: true });
  for (const [name, content] of Object.entries(files))
    fs.writeFileSync(path.join(outDir, name), content);
}

const arg = process.argv.slice(2);
const mode =
  arg[0] === "--essai"
    ? "essai"
    : arg[0] === "--retire"
      ? "retire"
      : arg[0] === "--purge-fermees"
        ? "purge"
        : "release";
if (mode !== "release") arg.shift();
const pr =
  mode === "essai" || mode === "retire" ? Number(arg.shift()) : undefined;
if (pr !== undefined && !(Number.isInteger(pr) && pr > 0)) usage();
const repo = process.env.UPDATES_REPO;

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
    installers.push({
      os,
      file,
      ...fingerprint(files[file]),
      version: entry.versionName,
    });
  }
  const backends = backendArchives(
    distDir,
    entry.versionName,
    (file, data) => (files[file] = data),
  );
  const manifest =
    JSON.stringify(
      {
        updates: [update],
        ...(installers.length ? { installers } : {}),
        ...(backends.length ? { backends } : {}),
      },
      null,
      2,
    ) + "\n";
  writeOut(outDir, { ...files, "manifest.json": manifest });
  console.log(JSON.stringify(update));
  if (installers.length)
    console.log(`installeurs : ${installers.map((i) => i.file).join(", ")}`);
  if (backends.length)
    console.log(`backends : ${backends.map((b) => b.file).join(", ")}`);
  if (repo) {
    const rel = release(repo, "latest");
    rel.publish(
      Object.keys(files).map((f) => path.join(outDir, f)),
      path.join(outDir, "manifest.json"),
    );
    rel.prune(new Set(["manifest.json", ...Object.keys(files)]));
  }
} else {
  if (!repo) {
    console.error("UPDATES_REPO : dépôt attendu (propriétaire/dépôt)");
    process.exit(1);
  }
  const rel = release(repo, "essais");
  const published = rel.manifest()?.essais ?? [];
  const gone =
    mode === "purge"
      ? new Set(published.filter((e) => prClosed(repo, e.mr)).map((e) => e.mr))
      : new Set([pr]);
  const essais = published.filter((e) => !gone.has(e.mr));
  const files = {};
  let outDir;
  if (mode === "essai") {
    let distDir;
    [distDir, outDir] = arg;
    if (!distDir || !outDir) usage();
    const entry = readEntry(distDir, true);
    const apk = fs.readFileSync(path.join(distDir, entry.file));
    files[entry.file] = apk;
    const backend = {};
    for (const { os, file, size, sha256 } of backendArchives(
      distDir,
      entry.versionName,
      (file, data) => (files[file] = data),
    )) {
      backend[os] = { file, size, sha256 };
    }
    const title = (process.env.PR_TITLE ?? "").trim().slice(0, MAX_TITLE);
    essais.push({
      mr: pr,
      title,
      ...entry,
      ...fingerprint(apk),
      ...(Object.keys(backend).length ? { backend } : {}),
    });
    essais.sort((a, b) => a.mr - b.mr);
    console.log(JSON.stringify(essais.find((e) => e.mr === pr)));
  } else {
    [outDir] = arg;
    if (!outDir) usage();
  }
  const manifest = JSON.stringify({ essais }, null, 2) + "\n";
  writeOut(outDir, { ...files, "manifest.json": manifest });
  rel.publish(
    Object.keys(files).map((f) => path.join(outDir, f)),
    path.join(outDir, "manifest.json"),
  );
  if (mode !== "essai") {
    const retired = published
      .filter((e) => gone.has(e.mr))
      .map((e) => `#${e.mr}`);
    console.log(
      mode === "purge"
        ? `essais retirés (PR fermées) : ${retired.join(", ") || "aucun"} (${essais.length} restant(s))`
        : `PR #${pr} retirée des essais (${essais.length} restant(s))`,
    );
  }
  const keep = new Set(["manifest.json"]);
  for (const e of essais) {
    keep.add(e.file);
    for (const b of Object.values(e.backend ?? {})) keep.add(b.file);
  }
  rel.prune(keep);
}
