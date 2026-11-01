import fs from "node:fs";
import { run } from "./run.ts";

/**
 * Température du processeur d'un Mac Apple Silicon par `macmon` (github.com/vladkens/macmon, MIT), qui lit les capteurs
 * sans `sudo` (`powermetrics` l'exige). À installer à part (`brew install macmon`) : absent, le widget n'affiche rien
 * plutôt qu'un faux chiffre.
 */
const CANDIDATES = ["/opt/homebrew/bin/macmon", "/usr/local/bin/macmon"];
/** Intervalle d'échantillonnage de macmon : court, pour répondre sous le délai de lecture du widget. */
const INTERVAL_MS = "250";
const READ_TIMEOUT_MS = 3000;
const CACHE_MS = 1000;

/** `cpu_power` et `gpu_power` (watts) de la sortie de `macmon pipe` ; `null` pour ce qui est absent ou illisible. */
export function parseMacmonPower(output: string): { cpuWatts: number | null; gpuWatts: number | null } {
  try {
    const j = JSON.parse(output) as { cpu_power?: unknown; gpu_power?: unknown } | null;
    const watts = (v: unknown) => (typeof v === "number" ? v : null);
    return { cpuWatts: watts(j?.cpu_power), gpuWatts: watts(j?.gpu_power) };
  } catch {
    return { cpuWatts: null, gpuWatts: null };
  }
}

/** `temp.cpu_temp_avg` de la sortie JSON de `macmon pipe` ; `null` si absente ou illisible. */
export function parseMacmonTemp(output: string): number | null {
  try {
    const value = (JSON.parse(output) as { temp?: { cpu_temp_avg?: unknown } } | null)?.temp?.cpu_temp_avg;
    return typeof value === "number" ? value : null;
  } catch {
    return null;
  }
}

let binary: string | undefined;
let cached: { at: number; output: Promise<string> } | undefined;

/** Une seule lecture de macmon par seconde, partagée par la température et les watts (le widget les demande ensemble). */
function macmonOutput(): Promise<string> | null {
  // Cherché à chaque lecture tant qu'il manque : un macmon installé après le démarrage du compagnon est pris sans le relancer.
  binary ??= CANDIDATES.find((p) => fs.existsSync(p));
  if (!binary) return null;
  const now = Date.now();
  if (!cached || now - cached.at >= CACHE_MS)
    cached = { at: now, output: run(binary, ["pipe", "-s", "1", "-i", INTERVAL_MS], READ_TIMEOUT_MS) };
  return cached.output;
}

export async function cpuTemperature(): Promise<number | null> {
  const output = macmonOutput();
  return output ? parseMacmonTemp(await output) : null;
}

export async function cpuWatts(): Promise<number | null> {
  const output = macmonOutput();
  return output ? parseMacmonPower(await output).cpuWatts : null;
}

export async function gpuWatts(): Promise<number | null> {
  const output = macmonOutput();
  return output ? parseMacmonPower(await output).gpuWatts : null;
}
