import { spawn } from "node:child_process";
import os from "node:os";
import type { SystemCommand } from "../screens.ts";
import * as audio from "./audio.ts";
import * as calls from "./calls.ts";
import * as keys from "./keys.ts";
import { osascript, run } from "./run.ts";

export async function systemCommand(command: SystemCommand) {
  switch (command) {
    case "sleep-display":
      return void (await run("pmset", ["displaysleepnow"]));
    case "toggle-dark-mode":
      return void (await osascript('tell application "System Events" to tell appearance preferences to set dark mode to not dark mode'));
    case "screenshot-clipboard":
      // Raccourci système plutôt que `screencapture` : ce dernier exige l'autorisation
      // Enregistrement de l'écran, sans laquelle il ne capture que le fond d'écran.
      return keys.sendKeys("cmd+ctrl+shift+3");
    case "lock":
      return keys.sendKeys("ctrl+cmd+q");
  }
}

/** Message bien visible sur l'ordinateur (code d'appairage), sans attendre qu'il soit fermé. */
export function alert(title: string, text: string) {
  // Processus détaché : la boîte reste affichée sans bloquer le serveur, et se ferme seule après 2 min.
  const script = `on run argv
activate
display dialog (item 2 of argv) with title (item 1 of argv) buttons {"OK"} default button 1 giving up after 120
end run`;
  spawn("osascript", ["-e", script, title, text], { detached: true, stdio: "ignore" }).unref();
}

/** Mémoire disponible en octets d'après la sortie de `vm_stat` : pages libres + inactives + spéculatives + purgeables. */
export function parseVmStat(output: string): number {
  const pageSize = Number(/page size of (\d+) bytes/.exec(output)?.[1] ?? 4096);
  const pages = (label: string) => Number(new RegExp(`^Pages ${label}:\\s+(\\d+)`, "m").exec(output)?.[1] ?? 0);
  return (pages("free") + pages("inactive") + pages("speculative") + pages("purgeable")) * pageSize;
}

/** Mémoire disponible en octets (`os.freemem()` ne compte que la libre, macOS garde le reste en cache) ; `vm_stat` en échec : `os.freemem()`. */
export async function availableMemory(): Promise<number> {
  try {
    return parseVmStat(await run("vm_stat", []));
  } catch {
    return os.freemem();
  }
}

/** L'appli tourne, même sans fenêtre. */
export async function isAppRunning(app: string): Promise<boolean> {
  return (await run("ps", ["-axo", "comm="])).split("\n").some((c) => c.includes(`/${app}.app/`));
}

/**
 * Sa fenêtre d'appel ou son écran d'avant-réunion est ouvert (Teams) ou, si sa fenêtre ne se lit pas, elle tourne et
 * un micro est utilisé.
 */
export async function isAppInCall(app: string): Promise<boolean> {
  if (!(await isAppRunning(app))) return false;
  const call = await calls.callState(app).catch(() => null);
  return call?.found ? call.connected || Boolean(call.lobby) : audio.micInUse();
}

/** Appli au premier plan (sans permission : NSWorkspace). */
export async function frontApp(): Promise<{ name: string; bundleId: string }> {
  const script =
    'ObjC.import("AppKit"); const a = $.NSWorkspace.sharedWorkspace.frontmostApplication; JSON.stringify({ name: ObjC.unwrap(a.localizedName) || "", bundleId: ObjC.unwrap(a.bundleIdentifier) || "" })';
  return JSON.parse(await osascript(script, [], "JavaScript"));
}
