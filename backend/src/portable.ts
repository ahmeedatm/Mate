import { execFile } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { backendRoot, dataDir } from "./paths.ts";

/**
 * Appli portable (un seul .exe sous Windows, « Mate.app » sous Mac) : rien n'est installé ni inscrit. Le
 * lanceur extrait le runtime dans le dossier de l'utilisateur et pose `COMPANION_APP` (chemin de l'exe ou de l'appli).
 * Ce module porte ce que le panneau (page locale) change sur l'ordinateur : lancement au démarrage, pare-feu, tout oublier.
 * Rien n'est fait de lui-même : chaque fonction répond à un bouton.
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
const MAC_LABEL = LABEL || "app.mate.demarrage";
const RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";
const RUN_VALUE = LABEL || "Mate";
const FIREWALL_RULE = "Mate";

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

/** Exécutable de l'appli lancée. */
const macExecutable = () => path.join(appPath!, "Contents", "MacOS", "Mate");
const macPlist = (label: string) => path.join(os.homedir(), "Library", "LaunchAgents", `${label}.plist`);

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

/** Règle « Mate » visant le node de ce runtime. Lue par netsh (pas de PowerShell), gardée 15 s. */
export async function firewallOpen(): Promise<boolean | null> {
  if (!isWindows) return null;
  if (firewallCache && Date.now() - firewallCache.at < 15_000) return firewallCache.value;
  const node = path.join(backendRoot, "node", "node.exe").toLowerCase();
  const r = await sh("netsh", ["advfirewall", "firewall", "show", "rule", `name=${FIREWALL_RULE}`, "verbose"]);
  const value = r.ok && r.out.toLowerCase().includes(node);
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
