import { logFailure } from "./log.ts";
import { keepAwake } from "./os.ts";

/**
 * Écran de l'ordinateur gardé allumé pendant la concentration du Focus de la tablette : la veille normale revient à
 * la pause ou à la fin. Rien à mémoriser sur disque : l'état meurt avec le compagnon (Mac : `caffeinate -w`, Windows :
 * état du helper). Minuteur de secours : la tablette peut s'éteindre ou perdre le WiFi en plein Focus.
 */
let safety: NodeJS.Timeout | undefined;

/** `minutes` : temps restant du chrono, pour le minuteur de secours. */
export async function focusAwake(on: boolean, minutes = 60): Promise<{ on: boolean }> {
  clearTimeout(safety);
  safety = undefined;
  await keepAwake(on);
  if (on) safety = setTimeout(() => void focusAwake(false).catch((e) => logFailure("Fin de l'écran éveillé", e)), (minutes + 2) * 60_000);
  return { on };
}
