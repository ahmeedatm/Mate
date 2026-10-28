import type { AudioChange, AudioState } from "./screens.ts";

/** Ce que Mac (src/mac/) et Windows (src/win/) font pareil : le pilote de chaque système n'a que ses codes et ses commandes. */

/** Mot que le pilote Windows met dans son erreur quand le presse-papiers n'existe pas (aucune session de bureau). */
export const CLIPBOARD_UNAVAILABLE = "CLIPBOARD_UNAVAILABLE";

/**
 * Pas de presse-papiers sur cet ordinateur (service sans session de bureau ouverte, conteneur) : 503, le seul cas où le
 * contrôle sanity passe son tour. Toute autre erreur (pilote cassé, script refusé) reste une erreur 500.
 */
export function clipboardUnavailable(): never {
  throw Object.assign(new Error("Presse-papiers indisponible : aucune session de bureau ouverte sur l'ordinateur"), { statusCode: 503 });
}

/** Le pilote Windows signale l'absence de bureau par `CLIPBOARD_UNAVAILABLE` : en 503 ; les autres erreurs restent telles quelles. */
export function whenClipboardAvailable<T>(result: Promise<T>): Promise<T> {
  return result.catch((e: Error) => {
    if (e.message.includes(CLIPBOARD_UNAVAILABLE)) clipboardUnavailable();
    throw e;
  });
}

/** Volume (0-100) et sourdine à appliquer pour `change` ; monter le volume réactive le son, comme les touches du clavier. */
export function planAudio(change: AudioChange, current: AudioState): { output?: number; muted?: boolean } {
  const clamp = (v: number) => Math.max(0, Math.min(100, Math.round(v)));
  let output: number | undefined;
  if (change.output !== undefined) output = clamp(change.output);
  else if (change.outputDelta !== undefined && current.output !== null) output = clamp(current.output + change.outputDelta);
  const muted = change.muted ?? (output !== undefined && output > 0 && current.muted ? false : undefined);
  return { output, muted };
}

/** "cmd+shift+m" : la touche (dernier élément) et les modificateurs traduits par la table du système. */
export function parseCombo<T>(combo: string, modifiers: Record<string, T>): { key: string; mods: T[] } {
  const parts = combo
    .toLowerCase()
    .split("+")
    .map((p) => p.trim())
    .filter(Boolean);
  const key = parts.pop();
  if (!key) throw new Error(`Raccourci invalide : ${combo}`);
  const mods = parts.map((p) => {
    const mod = modifiers[p];
    if (mod === undefined) throw new Error(`Modificateur inconnu : ${p}`);
    return mod;
  });
  return { key, mods };
}

/** Noms d'applis sans doublon, triés comme un annuaire français. */
export function sortedNames(names: string[]): string[] {
  return [...new Set(names)].sort((a, b) => a.localeCompare(b, "fr", { sensitivity: "base" }));
}
