import { osascript } from "./run.ts";

/**
 * Ne pas déranger sur Mac : macOS n'a pas de commande pour la concentration. Le compagnon passe par le Centre de
 * contrôle (Accessibilité, déjà accordée pour les fenêtres) : il l'ouvre, bascule l'entrée Ne pas déranger du
 * détail de la Concentration et le referme (~1 s à l'écran). Éléments trouvés par leur identifiant, pas par leur
 * texte : marche quelle que soit la langue du Mac (vérifié sur macOS 15).
 * État lu : la tuile Concentration (une autre concentration déjà active compte comme « déjà au calme », on n'y
 * touche pas).
 * Le panneau s'ouvre à chaque lecture : l'état n'est lu qu'une fois, puis gardé en mémoire et suivi par ce que le
 * compagnon pose (l'Accueil le redemande chaque minute : le Centre de contrôle clignotait). Un changement fait à
 * la main sur le Mac n'est donc vu qu'au prochain démarrage du compagnon. Un seul script à la fois : deux à la
 * connexion de la tablette se renvoyaient le panneau, resté ouvert.
 */
const SCRIPT = `
function run(argv) {
  const want = argv[0];
  const p = Application("System Events").processes.byName("ControlCenter");
  const id = (e) => { try { return e.attributes["AXIdentifier"].value(); } catch (x) { return ""; } };
  const until = (test) => { for (let i = 0; i < 30; i++) { const v = test(); if (v) return v; delay(0.05); } return undefined; };
  const find = (ident) => until(() => {
    try { return p.windows[0].groups[0].checkboxes().find((c) => id(c) === ident); } catch (x) { return undefined; }
  });
  const cc = p.menuBars[0].menuBarItems().find((i) => id(i) === "com.apple.menuextra.controlcenter");
  if (!cc) throw new Error("no-control-center");
  cc.actions["AXPress"].perform();
  try {
    const tile = find("controlcenter-focus-modes");
    if (!tile) throw new Error("no-focus-tile");
    if (want === "get") return JSON.stringify({ on: tile.value() === 1 });
    // « Afficher les détails » : seule action de la tuile autre qu'AXPress (son nom est traduit).
    tile.actions().find((a) => a.name() !== "AXPress").perform();
    const dnd = find("focus-mode-activity-com.apple.donotdisturb.mode.default");
    if (!dnd) throw new Error("no-dnd-entry");
    if ((dnd.value() === 1) !== (want === "on")) dnd.actions["AXPress"].perform();
    return JSON.stringify({ on: want === "on" });
  } finally {
    // Une pression referme le panneau, ou ne fait que quitter le détail : on insiste, mais seulement une fois sa
    // fermeture finie (réappuyer pendant l'animation le rouvrait).
    const closed = () => { try { return p.windows().length === 0; } catch (x) { return true; } };
    for (let i = 0; i < 3 && !closed(); i++) { cc.actions["AXPress"].perform(); until(closed); }
  }
}`;

let queue: Promise<unknown> = Promise.resolve();
let known: boolean | undefined;

function controlCenter(want: "get" | "on" | "off"): Promise<boolean> {
  const run = queue.then(async () => {
    if (want === "get" && known !== undefined) return known;
    known = JSON.parse(await osascript(SCRIPT, [want], "JavaScript")).on as boolean;
    return known;
  });
  queue = run.catch(() => undefined);
  return run;
}

export const getDnd = () => controlCenter("get");

export async function setDnd(on: boolean): Promise<boolean> {
  return controlCenter(on ? "on" : "off");
}
