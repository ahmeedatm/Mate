import { execFile } from "node:child_process";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";

/**
 * Température du processeur sous Windows. Elle passe par un pilote noyau (PawnIO) que seul un programme administrateur
 * peut interroger : `MateCapteurs.exe` (src/win/capteurs/, LibreHardwareMonitorLib) tourne donc dans une tâche
 * planifiée « droits maximum » créée une seule fois par `scripts/capteurs-windows.ps1` (accord administrateur demandé
 * par le panneau). Il écrit la valeur dans un fichier que ce module lit ; fichier absent ou périmé : pas de température.
 */
export const CAPTEURS_TASK = "MateCapteurs";
/** Au-delà, le capteur est considéré arrêté : mieux vaut rien qu'un chiffre vieux. */
export const CAPTEURS_MAX_AGE_MS = 6000;
const RESTART_EVERY_MS = 60_000;
/**
 * Sans lecture depuis ce délai (widget Performances fermé, tablette partie), la tâche est arrêtée : LibreHardwareMonitor
 * relit processeur, carte graphique et carte mère toutes les 2 s, ce qui chauffe certains portables pour rien. La
 * prochaine lecture la relance (`startTask`).
 */
export const CAPTEURS_IDLE_MS = 60_000;
const IDLE_CHECK_MS = 30_000;
const FILE = path.join(process.env.LOCALAPPDATA ?? os.tmpdir(), "Mate", "capteurs", "cpu-temp.json");

export type CapteursReading = { cpuTempC?: number; cpuWatts?: number; cpuFanRpm?: number; gpuWatts?: number };
const FIELDS = ["cpuTempC", "cpuWatts", "cpuFanRpm", "gpuWatts"] as const;

/** Valeurs du fichier du capteur s'il date de moins de `CAPTEURS_MAX_AGE_MS` (et pas du futur) ; sinon `null`. Un champ nul ou absent est omis. */
export function parseCapteursReading(text: string, nowMs: number): CapteursReading | null {
  try {
    const j = JSON.parse(text) as (Record<string, unknown> & { at?: unknown }) | null;
    if (typeof j?.at !== "number") return null;
    const age = nowMs - j.at;
    if (age < 0 || age > CAPTEURS_MAX_AGE_MS) return null;
    const reading: CapteursReading = {};
    for (const field of FIELDS) {
      const value = j[field];
      if (typeof value === "number" && value > 0) reading[field] = value;
    }
    return Object.keys(reading).length > 0 ? reading : null;
  } catch {
    return null;
  }
}

let lastStart = 0;
/** Départ du compagnon : la tâche lancée à l'ouverture de session s'arrête aussi si personne ne la lit. */
let lastRead = Date.now();

/** Arrêter la tâche : pas lue depuis `CAPTEURS_IDLE_MS`, et son fichier est encore frais (elle tourne). */
export function capteursIdle(lastReadMs: number, fileText: string | null, nowMs: number): boolean {
  if (nowMs - lastReadMs < CAPTEURS_IDLE_MS || fileText === null) return false;
  try {
    const at = (JSON.parse(fileText) as { at?: unknown } | null)?.at;
    return typeof at === "number" && nowMs - at >= 0 && nowMs - at <= CAPTEURS_MAX_AGE_MS;
  } catch {
    return false;
  }
}

/** Arrête la tâche quand plus rien ne la lit (vérifié toutes les 30 s) ; renvoie la fonction qui arrête la veille. */
export function stopCapteursWhenIdle(): () => void {
  const timer = setInterval(async () => {
    const text = await fs.readFile(FILE, "utf8").catch(() => null);
    if (!capteursIdle(lastRead, text, Date.now())) return;
    lastStart = 0; // la prochaine lecture la relance sans attendre la minute
    // Une tâche « droits maximum » s'arrête sans accord, comme elle se démarre.
    execFile("schtasks", ["/end", "/tn", CAPTEURS_TASK], { windowsHide: true }, () => {});
  }, IDLE_CHECK_MS);
  timer.unref();
  return () => clearInterval(timer);
}

/** Relance la tâche (sans droits : une tâche « droits maximum » se démarre sans accord) au plus une fois par minute. */
function startTask() {
  const now = Date.now();
  if (now - lastStart < RESTART_EVERY_MS) return;
  lastStart = now;
  execFile("schtasks", ["/run", "/tn", CAPTEURS_TASK], { windowsHide: true }, () => {});
}

async function readCapteurs(): Promise<CapteursReading | null> {
  lastRead = Date.now();
  try {
    const reading = parseCapteursReading(await fs.readFile(FILE, "utf8"), Date.now());
    if (reading === null) startTask();
    return reading;
  } catch {
    startTask();
    return null;
  }
}

export async function cpuTemperature(): Promise<number | null> {
  return (await readCapteurs())?.cpuTempC ?? null;
}

export async function cpuWatts(): Promise<number | null> {
  return (await readCapteurs())?.cpuWatts ?? null;
}

export async function cpuFanRpm(): Promise<number | null> {
  return (await readCapteurs())?.cpuFanRpm ?? null;
}

export async function gpuWatts(): Promise<number | null> {
  return (await readCapteurs())?.gpuWatts ?? null;
}
