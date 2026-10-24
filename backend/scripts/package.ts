import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { build } from "esbuild";

/**
 * Paquets du logiciel de l'ordinateur, sans Node à installer : `npm run package` (sur le Mac).
 *
 * Chaque zip contient l'installeur à double-cliquer, le serveur regroupé en un fichier (dist/server.mjs, et
 * dist/service.mjs pour le lancement automatique), les fichiers lus à l'exécution (config, helper.ps1…) et
 * le node officiel de nodejs.org (signé par l'OpenJS Foundation), vérifié par sa somme SHA-256.
 *
 *   ../dist/Compagnon-Unowhy-Windows.zip   node.exe (x64)
 *   ../dist/Compagnon-Unowhy-Mac.zip       node pour Apple Silicon et Intel, choisi par l'installeur
 */

const NODE_LINE = "latest-v22.x";
const backend = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const repo = path.resolve(backend, "..");
const out = path.join(repo, "dist");
const cache = path.join(backend, ".cache", "node");
const FOLDER = "Compagnon Unowhy";

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
  if (!line) throw new Error(`Pas d'archive node « ${suffix} » dans ${NODE_LINE}`);
  const [hash, name] = line.trim().split(/\s+/);
  const file = path.join(cache, name);
  if (!fs.existsSync(file)) {
    console.log(`Téléchargement de ${name}…`);
    fs.mkdirSync(cache, { recursive: true });
    fs.writeFileSync(file, await download(`https://nodejs.org/dist/${NODE_LINE}/${name}`));
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

async function stage(platform: "windows" | "mac", sums: string): Promise<string> {
  const root = path.join(out, platform, FOLDER);
  const dest = path.join(root, "backend");
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
    copy(path.join(repo, "Installer (Windows).cmd"), path.join(root, "Installer (Windows).cmd"));
    for (const f of ["helper.ps1", "native.cs"]) copy(path.join(backend, "src", "win", f), path.join(dest, "src", "win", f));
    for (const f of ["run-windows.ps1", "firewall-windows.ps1"]) copy(path.join(backend, "scripts", f), path.join(dest, "scripts", f));
    copy(path.join(backend, "assets", "compagnon.ico"), path.join(dest, "assets", "compagnon.ico"));
    // Extension du navigateur (onglets sur la tablette) : à charger depuis ce dossier, mode développeur.
    for (const f of fs.readdirSync(path.join(backend, "extension"))) copy(path.join(backend, "extension", f), path.join(root, "Extension navigateur", f));
    extract(await nodeArchive(sums, "-win-x64.zip"), "node.exe", path.join(dest, "node", "node.exe"));
  } else {
    copy(path.join(repo, "Installer (Mac).command"), path.join(root, "Installer (Mac).command"));
    for (const arch of ["arm64", "x64"]) {
      extract(await nodeArchive(sums, `-darwin-${arch}.tar.gz`), "bin/node", path.join(dest, "node", `darwin-${arch}`, "node"));
    }
  }

  // ditto : zip lisible par l'Explorateur de Windows et le Finder, droits d'exécution gardés, sans __MACOSX.
  const zip = path.join(out, `Compagnon-Unowhy-${platform === "windows" ? "Windows" : "Mac"}.zip`);
  fs.rmSync(zip, { force: true });
  execFileSync("ditto", ["-c", "-k", "--norsrc", "--keepParent", root, zip]);
  return zip;
}

async function main() {
  if (process.platform !== "darwin") throw new Error("À lancer sur le Mac (ditto, unzip, tar)");
  const sums = (await download(`https://nodejs.org/dist/${NODE_LINE}/SHASUMS256.txt`)).toString("utf8");
  console.log(`Node : ${sums.match(/node-(v[\d.]+)-/)?.[1] ?? "?"}`);
  for (const platform of ["windows", "mac"] as const) {
    const zip = await stage(platform, sums);
    console.log(`✓ ${path.relative(repo, zip)} (${Math.round(fs.statSync(zip).size / 1e6)} Mo)`);
  }
}

main().catch((e: Error) => {
  console.error(e.message);
  process.exit(1);
});
