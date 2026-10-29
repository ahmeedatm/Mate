import { execFileSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

/**
 * Dossiers du compagnon, partagés par le serveur (config.ts) et le lancement automatique (scripts/service.ts).
 * Dans le paquet, les deux sont regroupés dans dist/*.mjs. Seul config.ts appelle les recopies d'un ancien paquet.
 */

export const backendRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
/** Paquet client (npm run package) : serveur regroupé dans dist/server.mjs, node embarqué. */
export const packaged = import.meta.url.endsWith(".mjs");

/** Données du paquet hors du dossier extrait : une nouvelle version (autre dossier) les retrouve. */
function appDataDir(name = "UnoMate") {
  return process.platform === "win32"
    ? path.join(process.env.LOCALAPPDATA ?? path.join(os.homedir(), "AppData", "Local"), name, "data")
    : path.join(os.homedir(), "Library", "Application Support", name);
}

// DATA_DIR : autre dossier de données (tests : deux « ordinateurs » sur la même machine) ; « paquet » = celles du
// paquet installé, sur Mac comme sur Windows (serveur MCP lancé depuis le dépôt, .mcp.json).
const envDataDir = process.env.DATA_DIR === "paquet" ? appDataDir() : process.env.DATA_DIR;
export const dataDir = path.resolve(envDataDir || (packaged ? appDataDir() : path.join(backendRoot, ".data")));

/**
 * Données d'un paquet d'avant, recopiées une fois, sinon la tablette verrait un nouvel ordinateur (autre identité,
 * appairage perdu) et l'agenda, les tâches, les touches et les connexions seraient perdus : dossier d'avant le
 * renommage UnoMate (« UnowhyCompanion », 29/10/2026, mis à jour en place par la tablette), puis .data à côté du
 * serveur (paquet d'avant les données hors du dossier). Les originaux restent en place.
 */
export function adoptOldData() {
  if (!packaged || process.env.DATA_DIR) return;
  for (const old of [appDataDir("UnowhyCompanion"), path.join(backendRoot, ".data")]) if (adoptData(old, dataDir)) return;
}

/** Recopie `from` dans `to` si `to` n'a pas encore d'identité et que `from` en a une ; vrai si recopié. */
export function adoptData(from: string, to: string): boolean {
  if (path.resolve(from) === path.resolve(to)) return false;
  if (fs.existsSync(path.join(to, "identity.json")) || !fs.existsSync(path.join(from, "identity.json"))) return false;
  fs.cpSync(from, to, { recursive: true, filter: (src) => path.basename(src) !== "service.pid" });
  return true;
}

/**
 * Dossier de l'utilisateur (Personal = Documents, Desktop = Bureau, Programs = menu Démarrer) : sous Windows,
 * lu dans le registre car il peut avoir été déplacé (OneDrive par exemple).
 */
export function userFolder(kind: "Personal" | "Desktop" | "Programs", fallback: string): string {
  if (process.platform === "win32") {
    try {
      const out = execFileSync(
        "reg",
        ["query", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\Shell Folders", "/v", kind],
        { encoding: "utf8", windowsHide: true },
      );
      const dir = new RegExp(`${kind}\\s+REG_\\w+\\s+(.+)`).exec(out)?.[1]?.trim();
      if (dir && fs.existsSync(dir)) return dir;
    } catch {
      // Clé absente : dossier habituel.
    }
  }
  return fallback;
}

/**
 * Dossier partagé avec la tablette : dans Documents pour le paquet (gardé d'une version à l'autre), à la racine du dépôt en dev.
 * Pas « UnoMate » : c'est le nom du dossier du zip, souvent extrait dans Documents.
 */
export function sharedDir(): string {
  const old = path.resolve(backendRoot, "..", "shared");
  if (!packaged) return old;
  const dir = path.join(userFolder("Personal", path.join(os.homedir(), "Documents")), "Partage tablette");
  // Paquet plus ancien : documents dans le dossier extrait, recopiés une fois (l'original reste).
  if (!fs.existsSync(dir) && fs.existsSync(old) && fs.readdirSync(old).length > 0) {
    fs.cpSync(old, dir, { recursive: true });
  }
  return dir;
}
