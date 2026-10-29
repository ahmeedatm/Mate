import { isAppRunning, sendKeys } from "./os.ts";

export type PresentAction = "next" | "previous" | "start" | "end";
export const PRESENT_ACTIONS: PresentAction[] = ["next", "previous", "start", "end"];

/** Applis de diapos pilotées, dans l'ordre de préférence : PowerPoint prime si les deux tournent. Keynote n'existe que sur Mac. */
const APPS = ["Microsoft PowerPoint", "Keynote"];

/**
 * Touches de chaque action, envoyées à l'appli de diapos : flèches pour avancer et reculer, « 1 » puis Entrée pour
 * revenir à la première diapo du diaporama en cours (même geste dans PowerPoint et Keynote), Échap pour le quitter.
 */
const KEYS: Record<PresentAction, string[]> = {
  next: ["right"],
  previous: ["left"],
  start: ["1", "enter"],
  end: ["escape"],
};

export class PresentError extends Error {
  statusCode = 409;
}

/** L'appli de diapos ouverte sur l'ordinateur, PowerPoint d'abord. */
export async function presentApp(): Promise<string | null> {
  for (const app of APPS) if (await isAppRunning(app).catch(() => false)) return app;
  return null;
}

/** Envoie l'action à l'appli de diapos ouverte (mise au premier plan avant la première touche). */
export async function present(action: PresentAction): Promise<string> {
  const app = await presentApp();
  if (!app) throw new PresentError("Ouvre PowerPoint ou Keynote sur l'ordinateur");
  for (const [i, combo] of KEYS[action].entries()) await sendKeys(combo, i === 0 ? app : undefined);
  return app;
}
