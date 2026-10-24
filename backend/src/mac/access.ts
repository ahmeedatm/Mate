import { osascript, run } from "./run.ts";

/**
 * Autorisation Accessibilité du Mac : sans elle, fenêtres, raccourcis clavier et saisie ne marchent pas. Elle est
 * accordée à l'appli qui a lancé le backend (le service : node ; sinon le terminal), d'où une vérification réelle
 * plutôt que d'attendre la première erreur.
 */
let cached: { value: boolean; at: number } | undefined;

export async function accessibilityTrusted(): Promise<boolean> {
  if (cached && Date.now() - cached.at < 10_000) return cached.value;
  const value = (await osascript('ObjC.import("ApplicationServices"); $.AXIsProcessTrusted()', [], "JavaScript").catch(() => "false")) === "true";
  cached = { value, at: Date.now() };
  return value;
}

/**
 * Ajoute le compagnon à la liste du réglage (demande système) et ouvre Réglages Système à la bonne page : il ne
 * reste qu'à cocher l'interrupteur.
 */
export async function openAccessibilitySettings() {
  cached = undefined;
  await osascript(
    'ObjC.import("ApplicationServices"); $.AXIsProcessTrustedWithOptions($({ AXTrustedCheckOptionPrompt: true }))',
    [],
    "JavaScript",
  ).catch(() => undefined);
  await run("open", ["x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility"]);
}
