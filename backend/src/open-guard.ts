import path from "node:path";

/**
 * Garde de `openTarget` : ce que l'ordinateur accepte d'ouvrir pour la tablette.
 * Un lien à schéma de la liste blanche, ou un chemin absolu (document, dossier) qui n'est pas un programme.
 */

const COMMON_SCHEMES = ["http", "https", "mailto", "msteams", "ms-teams", "slack", "vscode", "zoommtg"];
/** Schémas propres à un système : `x-apple…` n'existe que sur Mac, `ms-settings` que sur Windows. */
const SCHEMES: Record<string, RegExp> = {
  mac: new RegExp(`^(${COMMON_SCHEMES.join("|")}|x-apple[\\w.-]*)$`, "i"),
  windows: new RegExp(`^(${COMMON_SCHEMES.join("|")}|ms-settings)$`, "i"),
};

/** Fichiers qui s'exécutent à l'ouverture (Windows : ShellExecute ; Mac : `open`), dossier Partage compris. */
const EXECUTABLE = new Set([
  ".exe",
  ".com",
  ".scr",
  ".pif",
  ".bat",
  ".cmd",
  ".ps1",
  ".psm1",
  ".vbs",
  ".vbe",
  ".js",
  ".jse",
  ".wsf",
  ".wsh",
  ".hta",
  ".msi",
  ".msp",
  ".msc",
  ".cpl",
  ".reg",
  ".lnk",
  ".url",
  ".jar",
  ".appx",
  ".msix",
  ".application",
  ".app",
  ".command",
  ".tool",
  ".sh",
  ".bash",
  ".zsh",
  ".pkg",
  ".mpkg",
  ".workflow",
  ".scpt",
  ".scptd",
  ".action",
  ".terminal",
  ".dmg",
]);

export type OpenPlatform = "mac" | "windows";

function refuse(message: string): never {
  throw Object.assign(new Error(message), { statusCode: 400 });
}

/** Lève une erreur (400, en français) si la cible ne doit pas être ouverte ; sinon ne fait rien. */
export function checkOpenTarget(target: string, platform: OpenPlatform): void {
  if (!target.trim() || target.includes("\0")) refuse("Rien à ouvrir");
  // Un schéma fait au moins 2 caractères : « C:\… » est un chemin Windows.
  const scheme = /^([a-z][a-z0-9+.-]+):/i.exec(target)?.[1];
  if (scheme) {
    if (!SCHEMES[platform].test(scheme)) refuse(`Lien non autorisé : le schéma « ${scheme}: » n'est pas pris en charge`);
    return;
  }
  const p = platform === "windows" ? path.win32 : path.posix;
  // Chemin absolu seulement : un nom nu (« calc ») serait cherché dans le PATH ; un chemin réseau (\\hôte\…) est refusé.
  if (!p.isAbsolute(target) || (platform === "windows" && /^[\\/]{2}/.test(target)))
    refuse("Cible non autorisée : chemin absolu local attendu");
  // Windows ignore les points et espaces de fin (« a.exe. ») ; un dossier `.app` finit par « / » parfois.
  const name = target.replace(/[\\/\s.]+$/, "");
  if (EXECUTABLE.has(p.extname(name).toLowerCase())) refuse("Ouverture refusée : ce fichier est un programme ou un script");
}
