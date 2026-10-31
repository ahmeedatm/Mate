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

/** `cpuTempC` du fichier du capteur s'il date de moins de `CAPTEURS_MAX_AGE_MS` (et pas du futur) ; sinon `null`. */
export function parseCapteursFile(text: string, nowMs: number): number | null {
  try {
    const j = JSON.parse(text) as { cpuTempC?: unknown; at?: unknown } | null;
    if (typeof j?.cpuTempC !== "number" || typeof j.at !== "number" || j.cpuTempC <= 0) return null;
    const age = nowMs - j.at;
    return age >= 0 && age <= CAPTEURS_MAX_AGE_MS ? j.cpuTempC : null;
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

export async function cpuTemperature(): Promise<number | null> {
  try {
    const value = parseCapteursFile(await fs.readFile(FILE, "utf8"), Date.now());
    if (value === null) startTask();
    return value;
  } catch {
    startTask();
    return null;
  }
}
