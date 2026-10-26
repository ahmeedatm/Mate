import type { AudioChange, AudioState } from "./screens.ts";

/** Ce que Mac (src/mac/) et Windows (src/win/) font pareil : le pilote de chaque système n'a que ses codes et ses commandes. */

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
