import fs from "node:fs";
import path from "node:path";
import { osName, steamPath } from "./os.ts";

/**
 * Cyberpunk 2077 (Steam, Windows seulement) : dossier du jeu, présence de Cyber Engine Tweaks (CET) et dossier du mod Mate.
 * Dossier de Steam : variable MATE_STEAM_PATH (tests), sinon `SteamPath` du registre, puis les bibliothèques de
 * `steamapps/libraryfolders.vdf` et le manifeste `appmanifest_1091500.acf` du jeu.
 */

const APP_ID = "1091500";
const PLUGINS = path.join("bin", "x64", "plugins");

export type Game = {
  /** Dossier d'installation du jeu. */
  dir: string;
  /** CET installé (`cyber_engine_tweaks.asi` ou son dossier). */
  cet: boolean;
  /** `…/cyber_engine_tweaks/mods/mate` : calculé, jamais fourni par une requête. */
  modDir: string;
};

/** Valeurs d'un champ d'un fichier VDF/ACF (`"champ"  "valeur"`, antislash doublés). */
function vdfValues(text: string, key: string): string[] {
  return [...text.matchAll(new RegExp(`"${key}"\\s+"([^"]*)"`, "gi"))].map((m) => m[1].replace(/\\\\/g, "\\"));
}

const read = (file: string) => {
  try {
    return fs.readFileSync(file, "utf8");
  } catch {
    return "";
  }
};

let registry: { at: number; value: string | null } | undefined;
function steamRoot(): string | null {
  if (process.env.MATE_STEAM_PATH) return process.env.MATE_STEAM_PATH;
  if (!registry || Date.now() - registry.at > 60_000) registry = { at: Date.now(), value: steamPath() };
  return registry.value;
}

/** Le jeu dans une installation de Steam donnée, ou null. */
export function gameIn(root: string): Game | null {
  const libraries = [root, ...vdfValues(read(path.join(root, "steamapps", "libraryfolders.vdf")), "path")];
  for (const lib of libraries) {
    const installdir = vdfValues(read(path.join(lib, "steamapps", `appmanifest_${APP_ID}.acf`)), "installdir")[0];
    // Un nom de dossier seulement : le manifeste ne doit pas faire sortir du dossier common.
    if (!installdir || installdir === "." || installdir === ".." || installdir !== path.basename(installdir)) continue;
    const dir = path.join(lib, "steamapps", "common", installdir);
    if (!fs.existsSync(dir)) continue;
    const plugins = path.join(dir, PLUGINS);
    const cet = fs.existsSync(path.join(plugins, "cyber_engine_tweaks.asi")) || fs.existsSync(path.join(plugins, "cyber_engine_tweaks"));
    return { dir, cet, modDir: path.join(plugins, "cyber_engine_tweaks", "mods", "mate") };
  }
  return null;
}

/** Le jeu Steam de cet ordinateur ; null hors Windows ou s'il n'est pas installé. */
export function findGame(): Game | null {
  if (osName !== "windows") return null;
  const root = steamRoot();
  return root ? gameIn(root) : null;
}
