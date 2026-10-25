import { listShortcuts } from "./apps.ts";
import { run } from "./run.ts";

/**
 * Ne pas déranger sur Mac : macOS n'a pas de commande pour la concentration, mais l'appli Raccourcis a l'action
 * « Définir la concentration ». Deux raccourcis créés une fois sur le Mac (docs/ne-pas-deranger.md).
 * L'état n'est pas lisible : null.
 */
export const DND_ON = "Compagnon Ne pas déranger";
export const DND_OFF = "Compagnon Fin Ne pas déranger";

export async function setDnd(on: boolean): Promise<null> {
  const name = on ? DND_ON : DND_OFF;
  // Noms des raccourcis parfois en accents décomposés : comparés en NFC.
  const names = (await listShortcuts()).map((s) => s.normalize("NFC"));
  if (!names.includes(name)) throw new Error(`Créez sur le Mac le raccourci « ${name} » (appli Raccourcis, action Définir la concentration)`);
  await run("shortcuts", ["run", name], 30_000);
  return null;
}
