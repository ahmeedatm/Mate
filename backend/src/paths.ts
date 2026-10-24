import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

/**
 * Dossiers du compagnon, sans effet de bord : partagés par le serveur (config.ts) et le lancement
 * automatique (scripts/service.ts). Dans le paquet, les deux sont regroupés dans dist/*.mjs.
 */

export const backendRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
/** Paquet client (npm run package) : serveur regroupé dans dist/server.mjs, node embarqué. */
export const packaged = import.meta.url.endsWith(".mjs");

/** Données du paquet hors du dossier extrait : une nouvelle version (autre dossier) les retrouve. */
function appDataDir() {
  return process.platform === "win32"
    ? path.join(process.env.LOCALAPPDATA ?? path.join(os.homedir(), "AppData", "Local"), "UnowhyCompanion", "data")
    : path.join(os.homedir(), "Library", "Application Support", "UnowhyCompanion");
}

// DATA_DIR : autre dossier de données (tests : deux « ordinateurs » sur la même machine).
export const dataDir = path.resolve(process.env.DATA_DIR || (packaged ? appDataDir() : path.join(backendRoot, ".data")));

/**
 * Paquet installé avant les données hors du dossier (.data à côté du serveur) : on les recopie une fois,
 * sinon la tablette verrait un nouvel ordinateur. Le .data d'origine reste en place.
 */
export function adoptOldData() {
  const old = path.join(backendRoot, ".data");
  if (!packaged || process.env.DATA_DIR || old === dataDir) return;
  if (fs.existsSync(path.join(dataDir, "identity.json")) || !fs.existsSync(path.join(old, "identity.json"))) return;
  fs.cpSync(old, dataDir, { recursive: true, filter: (src) => path.basename(src) !== "service.pid" });
}
