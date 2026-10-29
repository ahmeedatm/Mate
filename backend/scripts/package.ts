import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { build } from "esbuild";

/**
 * Installeurs du logiciel de l'ordinateur, sans Node à installer : `npm run package` (Mac ou Linux, dont la CI).
 *
 * Chacun embarque le serveur regroupé en un fichier (dist/server.mjs, et dist/service.mjs pour le lancement
 * automatique), les fichiers lus à l'exécution (config, UnowhyHelper.exe…), l'extension du navigateur et le node
 * officiel de nodejs.org (signé par l'OpenJS Foundation), vérifié par sa somme SHA-256.
 *
 *   ../dist/Compagnon-Unowhy-Windows.exe   installeur NSIS (installer/windows/compagnon.nsi, makensis requis), node.exe x64
 *   ../dist/Compagnon-Unowhy-Mac.zip       « Compagnon Unowhy.app » (installer/mac/), node Apple Silicon et Intel
 * `npm run package -- windows` ou `-- mac` : un seul des deux. Version : tag de la CI (CI_COMMIT_TAG), sinon 0.0.0.
 *
 * `npm run package -- essai` : seulement le backend de chaque plateforme, sans node ni lanceur Windows, en
 * ../dist/backend-windows.tar.gz et backend-mac.tar.gz. C'est ce que la tablette envoie à l'ordinateur pour essayer le
 * backend d'une MR (src/essai-backend.ts), ou, fait sur un tag (job package-backend), pour le mettre à jour vers la
 * release. Rien n'est téléchargé.
 */

// Version figée (celle de latest-v22.x au 26/10/2026) : deux paquets faits à des dates différentes embarquent le même node.
const NODE_VERSION = "v22.23.4";
const backend = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const repo = path.resolve(backend, "..");
const out = path.join(repo, "dist");
const cache = path.join(backend, ".cache", "node");
const FOLDER = "Compagnon Unowhy";
const VERSION = /^v\d+\.\d+\.\d+$/.test(process.env.CI_COMMIT_TAG ?? "") ? process.env.CI_COMMIT_TAG!.slice(1) : "0.0.0";

/** Fichiers de config livrés : pas deck.json ni tasks.json (données de l'ordinateur, créées sur place). */
const CONFIG = ["accounts.json", "calls.json", "deck.example.json", "deck.example.windows.json", "tasks.example.json"];

async function download(url: string): Promise<Buffer> {
  const res = await fetch(url);
  if (!res.ok) throw new Error(`${url} : HTTP ${res.status}`);
  return Buffer.from(await res.arrayBuffer());
}

/** Archive officielle de node, gardée dans .cache/node et vérifiée à chaque fois. */
async function nodeArchive(sums: string, suffix: string): Promise<{ file: string; dir: string }> {
  const line = sums.split("\n").find((l) => l.endsWith(suffix));
  if (!line) throw new Error(`Pas d'archive node « ${suffix} » dans ${NODE_VERSION}`);
  const [hash, name] = line.trim().split(/\s+/);
  const file = path.join(cache, name);
  if (!fs.existsSync(file)) {
    console.log(`Téléchargement de ${name}…`);
    fs.mkdirSync(cache, { recursive: true });
    fs.writeFileSync(file, await download(`https://nodejs.org/dist/${NODE_VERSION}/${name}`));
  }
  if (createHash("sha256").update(fs.readFileSync(file)).digest("hex") !== hash) {
    fs.rmSync(file);
    throw new Error(`${name} : somme SHA-256 incorrecte (fichier supprimé, relancer)`);
  }
  return { file, dir: name.replace(/\.(zip|tar\.gz)$/, "") };
}

function extract(archive: { file: string; dir: string }, inner: string, dest: string) {
  fs.mkdirSync(path.dirname(dest), { recursive: true });
  const member = `${archive.dir}/${inner}`;
  const data = archive.file.endsWith(".zip")
    ? execFileSync("unzip", ["-p", archive.file, member], { maxBuffer: 512 * 1024 * 1024 })
    : execFileSync("tar", ["-xzOf", archive.file, member], { maxBuffer: 512 * 1024 * 1024 });
  fs.writeFileSync(dest, data, { mode: 0o755 });
}

function copy(from: string, to: string) {
  fs.mkdirSync(path.dirname(to), { recursive: true });
  fs.copyFileSync(from, to);
  fs.chmodSync(to, fs.statSync(from).mode);
}

const xml = (s: string) => s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");

/** Info.plist de l'appli Mac. UnowhyBuild (date du paquet) : le lanceur réinstalle quand il change. */
function infoPlist(): string {
  const keys: [string, string | boolean][] = [
    ["CFBundleName", FOLDER],
    ["CFBundleDisplayName", FOLDER],
    ["CFBundleIdentifier", "com.unowhy.companion.app"],
    ["CFBundleExecutable", FOLDER],
    ["CFBundleIconFile", "compagnon"],
    ["CFBundlePackageType", "APPL"],
    ["CFBundleShortVersionString", VERSION],
    ["CFBundleVersion", VERSION],
    ["CFBundleDevelopmentRegion", "fr"],
    ["LSApplicationCategoryType", "public.app-category.utilities"],
    // node 22 : macOS 11 au moins.
    ["LSMinimumSystemVersion", "11.0"],
    ["NSHighResolutionCapable", true],
    ["UnowhyBuild", new Date().toISOString()],
  ];
  const body = keys.map(([k, v]) => `  <key>${k}</key>\n  ${typeof v === "boolean" ? `<${v}/>` : `<string>${xml(v)}</string>`}`).join("\n");
  return `<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
${body}
</dict>
</plist>
`;
}

async function stage(platform: "windows" | "mac", sums: string, essai = false): Promise<string> {
  const root = path.join(out, platform, FOLDER);
  // Mac : tout le compagnon dans l'appli ; l'extension reste à côté (le Finder n'ouvre pas une appli pour y choisir un dossier).
  const bundle = path.join(root, `${FOLDER}.app`, "Contents");
  const dest = platform === "windows" ? path.join(root, "backend") : path.join(bundle, "Resources", "backend");
  fs.rmSync(path.join(out, platform), { recursive: true, force: true });

  await build({
    entryPoints: { server: path.join(backend, "src", "server.ts"), service: path.join(backend, "scripts", "service.ts") },
    outdir: path.join(dest, "dist"),
    outExtension: { ".js": ".mjs" },
    bundle: true,
    platform: "node",
    format: "esm",
    target: "node22",
    legalComments: "none",
    // Version renvoyée par /health : la tablette propose l'installeur plus récent qu'elle garde.
    define: { "process.env.COMPANION_VERSION": JSON.stringify(VERSION) },
    logLevel: "warning",
    // Modules CommonJS regroupés dans un fichier ESM : ils attendent require, __filename et __dirname.
    banner: {
      js: [
        'import { createRequire as __cr } from "node:module";',
        'import { fileURLToPath as __fu } from "node:url";',
        'import { dirname as __dn } from "node:path";',
        "const require = __cr(import.meta.url);",
        "const __filename = __fu(import.meta.url);",
        "const __dirname = __dn(__filename);",
      ].join("\n"),
    },
  });

  for (const f of CONFIG) copy(path.join(backend, "config", f), path.join(dest, "config", f));

  if (platform === "windows") {
    // Essai : ni le lanceur (verrouillé tant que le compagnon tourne) ni node, déjà sur l'ordinateur.
    for (const f of essai ? ["UnowhyHelper.exe"] : ["UnowhyHelper.exe", "UnowhyCompanion.exe"])
      copy(path.join(backend, "src", "win", f), path.join(dest, "src", "win", f));
    copy(path.join(backend, "scripts", "firewall-windows.ps1"), path.join(dest, "scripts", "firewall-windows.ps1"));
    copy(path.join(backend, "assets", "compagnon.ico"), path.join(dest, "assets", "compagnon.ico"));
    if (!essai) extract(await nodeArchive(sums, "-win-x64.zip"), "node.exe", path.join(dest, "node", "node.exe"));
  } else {
    copy(path.join(backend, "src", "mac", "NowPlaying.dylib"), path.join(dest, "src", "mac", "NowPlaying.dylib"));
    if (!essai) {
      fs.mkdirSync(path.join(bundle, "MacOS"), { recursive: true });
      fs.writeFileSync(path.join(bundle, "Info.plist"), infoPlist());
      copy(path.join(backend, "installer", "mac", "launcher.sh"), path.join(bundle, "MacOS", FOLDER));
      copy(path.join(backend, "assets", "compagnon.icns"), path.join(bundle, "Resources", "compagnon.icns"));
      for (const arch of ["arm64", "x64"]) {
        extract(await nodeArchive(sums, `-darwin-${arch}.tar.gz`), "bin/node", path.join(dest, "node", `darwin-${arch}`, "node"));
      }
    }
  }

  if (essai) {
    const archive = path.join(out, `backend-${platform}.tar.gz`);
    fs.rmSync(archive, { force: true });
    // COPYFILE_DISABLE : pas de fichiers ._* du Mac dans l'archive.
    execFileSync("tar", ["-czf", archive, "-C", dest, "."], { env: { ...process.env, COPYFILE_DISABLE: "1" } });
    fs.rmSync(path.join(out, platform), { recursive: true, force: true });
    return archive;
  }

  // Extension du navigateur (onglets et page Média sur la tablette) : à charger depuis ce dossier, mode développeur.
  for (const f of fs.readdirSync(path.join(backend, "extension")))
    copy(path.join(backend, "extension", f), path.join(root, "Extension navigateur", f));

  if (platform === "windows") {
    const exe = path.join(out, "Compagnon-Unowhy-Windows.exe");
    fs.rmSync(exe, { force: true });
    const nsi = path.join(backend, "installer", "windows", "compagnon.nsi");
    try {
      execFileSync("makensis", ["-V2", "-INPUTCHARSET", "UTF8", `-DSOURCE=${root}`, `-DOUTFILE=${exe}`, `-DVERSION=${VERSION}`, nsi], {
        stdio: "inherit",
      });
    } catch (e) {
      if ((e as NodeJS.ErrnoException).code === "ENOENT")
        throw new Error("makensis introuvable : brew install makensis (Mac), apt-get install nsis (Linux)");
      throw new Error("makensis a échoué (voir plus haut)");
    }
    return exe;
  }

  // zip (Info-ZIP) : lisible par le Finder, droits d'exécution gardés, sans __MACOSX.
  const zip = path.join(out, "Compagnon-Unowhy-Mac.zip");
  fs.rmSync(zip, { force: true });
  execFileSync("zip", ["-r", "-q", zip, FOLDER, "-x", "*.DS_Store"], { cwd: path.join(out, platform) });
  return zip;
}

async function main() {
  if (process.platform === "win32") throw new Error("À lancer sur le Mac ou sous Linux (zip, unzip, tar, makensis)");
  // Un job CI par plateforme : artefacts sous la limite de 100 Mo de GitLab.
  const only = process.argv[2];
  const essai = only === "essai";
  if (only && !essai && only !== "windows" && only !== "mac") throw new Error(`Plateforme « ${only} » : windows, mac ou essai`);
  const sums = essai ? "" : (await download(`https://nodejs.org/dist/${NODE_VERSION}/SHASUMS256.txt`)).toString("utf8");
  if (!essai) console.log(`Node : ${sums.match(/node-(v[\d.]+)-/)?.[1] ?? "?"}`);
  for (const platform of (["windows", "mac"] as const).filter((p) => essai || !only || p === only)) {
    const file = await stage(platform, sums, essai);
    console.log(`✓ ${path.relative(repo, file)} (${Math.round(fs.statSync(file).size / 1e6)} Mo)`);
  }
}

main().catch((e: Error) => {
  console.error(e.message);
  process.exit(1);
});
