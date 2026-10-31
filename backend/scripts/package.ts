import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { build } from "esbuild";
import { bundledPackages, noticeOf, thirdPartyNotices } from "./licences.ts";
import { WEB_FILES } from "../src/web-style.ts";

/**
 * Appli portable du logiciel de l'ordinateur, sans installation ni Node à installer : `npm run package` (Mac ou Linux,
 * dont la CI).
 *
 * Chacune embarque le serveur regroupé en un fichier (dist/server.mjs), les fichiers lus à l'exécution (config,
 * MateHelper.exe…), l'extension du navigateur et le node officiel de nodejs.org (signé par l'OpenJS Foundation),
 * vérifié par sa somme SHA-256. Au premier lancement, le runtime est extrait dans le dossier de l'utilisateur.
 *
 *   ../dist/Mate-Windows.exe   le lanceur (src/win/Mate.exe) suivi du zip du runtime, node.exe x64
 *   ../dist/Mate-Mac.zip       « Mate.app » (portable/mac/), node Apple Silicon et Intel
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
const FOLDER = "Mate";
const VERSION = /^v\d+\.\d+\.\d+$/.test(process.env.CI_COMMIT_TAG ?? "") ? process.env.CI_COMMIT_TAG!.slice(1) : "0.0.0";

/** Fichiers de config livrés : pas deck.json ni tasks.json (données de l'ordinateur, créées sur place). */
const CONFIG = [
  "accounts.json",
  "calls.json",
  "confidentialite.txt",
  "deck.example.json",
  "deck.example.windows.json",
  "tasks.example.json",
];

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

function member(archive: { file: string; dir: string }, inner: string): Buffer {
  const name = `${archive.dir}/${inner}`;
  return archive.file.endsWith(".zip")
    ? execFileSync("unzip", ["-p", archive.file, name], { maxBuffer: 512 * 1024 * 1024 })
    : execFileSync("tar", ["-xzOf", archive.file, name], { maxBuffer: 512 * 1024 * 1024 });
}

function extract(archive: { file: string; dir: string }, inner: string, dest: string) {
  fs.mkdirSync(path.dirname(dest), { recursive: true });
  fs.writeFileSync(dest, member(archive, inner), { mode: 0o755 });
}

function copy(from: string, to: string) {
  fs.mkdirSync(path.dirname(to), { recursive: true });
  fs.copyFileSync(from, to);
  fs.chmodSync(to, fs.statSync(from).mode);
}

const xml = (s: string) => s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");

/** Info.plist de l'appli Mac. */
function infoPlist(): string {
  const keys: [string, string | boolean][] = [
    ["CFBundleName", FOLDER],
    ["CFBundleDisplayName", FOLDER],
    ["CFBundleIdentifier", "app.mate.app"],
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
  // Mac : tout le compagnon dans l'appli. Windows : le runtime, zippé à la fin et ajouté à l'exe.
  const bundle = path.join(root, `${FOLDER}.app`, "Contents");
  const dest = platform === "windows" ? path.join(out, platform, "runtime") : path.join(bundle, "Resources", "backend");
  fs.rmSync(path.join(out, platform), { recursive: true, force: true });

  const built = await build({
    metafile: true,
    // mcp-bridge : pont stdio de Claude Desktop vers la porte MCP (routes/mcp.ts), lancé avec le node embarqué.
    entryPoints: { server: path.join(backend, "src", "server.ts"), "mcp-bridge": path.join(backend, "src", "mcp-bridge.ts") },
    outdir: path.join(dest, "dist"),
    outExtension: { ".js": ".mjs" },
    bundle: true,
    platform: "node",
    format: "esm",
    target: "node22",
    legalComments: "none",
    // Version renvoyée par /health : la tablette propose l'appli plus récente qu'elle garde ; le panneau la compare aussi.
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
  for (const f of WEB_FILES) copy(path.join(backend, "assets", "web", f), path.join(dest, "assets", "web", f));

  if (platform === "windows") {
    // Le lanceur n'est pas dans le runtime : c'est l'exe lui-même. Essai : pas de node, déjà sur l'ordinateur.
    copy(path.join(backend, "src", "win", "MateHelper.exe"), path.join(dest, "src", "win", "MateHelper.exe"));
    copy(path.join(backend, "scripts", "firewall-windows.ps1"), path.join(dest, "scripts", "firewall-windows.ps1"));
    copy(path.join(backend, "scripts", "capteurs-windows.ps1"), path.join(dest, "scripts", "capteurs-windows.ps1"));
    for (const f of fs.readdirSync(path.join(backend, "src", "win", "MateCapteurs"))) {
      copy(path.join(backend, "src", "win", "MateCapteurs", f), path.join(dest, "src", "win", "MateCapteurs", f));
    }
    if (!essai) extract(await nodeArchive(sums, "-win-x64.zip"), "node.exe", path.join(dest, "node", "node.exe"));
  } else {
    copy(path.join(backend, "src", "mac", "NowPlaying.dylib"), path.join(dest, "src", "mac", "NowPlaying.dylib"));
    copy(path.join(backend, "src", "mac", "MateMenu"), path.join(dest, "src", "mac", "MateMenu"));
    if (!essai) {
      fs.mkdirSync(path.join(bundle, "MacOS"), { recursive: true });
      fs.writeFileSync(path.join(bundle, "Info.plist"), infoPlist());
      copy(path.join(backend, "portable", "mac", "launcher.sh"), path.join(bundle, "MacOS", FOLDER));
      copy(path.join(backend, "assets", "compagnon.icns"), path.join(bundle, "Resources", "compagnon.icns"));
      for (const arch of ["arm64", "x64"]) {
        extract(await nodeArchive(sums, `-darwin-${arch}.tar.gz`), "bin/node", path.join(dest, "node", `darwin-${arch}`, "node"));
      }
    }
  }

  // Mentions de licences (scripts/licences.ts) : paquets regroupés par esbuild et node embarqué (même licence sur Windows
  // et Mac : celle de l'archive Windows). Dans le runtime (servi par la page locale) et, sur Mac, à côté de l'appli.
  const packages = bundledPackages(Object.keys(built.metafile.inputs)).map((dir) => noticeOf(path.join(backend, dir)));
  const node = essai
    ? undefined
    : { version: NODE_VERSION, license: member(await nodeArchive(sums, "-win-x64.zip"), "LICENSE").toString("utf8") };
  const notices = thirdPartyNotices(packages, node);
  fs.writeFileSync(path.join(dest, "LICENCES-TIERS.txt"), notices);
  if (!essai && platform === "mac") fs.writeFileSync(path.join(root, "LICENCES-TIERS.txt"), notices);

  if (essai) {
    const archive = path.join(out, `backend-${platform}.tar.gz`);
    fs.rmSync(archive, { force: true });
    // COPYFILE_DISABLE : pas de fichiers ._* du Mac dans l'archive.
    execFileSync("tar", ["-czf", archive, "-C", dest, "."], { env: { ...process.env, COPYFILE_DISABLE: "1" } });
    fs.rmSync(path.join(out, platform), { recursive: true, force: true });
    return archive;
  }

  // Repère du paquet : le lanceur extrait (Windows) ou recopie (Mac) le runtime quand il diffère de celui en place.
  fs.writeFileSync(path.join(dest, "runtime.stamp"), `${VERSION}+${new Date().toISOString()}\n`);

  // Extension du navigateur (onglets et page Média sur la tablette) : dans le runtime (chemin stable), à charger en mode développeur.
  for (const f of fs.readdirSync(path.join(backend, "extension")))
    copy(path.join(backend, "extension", f), path.join(dest, "extension", f));

  if (platform === "windows") {
    // zip (Info-ZIP) du runtime, ajouté au lanceur : [exe][zip][taille du zip sur 8 octets][MATEZIP1] (src/win/launcher/Program.cs).
    const zip = path.join(out, platform, "runtime.zip");
    execFileSync("zip", ["-r", "-q", "-X", zip, ".", "-x", "*.DS_Store"], { cwd: dest });
    const payload = fs.readFileSync(zip);
    const trailer = Buffer.alloc(16);
    trailer.writeBigInt64LE(BigInt(payload.length), 0);
    trailer.write("MATEZIP1", 8, "ascii");
    const exe = path.join(out, "Mate-Windows.exe");
    fs.rmSync(exe, { force: true });
    fs.writeFileSync(exe, Buffer.concat([fs.readFileSync(path.join(backend, "src", "win", "Mate.exe")), payload, trailer]));
    return exe;
  }

  // zip (Info-ZIP) : lisible par le Finder, droits d'exécution gardés, sans __MACOSX.
  const zip = path.join(out, "Mate-Mac.zip");
  fs.rmSync(zip, { force: true });
  execFileSync("zip", ["-r", "-q", zip, FOLDER, "-x", "*.DS_Store"], { cwd: path.join(out, platform) });
  return zip;
}

async function main() {
  if (process.platform === "win32") throw new Error("À lancer sur le Mac ou sous Linux (zip, unzip, tar)");
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
