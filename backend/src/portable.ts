import { execFile } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { backendRoot, dataDir, userFolder } from "./paths.ts";

/**
 * Appli portable (un seul .exe sous Windows, « Compagnon Unowhy.app » sous Mac) : rien n'est installé ni inscrit. Le
 * lanceur extrait le runtime dans le dossier de l'utilisateur et pose `COMPANION_APP` (chemin de l'exe ou de l'appli).
 * Ce module porte ce que le panneau (page locale) change sur l'ordinateur : lancement au démarrage, pare-feu, ancienne
 * installation, tout oublier. Rien n'est fait de lui-même : chaque fonction répond à un bouton.
 */

/** Chemin de l'exe ou de l'appli lancée ; absent lancé depuis le dépôt (`npm run dev`, service.ts). */
export const appPath = process.env.COMPANION_APP ? path.resolve(process.env.COMPANION_APP) : undefined;
const isMac = process.platform === "darwin";
const isWindows = process.platform === "win32";
export const portable = appPath !== undefined && (isMac || isWindows);

/** Codes de sortie du serveur, lus par la boucle du lanceur : quitter, ou quitter en effaçant le runtime. Les autres relancent. */
export const QUIT_CODE = 75;
export const FORGET_CODE = 76;

/** Instance de test (qa/) : sa propre entrée de démarrage, celle du vrai compagnon n'est pas touchée. */
const LABEL = process.env.COMPANION_LABEL || "";
const MAC_LABEL = LABEL || "com.unowhy.companion.demarrage";
const RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";
const RUN_VALUE = LABEL || "UnowhyCompanion";
const UNINSTALL_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\UnowhyCompanion";
const LEGACY_MAC_LABEL = "com.unowhy.companion";
const SHORTCUT = "Compagnon Unowhy";
const FIREWALL_RULE = "Compagnon Unowhy";
const home = os.homedir();
const reg = async (args: string[]) => void (await sh("reg", args));

export function sh(cmd: string, args: string[], timeout = 30_000): Promise<{ ok: boolean; out: string }> {
  return new Promise((resolve) => {
    execFile(cmd, args, { windowsHide: true, timeout }, (err, stdout, stderr) => resolve({ ok: !err, out: `${stdout}${stderr}`.trim() }));
  });
}

const xml = (s: string) => s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
const sameFile = (a: string, b: string) => path.resolve(a).toLowerCase() === path.resolve(b).toLowerCase();

// --- Commandes de démarrage (fonctions pures, testées)

/** Windows : valeur de la clé Run. `--demarrage` : pas de page ouverte à l'ouverture de session. */
export const windowsRunCommand = (exe: string) => `"${exe}" --demarrage`;

/** Mac : agent launchd qui lance l'appli à l'ouverture de session ; la boucle du lanceur tourne alors au premier plan. */
export function macLaunchAgent(label: string, executable: string): string {
  return `<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key>
  <string>${xml(label)}</string>
  <key>ProgramArguments</key>
  <array>
    <string>${xml(executable)}</string>
    <string>--demarrage</string>
  </array>
  <key>RunAtLoad</key>
  <true/>
  <key>ProcessType</key>
  <string>Interactive</string>
</dict>
</plist>
`;
}

/** Agent ou clé Run d'une installation faite par l'ancien installeur : le paquet installé (node embarqué), pas le dépôt. */
export const isLegacyMacAgent = (plist: string) => plist.includes("Contents/Resources/backend");
export const isLegacyRun = (value: string) => value.includes("--backend") && /\\node\\node\.exe/i.test(value);

const macExecutable = () => path.join(appPath!, "Contents", "MacOS", "Compagnon Unowhy");
const macPlist = (label: string) => path.join(home, "Library", "LaunchAgents", `${label}.plist`);

// --- Lancement au démarrage

export async function autostartEnabled(): Promise<boolean> {
  if (isMac) return fs.existsSync(macPlist(MAC_LABEL)) && fs.readFileSync(macPlist(MAC_LABEL), "utf8").includes(xml(macExecutable()));
  const r = await sh("reg", ["query", RUN_KEY, "/v", RUN_VALUE]);
  return r.ok && r.out.includes(windowsRunCommand(appPath!));
}

export async function setAutostart(on: boolean) {
  if (isMac) {
    // Pas de launchctl : l'agent chargé tourne peut-être déjà (c'est lui qui a lancé ce compagnon) ; il compte à la prochaine session.
    if (!on) return fs.rmSync(macPlist(MAC_LABEL), { force: true });
    fs.mkdirSync(path.dirname(macPlist(MAC_LABEL)), { recursive: true });
    return fs.writeFileSync(macPlist(MAC_LABEL), macLaunchAgent(MAC_LABEL, macExecutable()));
  }
  const r = on
    ? await sh("reg", ["add", RUN_KEY, "/v", RUN_VALUE, "/t", "REG_SZ", "/d", windowsRunCommand(appPath!), "/f"])
    : await sh("reg", ["delete", RUN_KEY, "/v", RUN_VALUE, "/f"]);
  if (!r.ok && on) throw new Error(`Registre : ${r.out}`);
}

// --- Pare-feu (Windows)

const firewallScript = path.join(backendRoot, "scripts", "firewall-windows.ps1");
let firewallCache: { value: boolean; at: number } | undefined;

/** Règle « Compagnon Unowhy » visant le node de ce runtime. Lue par netsh (pas de PowerShell), gardée 15 s. */
export async function firewallOpen(): Promise<boolean | null> {
  if (!isWindows) return null;
  if (firewallCache && Date.now() - firewallCache.at < 15_000) return firewallCache.value;
  const r = await sh("netsh", ["advfirewall", "firewall", "show", "rule", `name=${FIREWALL_RULE}`, "verbose"]);
  const value = r.ok && r.out.toLowerCase().includes(path.join(backendRoot, "node", "node.exe").toLowerCase());
  firewallCache = { value, at: Date.now() };
  return value;
}

/** Lance le script du pare-feu : la demande d'administrateur (UAC) s'affiche à ce moment-là. Faux si refusée. */
export async function setFirewall(open: boolean): Promise<boolean> {
  const args = ["-NoProfile", "-ExecutionPolicy", "Bypass", "-File", firewallScript, ...(open ? [] : ["-Retirer"])];
  const r = await sh("powershell.exe", args, 120_000);
  firewallCache = undefined;
  return r.ok;
}

// --- Ancienne installation (NSIS sous Windows, appli copiée dans Applications + LaunchAgent sous Mac)

type Leftover = { label: string; remove: () => Promise<void> | void };

async function leftovers(): Promise<Leftover[]> {
  const found: Leftover[] = [];
  const rm = (p: string) => fs.rmSync(p, { recursive: true, force: true });
  if (isMac) {
    const agent = macPlist(LEGACY_MAC_LABEL);
    if (fs.existsSync(agent) && isLegacyMacAgent(fs.readFileSync(agent, "utf8"))) {
      found.push({
        label: "lancement automatique de l'ancienne installation",
        remove: async () => {
          await sh("launchctl", ["bootout", `gui/${process.getuid?.()}/${LEGACY_MAC_LABEL}`]);
          rm(agent);
        },
      });
    }
    for (const dir of ["/Applications", path.join(home, "Applications")]) {
      const copy = path.join(dir, "Compagnon Unowhy.app");
      if (fs.existsSync(path.join(copy, "Contents", "Resources", "backend", "dist", "service.mjs")) && !sameFile(copy, appPath!)) {
        found.push({ label: `appli copiée dans ${dir}`, remove: () => rm(copy) });
      }
    }
    const stamp = path.join(dataDir, "app-Info.plist");
    if (fs.existsSync(stamp)) found.push({ label: "repère de l'ancien installeur", remove: () => rm(stamp) });
    // Raccourci de l'ancien installeur : retiré avec lui seulement (le dépôt en pose aussi, vers la même page).
    const webloc = path.join(home, "Desktop", `${SHORTCUT}.webloc`);
    if (found.length > 0 && fs.existsSync(webloc)) found.push({ label: "raccourci du Bureau", remove: () => rm(webloc) });
    return found;
  }

  const run = await sh("reg", ["query", RUN_KEY, "/v", RUN_VALUE]);
  if (run.ok && isLegacyRun(run.out)) {
    found.push({
      label: "lancement automatique de l'ancienne installation",
      remove: () => reg(["delete", RUN_KEY, "/v", RUN_VALUE, "/f"]),
    });
  }
  if ((await sh("reg", ["query", UNINSTALL_KEY])).ok) {
    found.push({ label: "entrée de Programmes et fonctionnalités", remove: () => reg(["delete", UNINSTALL_KEY, "/f"]) });
  }
  const localAppData = process.env.LOCALAPPDATA ?? path.join(home, "AppData", "Local");
  const installed = path.join(localAppData, "Programs", "Compagnon Unowhy");
  const inside =
    appPath !== undefined &&
    path
      .resolve(appPath)
      .toLowerCase()
      .startsWith(installed.toLowerCase() + path.sep);
  if (fs.existsSync(path.join(installed, "backend", "node", "node.exe")) && !inside) {
    found.push({ label: `dossier ${installed}`, remove: () => rm(installed) });
  }
  const shortcuts = [
    path.join(userFolder("Desktop", path.join(home, "Desktop")), `${SHORTCUT}.url`),
    path.join(
      userFolder(
        "Programs",
        path.join(process.env.APPDATA ?? path.join(home, "AppData", "Roaming"), "Microsoft", "Windows", "Start Menu", "Programs"),
      ),
      `${SHORTCUT}.url`,
    ),
  ].filter((f) => fs.existsSync(f));
  if (found.length > 0) for (const f of shortcuts) found.push({ label: `raccourci ${path.basename(f)}`, remove: () => rm(f) });
  return found;
}

export async function legacyFound(): Promise<string[]> {
  return (await leftovers()).map((l) => l.label);
}

/** Retire l'ancienne installation, données gardées. Rend ce qui n'a pas pu l'être. */
export async function removeLegacy(): Promise<{ removed: string[]; failed: string[] }> {
  const removed: string[] = [];
  const failed: string[] = [];
  for (const l of await leftovers()) {
    try {
      await l.remove();
      removed.push(l.label);
    } catch {
      failed.push(l.label);
    }
  }
  return { removed, failed };
}

// --- Tout oublier

/**
 * Retire le lancement au démarrage et les données (identité, appairage, agenda, tâches). Le runtime lui-même est effacé
 * par le lanceur quand le serveur sort avec FORGET_CODE. Le dossier Partage tablette reste : ce sont les documents de l'utilisateur.
 */
export async function forgetEverything() {
  await setAutostart(false).catch(() => undefined);
  if (isWindows && (await firewallOpen())) await setFirewall(false);
  for (const name of fs.readdirSync(dataDir)) {
    const p = path.join(dataDir, name);
    if (!sameFile(p, backendRoot)) fs.rmSync(p, { recursive: true, force: true });
  }
}
