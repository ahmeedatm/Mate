import { spawn } from "node:child_process";
import os from "node:os";
import type { SystemCommand } from "../screens.ts";
import * as audio from "./audio.ts";
import * as calls from "./calls.ts";
import * as keys from "./keys.ts";
import { automation } from "./permissions.ts";
import { osascript, run } from "./run.ts";

export async function systemCommand(command: SystemCommand) {
  switch (command) {
    case "sleep-display":
      return void (await run("pmset", ["displaysleepnow"]));
    case "toggle-dark-mode":
      await automation.requireSystemEvents();
      return void (await osascript('tell application "System Events" to tell appearance preferences to set dark mode to not dark mode'));
    case "screenshot-clipboard":
      // Raccourci système plutôt que `screencapture` : ce dernier exige l'autorisation
      // Enregistrement de l'écran, sans laquelle il ne capture que le fond d'écran.
      return keys.sendKeys("cmd+ctrl+shift+3");
    case "lock":
      return keys.sendKeys("ctrl+cmd+q");
  }
}

/** Notification du centre de notifications (demande d'appairage), sans bloquer le serveur. */
export function notify(title: string, text: string) {
  // Texte et titre en argv : jamais d'interpolation dans le script. S'affiche au nom d'« Éditeur de script » (docs/architecture.md).
  const script = `on run argv
display notification (item 2 of argv) with title (item 1 of argv)
end run`;
  spawn("osascript", ["-e", script, title, text], { detached: true, stdio: "ignore" }).unref();
}

/** Mémoire disponible en octets d'après la sortie de `vm_stat` : pages libres + inactives + spéculatives + purgeables. */
export function parseVmStat(output: string): number {
  const pageSize = Number(/page size of (\d+) bytes/.exec(output)?.[1] ?? 4096);
  const pages = (label: string) => Number(new RegExp(`^Pages ${label}:\\s+(\\d+)`, "m").exec(output)?.[1] ?? 0);
  return (pages("free") + pages("inactive") + pages("speculative") + pages("purgeable")) * pageSize;
}

/**
 * Lignes de `ps -Aco pcpu=,rss=,comm=` : processeur en % d'un cœur (moyenne récente de macOS), mémoire résidente en
 * Ko, nom court du processus (peut contenir des espaces). Les lignes illisibles sont écartées.
 */
export function parsePsApps(output: string): { name: string; cpu: number; memBytes: number }[] {
  const list: { name: string; cpu: number; memBytes: number }[] = [];
  for (const line of output.split("\n")) {
    const m = /^\s*([\d.]+)\s+(\d+)\s+(.+?)\s*$/.exec(line);
    if (m) list.push({ name: m[3], cpu: Number(m[1]), memBytes: Number(m[2]) * 1024 });
  }
  return list;
}

/** Processus du Mac pour le widget Performances (`ps`, sans permission). */
export async function processes(): Promise<{ name: string; cpu: number; memBytes: number }[]> {
  return parsePsApps(await run("ps", ["-Aco", "pcpu=,rss=,comm="], 5_000));
}

/** Mémoire disponible en octets (`os.freemem()` ne compte que la libre, macOS garde le reste en cache) ; `vm_stat` en échec : `os.freemem()`. */
export async function availableMemory(): Promise<number> {
  try {
    return parseVmStat(await run("vm_stat", []));
  } catch {
    return os.freemem();
  }
}

export type NetCounters = { rx: number; tx: number; at: number };

/**
 * Octets reçus et émis d'après `netstat -ib` : lignes `<Link#>` des interfaces `en*` (Wi-Fi, Ethernet). Les colonnes
 * sont lues depuis la fin (Ibytes, Opkts, Oerrs, Obytes, Coll) car l'adresse peut manquer. `null` : rien de lisible.
 */
export function parseNetstat(output: string): { rx: number; tx: number } | null {
  let rx = 0;
  let tx = 0;
  let found = false;
  for (const line of output.split("\n")) {
    const f = line.trim().split(/\s+/);
    if (!/^en\d+\*?$/.test(f[0] ?? "") || !f[2]?.startsWith("<Link#") || f.length < 8) continue;
    const ibytes = Number(f[f.length - 5]);
    const obytes = Number(f[f.length - 2]);
    if (!Number.isFinite(ibytes) || !Number.isFinite(obytes)) continue;
    rx += ibytes;
    tx += obytes;
    found = true;
  }
  return found ? { rx, tx } : null;
}

/** Débit entre deux relevés en Ko/s ; `null` sans écart de temps ou si un compteur a reculé (interface relancée). */
export function kbps(prev: NetCounters, next: NetCounters): { upKBps: number; downKBps: number } | null {
  const seconds = (next.at - prev.at) / 1000;
  if (seconds <= 0 || next.rx < prev.rx || next.tx < prev.tx) return null;
  return { upKBps: (next.tx - prev.tx) / 1024 / seconds, downKBps: (next.rx - prev.rx) / 1024 / seconds };
}

let lastNet: NetCounters | undefined;
/** Au-delà, le dernier relevé réseau ne sert plus de point de départ (widget fermé un moment). */
const NET_STALE_MS = 5000;
/** Écart des deux relevés du tout premier appel (ou après une pause). */
const NET_BASELINE_MS = 250;

async function readNet(): Promise<NetCounters | null> {
  const counters = parseNetstat(await run("netstat", ["-ib"]));
  return counters && { ...counters, at: performance.now() };
}

/** Débit réseau du Mac (deltas de `netstat -ib`) ; premier relevé : un second suit 250 ms plus tard. */
export async function netThroughput(): Promise<{ upKBps: number; downKBps: number } | null> {
  let prev = lastNet && performance.now() - lastNet.at <= NET_STALE_MS ? lastNet : null;
  if (!prev) {
    prev = await readNet();
    if (!prev) return null;
    await new Promise((resolve) => setTimeout(resolve, NET_BASELINE_MS));
  }
  const next = await readNet();
  lastNet = next ?? undefined;
  return next && kbps(prev, next);
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

/**
 * Appli au premier plan (sans permission : NSWorkspace). `file` : nom du fichier .app sans l'extension, celui du catalogue
 * des applis (`localizedName` change avec la langue du Mac).
 */
export async function frontApp(): Promise<{ name: string; bundleId: string; file: string }> {
  const script =
    'ObjC.import("AppKit"); const a = $.NSWorkspace.sharedWorkspace.frontmostApplication; JSON.stringify({ name: ObjC.unwrap(a.localizedName) || "", bundleId: ObjC.unwrap(a.bundleIdentifier) || "", file: (a.bundleURL ? ObjC.unwrap(a.bundleURL.lastPathComponent) : "").replace(/\\.app$/, "") })';
  return JSON.parse(await osascript(script, [], "JavaScript"));
}
