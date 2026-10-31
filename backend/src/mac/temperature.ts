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

/** `temp.cpu_temp_avg` de la sortie JSON de `macmon pipe` ; `null` si absente ou illisible. */
export function parseMacmonTemp(output: string): number | null {
  try {
    const value = (JSON.parse(output) as { temp?: { cpu_temp_avg?: unknown } } | null)?.temp?.cpu_temp_avg;
    return typeof value === "number" ? value : null;
  } catch {
    return null;
  }
}

let binary: string | null | undefined;

export async function cpuTemperature(): Promise<number | null> {
  binary ??= CANDIDATES.find((p) => fs.existsSync(p)) ?? null;
  if (!binary) return null;
  return parseMacmonTemp(await run(binary, ["pipe", "-s", "1", "-i", INTERVAL_MS], READ_TIMEOUT_MS));
}
