import { execFileSync } from "node:child_process";
import { createHash, verify } from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { config } from "./config.ts";
import { backendRoot, packaged } from "./paths.ts";

/**
 * Essai du backend d'une PR : la tablette envoie l'archive du backend de la PR (npm run package -- essai : sans node
 * ni lanceur Windows), cet ordinateur garde une copie de son backend installé, le remplace, puis redémarre.
 * Une seule copie, celle d'origine : un essai suivant ne l'écrase pas, seul le retour à l'origine la supprime.
 * Mise à jour stable (applyUpdate) : même archive, celle d'une release ; elle remplace le backend installé, sans copie.
 * Compagnon installé par paquet seulement : lancé depuis le dépôt, il refuse (409).
 */

export type Layout = {
  /** Dossier du backend installé (dist/, src/, config/…). */
  root: string;
  dataDir: string;
  platform: "mac" | "windows";
  packaged: boolean;
  /**
   * Clé publique Ed25519 (PEM) de la CI du projet, livrée dans `config/backend-signature.pub` : seule une archive signée
   * par la clé privée de la CI (environnement « signature ») est posée. null : pas de clé, rien n'est accepté.
   */
  publicKey: string | null;
};

/** Clé publique de vérification, dans le backend installé (remplacée avec config/ par une archive signée). */
export const SIGNATURE_KEY = "config/backend-signature.pub";

const readPublicKey = (root: string) => {
  try {
    return fs.readFileSync(path.join(root, SIGNATURE_KEY), "utf8");
  } catch {
    return null;
  }
};

/** `sha256` : empreinte de l'archive posée, pour qu'une PR republiée soit renvoyée par la tablette. */
export type BackendState = { packaged: boolean; mode: "origin" | "essai"; mr: number | null; sha256: string | null };

const MARKER = "essai-backend.json";
const ORIGIN = "backend-origin";
/** Remplacés par l'essai. Pas node/ (verrouillé en marche) ni .env, .cache : à l'ordinateur. */
const MANAGED = ["dist", "src", "scripts", "assets", "config"];
/** Le lanceur Windows est verrouillé tant que le compagnon tourne. */
const LOCKED = ["src/win/Mate.exe"];
/** Données de l'ordinateur dans config/ : jamais copiées ni remplacées. */
const USER_FILES = ["config/deck.json", "config/tasks.json"];

const rel = (p: string) => p.split(path.sep).join("/");

function fail(message: string, statusCode: number): never {
  throw Object.assign(new Error(message), { statusCode });
}

export const defaultLayout = (): Layout => ({
  root: backendRoot,
  dataDir: config.dataDir,
  platform: process.platform === "win32" ? "windows" : "mac",
  packaged,
  publicKey: readPublicKey(backendRoot),
});

const serverSha = (layout: Layout) =>
  createHash("sha256")
    .update(fs.readFileSync(path.join(layout.root, "dist", "server.mjs")))
    .digest("hex");

/**
 * Essai en cours d'après la marque `essai-backend.json` (PR, empreinte de l'archive et du serveur posé). Serveur différent : le
 * compagnon a été réinstallé depuis (nouvel installeur), la marque et la copie d'origine sont périmées, on les oublie.
 */
export function backendState(layout: Layout = defaultLayout()): BackendState {
  const marker = path.join(layout.dataDir, MARKER);
  try {
    const { mr, sha256, server } = JSON.parse(fs.readFileSync(marker, "utf8")) as { mr: number; sha256?: string; server: string };
    if (server === serverSha(layout)) return { packaged: layout.packaged, mode: "essai", mr, sha256: sha256 ?? null };
    fs.rmSync(marker, { force: true });
    fs.rmSync(path.join(layout.dataDir, ORIGIN), { recursive: true, force: true });
  } catch {
    // Pas de marque (ou illisible) : backend d'origine.
  }
  return { packaged: layout.packaged, mode: "origin", mr: null, sha256: null };
}

/** Copie `from` dans `to` (fichiers de `skip` ignorés) ; `prune` : supprime de `to` ce qui n'est plus dans `from`. */
function sync(from: string, to: string, base: string, prune: boolean, skip: (r: string) => boolean) {
  fs.mkdirSync(to, { recursive: true });
  if (prune) {
    for (const name of fs.readdirSync(to)) {
      const r = rel(path.join(base, name));
      if (!skip(r) && !fs.existsSync(path.join(from, name))) fs.rmSync(path.join(to, name), { recursive: true, force: true });
    }
  }
  for (const entry of fs.readdirSync(from, { withFileTypes: true })) {
    const r = rel(path.join(base, entry.name));
    if (skip(r)) continue;
    const dest = path.join(to, entry.name);
    if (entry.isDirectory()) {
      if (fs.existsSync(dest) && !fs.statSync(dest).isDirectory()) fs.rmSync(dest, { force: true });
      sync(path.join(from, entry.name), dest, r, prune, skip);
    } else {
      fs.rmSync(dest, { recursive: true, force: true });
      fs.copyFileSync(path.join(from, entry.name), dest);
    }
  }
}

const skipped = (r: string) => LOCKED.includes(r) || USER_FILES.includes(r);

/** Fichiers gérés de `from` posés sur `to`. */
function replaceManaged(from: string, to: string) {
  for (const name of MANAGED) {
    if (fs.existsSync(path.join(from, name))) sync(path.join(from, name), path.join(to, name), name, name !== "config", skipped);
  }
}

/** Fichier propre à la plateforme : une archive de l'autre plateforme n'est jamais posée. */
const expected = (platform: Layout["platform"]) => (platform === "windows" ? "src/win/MateHelper.exe" : "src/mac/NowPlaying.dylib");

/** Revient au backend d'origine. Faux (rien fait) si l'ordinateur n'est pas sur un essai. */
export function restoreOrigin(layout: Layout = defaultLayout()): boolean {
  if (backendState(layout).mode === "origin") return false;
  const copy = path.join(layout.dataDir, ORIGIN);
  if (!fs.existsSync(path.join(copy, "dist"))) fail("La copie du backend d'origine est introuvable : réinstalle le compagnon.", 500);
  replaceManaged(copy, layout.root);
  fs.rmSync(path.join(layout.dataDir, MARKER), { force: true });
  fs.rmSync(copy, { recursive: true, force: true });
  return true;
}

const UNSIGNED = "Cette archive n'est pas signée par Mate : rien n'est changé.";
/** Compagnon installé avant la signature : la première clé ne peut arriver que par l'installeur, jamais par une archive. */
const NO_KEY = "Ce compagnon ne sait pas encore vérifier les mises à jour : réinstalle-le une fois avec l'installeur.";

/** Clés publiques d'un fichier PEM : plusieurs pendant un changement de clé (l'ancienne et la nouvelle). */
export const publicKeys = (pem: string) => pem.match(/-----BEGIN PUBLIC KEY-----[\s\S]*?-----END PUBLIC KEY-----/g) ?? [];

/**
 * Signature Ed25519 (`signature` : base64, en-tête `X-Mate-Signature`) de l'archive entière par la clé privée de la CI,
 * vérifiée avec les clés de `publicKey` : une seule suffit (changement de clé, docs/developpement.md). L'empreinte
 * SHA-256 seule ne prouve rien : elle vient du même expéditeur que l'archive. Erreur 403 si le compagnon n'a pas de clé
 * ([NO_KEY]), ou si la signature manque, est mal formée ou ne correspond à aucune clé ([UNSIGNED]).
 */
export function checkSignature(archive: Buffer, signature: string | undefined, publicKey: string | null): void {
  const keys = publicKeys(publicKey ?? "");
  if (keys.length === 0) fail(NO_KEY, 403);
  // Base64 stricte : Buffer.from ignore en silence les caractères invalides. Une signature Ed25519 fait 64 octets.
  if (!signature || !/^[A-Za-z0-9+/]{85}[AQgw]==$/.test(signature)) fail(UNSIGNED, 403);
  const bytes = Buffer.from(signature, "base64");
  const valid = keys.some((key) => {
    try {
      return verify(null, archive, key, bytes);
    } catch {
      // Clé illisible : elle ne valide rien.
      return false;
    }
  });
  if (!valid) fail(UNSIGNED, 403);
}

/**
 * Archive vérifiée (SHA-256 si donné, signature de la CI, fichiers de la plateforme) et extraite dans `tmp` ; renvoie le
 * dossier extrait. Rien n'est touché dans l'installation ; rien n'est écrit sur le disque avant la signature.
 */
function extract(
  archive: Buffer,
  sha256: string | undefined,
  signature: string | undefined,
  layout: Layout,
  tmp: string,
): { files: string; received: string } {
  const received = createHash("sha256").update(archive).digest("hex");
  if (sha256 && received !== sha256.toLowerCase()) {
    fail("L'archive du backend reçue est abîmée (SHA-256 différent) : rien n'est changé.", 400);
  }
  checkSignature(archive, signature, layout.publicKey);
  fs.writeFileSync(path.join(tmp, "backend.tar.gz"), archive);
  fs.mkdirSync(path.join(tmp, "backend"));
  // bsdtar sur Mac et Windows 10 : chemins relatifs (une lettre de lecteur serait prise pour un hôte par GNU tar).
  try {
    execFileSync("tar", ["-xzf", "backend.tar.gz", "-C", "backend"], { cwd: tmp, stdio: "ignore" });
  } catch {
    fail("L'archive du backend est illisible : rien n'est changé.", 400);
  }
  const files = path.join(tmp, "backend");
  if (!fs.existsSync(path.join(files, "dist", "server.mjs")) || !fs.existsSync(path.join(files, expected(layout.platform)))) {
    fail("Cette archive n'est pas un backend pour cet ordinateur : rien n'est changé.", 400);
  }
  return { files, received };
}

const notPackaged = (what: string) =>
  fail(`Ce compagnon est lancé depuis le dépôt : ${what} marche seulement avec le compagnon installé par paquet.`, 409);

/**
 * Remplace le backend par celui de la PR `mr`. Archive vérifiée (SHA-256 si donné, signature de la CI, fichiers attendus) et extraite à
 * part avant de toucher à quoi que ce soit ; un échec en cours de remplacement remet le backend d'origine.
 * L'appelant redémarre le compagnon.
 */
export function applyEssai(
  archive: Buffer,
  mr: number,
  sha256: string | undefined,
  signature: string | undefined,
  layout: Layout = defaultLayout(),
) {
  if (!layout.packaged) notPackaged("l'essai du backend");
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "companion-essai-"));
  try {
    const { files, received } = extract(archive, sha256, signature, layout, tmp);

    // Copie d'origine : prise à la première bascule seulement (pas d'essai en cours), jamais écrasée par un essai.
    const copy = path.join(layout.dataDir, ORIGIN);
    if (backendState(layout).mode === "origin") {
      fs.rmSync(copy, { recursive: true, force: true });
      replaceManaged(layout.root, copy);
    }
    try {
      replaceManaged(files, layout.root);
    } catch (e) {
      try {
        replaceManaged(copy, layout.root);
      } catch {
        // Le premier échec est celui qu'on remonte.
      }
      throw e;
    }
    fs.mkdirSync(layout.dataDir, { recursive: true });
    fs.writeFileSync(path.join(layout.dataDir, MARKER), JSON.stringify({ mr, sha256: received, server: serverSha(layout) }) + "\n");
  } finally {
    fs.rmSync(tmp, { recursive: true, force: true });
  }
}

/** [X, Y, Z] d'une version X.Y.Z, null sinon (« dev », vide). */
export function versionNumbers(version: string | undefined): number[] | null {
  const m = /^(\d+)\.(\d+)\.(\d+)$/.exec(version ?? "");
  return m ? m.slice(1).map(Number) : null;
}

const newer = (a: number[], b: number[]) => a[0] - b[0] || a[1] - b[1] || a[2] - b[2];

/**
 * Mise à jour stable : remplace le backend installé par celui de la release `version` (archive de la release, sans node
 * ni lanceur). SHA-256 et signature de la CI exigées. Version pas plus récente que `current` (celle qui tourne) : refus (409). Un essai de PR
 * posé est oublié (marque et copie d'origine) : la nouvelle version devient l'origine. Copie temporaire de l'installation
 * pendant le remplacement, remise si une copie échoue. L'appelant redémarre le compagnon.
 */
export function applyUpdate(
  archive: Buffer,
  sha256: string,
  signature: string | undefined,
  version: string,
  current: string | undefined = process.env.COMPANION_VERSION,
  layout: Layout = defaultLayout(),
) {
  if (!layout.packaged) notPackaged("la mise à jour depuis la tablette");
  const target = versionNumbers(version);
  if (!target) fail(`Version « ${version} » invalide : X.Y.Z attendu.`, 400);
  const running = versionNumbers(current);
  if (running && newer(target, running) <= 0) fail(`Ce compagnon est déjà en version ${current} : rien n'est changé.`, 409);
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "companion-update-"));
  try {
    const { files } = extract(archive, sha256, signature, layout, tmp);
    const backup = path.join(tmp, "installed");
    replaceManaged(layout.root, backup);
    try {
      replaceManaged(files, layout.root);
    } catch (e) {
      try {
        replaceManaged(backup, layout.root);
      } catch {
        // Le premier échec est celui qu'on remonte.
      }
      throw e;
    }
    fs.rmSync(path.join(layout.dataDir, MARKER), { force: true });
    fs.rmSync(path.join(layout.dataDir, ORIGIN), { recursive: true, force: true });
  } finally {
    fs.rmSync(tmp, { recursive: true, force: true });
  }
}
