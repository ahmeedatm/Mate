import path from "node:path";
import { config } from "./config.ts";
import { logFailure } from "./log.ts";
import { getDnd, setDnd } from "./os.ts";
import { readJson, writeJson } from "./store.ts";

/**
 * Ne pas déranger de l'ordinateur pendant le Focus de la tablette : activé quand le chrono de concentration
 * démarre, rendu à la pause ou à la fin. On ne rend que ce qu'on a activé (déjà mis par l'utilisateur : on n'y
 * touche pas), noté dans .data/dnd.json pour survivre à un redémarrage du compagnon. Minuteur de secours : la
 * tablette peut s'éteindre ou perdre le WiFi en plein Focus.
 */
const storeFile = path.join(config.dataDir, "dnd.json");
let safety: NodeJS.Timeout | undefined;

async function isOurs(): Promise<boolean> {
  return (await readJson<{ ours?: boolean }>(storeFile, () => ({}))).ours === true;
}

async function setOurs(ours: boolean) {
  await writeJson(storeFile, { ours });
}

/** `minutes` : temps restant du chrono, pour le minuteur de secours. */
export async function focusDnd(on: boolean, minutes = 60): Promise<{ on: boolean | null }> {
  clearTimeout(safety);
  safety = undefined;
  if (on) {
    if (!(await isOurs())) {
      if ((await getDnd()) === true) return { on: true };
      await setDnd(true);
      await setOurs(true);
    }
    safety = setTimeout(() => void focusDnd(false).catch((e) => logFailure("Fin du Ne pas déranger", e)), (minutes + 2) * 60_000);
    return { on: true };
  }
  if (await isOurs()) {
    await setDnd(false);
    await setOurs(false);
  }
  return { on: await getDnd() };
}
