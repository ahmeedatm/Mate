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

/** Relance la tâche (sans droits : une tâche « droits maximum » se démarre sans accord) au plus une fois par minute. */
function startTask() {
  const now = Date.now();
  if (now - lastStart < RESTART_EVERY_MS) return;
  lastStart = now;
  execFile("schtasks", ["/run", "/tn", CAPTEURS_TASK], { windowsHide: true }, () => {});
}

async function readCapteurs(): Promise<CapteursReading | null> {
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
