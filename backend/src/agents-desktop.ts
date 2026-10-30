import { AnswerError } from "./agents.ts";

/**
 * Agents Claude sur Windows, sans Orca : retrouver la fenêtre du terminal d'une session (par son pid), l'amener devant,
 * et y taper un message ou Échap seulement quand on est sûr que les touches arrivent à cette session.
 *
 * La fenêtre se cherche dans cet ordre (op `ancestors` du pilote, helper/Native.Processes.cs) :
 * 1. la console de la session : fenêtre de la console classique (conhost), ou fenêtre de Windows Terminal qui l'héberge
 *    (propriétaire de sa pseudo-console ; marche aussi quand Windows confie à Terminal une console ouverte depuis le menu
 *    Démarrer : la session n'est alors pas une descendante de Terminal) ;
 * 2. le premier parent qui a une fenêtre (VS Code et ses semblables, Claude Desktop, un IDE) ;
 * 3. à défaut, la fenêtre la plus en avant d'un hôte connu (`WINDOWS_HOSTS`) : seulement pour l'amener devant.
 *
 * Écrire n'est permis que dans deux cas, vérifiés juste avant chaque frappe (fenêtre de devant relue par l'op `front`) :
 * - console classique : une fenêtre = une console, et la session interactive y est au premier plan ;
 * - Windows Terminal : une fenêtre a plusieurs onglets et rien ne dit lequel est affiché, sauf le titre de la fenêtre
 *   (celui de l'onglet affiché) que Claude Code règle à son titre de session. Il doit être égal au nom ou au titre de la
 *   session (sans le symbole d'état que Claude met devant) ; sinon refus.
 * Jamais dans un éditeur (VS Code : le clavier peut être dans un fichier), Claude Desktop, un IDE, ni par la fenêtre
 * devinée. Si la fenêtre de devant change entre deux frappes, on s'arrête là (409), sans Entrée.
 */

export type DeskWindow = { hwnd: number; pid: number; exe: string; title: string; cls: string };
export type DeskLookup = { console: DeskWindow | null; chain: (DeskWindow & { ppid: number })[]; fallback: DeskWindow | null };
/** `console` : console classique ; `tabs` : Windows Terminal ; `window` : fenêtre d'un parent ; `guess` : hôte deviné. */
export type DeskSpot = { window: DeskWindow; kind: "console" | "tabs" | "window" | "guess"; canSend: boolean };

export type DesktopDeps = {
  processWindows: (pid: number, hosts: string[]) => Promise<DeskLookup>;
  frontWindow: () => Promise<DeskWindow>;
  raiseWindow: (hwnd: number) => Promise<unknown>;
  /** Colle un texte dans l'appli de devant (presse-papiers rendu ensuite). */
  typeText: (text: string) => Promise<void>;
  sendKeys: (combo: string) => Promise<void>;
};

/** Programmes (exe en minuscules) où tourne Claude Code sur Windows : terminaux, éditeurs, Claude Desktop, Orca. */
export const WINDOWS_HOSTS = [
  "windowsterminal.exe",
  "openconsole.exe",
  "conhost.exe",
  "cmd.exe",
  "powershell.exe",
  "pwsh.exe",
  "bash.exe",
  "mintty.exe",
  "wezterm-gui.exe",
  "alacritty.exe",
  "tabby.exe",
  "hyper.exe",
  "warp.exe",
  "code.exe",
  "code - insiders.exe",
  "cursor.exe",
  "windsurf.exe",
  "zed.exe",
  "idea64.exe",
  "pycharm64.exe",
  "webstorm64.exe",
  "rider64.exe",
  "claude.exe",
  "orca.exe",
];

const CONSOLE_CLASS = "ConsoleWindowClass";
const TERMINAL_CLASS = "CASCADIA_HOSTING_WINDOW_CLASS";

/** Titre sans le symbole d'état de Claude (« ✳ », points braille) ni les espaces autour, en minuscules. */
const bare = (title: string) =>
  title
    .normalize("NFC")
    .replace(/^[^\p{L}\p{N}]+/u, "")
    .trim()
    .toLowerCase();

/** Le titre de la fenêtre est celui de la session : égal (pas « contient » : un shell affiche souvent son dossier). */
export function titleMatches(windowTitle: string, names: string[]): boolean {
  const title = bare(windowTitle);
  return title.length > 0 && names.some((n) => bare(n).length >= 4 && bare(n) === title);
}

export function spotOf(found: DeskLookup, names: string[]): DeskSpot | null {
  const c = found.console;
  if (c?.hwnd) {
    if (c.cls === CONSOLE_CLASS) return { window: c, kind: "console", canSend: true };
    if (c.cls === TERMINAL_CLASS) return { window: c, kind: "tabs", canSend: titleMatches(c.title, names) };
    return { window: c, kind: "window", canSend: false };
  }
  const owner = found.chain.find((l) => l.hwnd);
  if (owner) return { window: owner, kind: "window", canSend: false };
  if (found.fallback?.hwnd) return { window: found.fallback, kind: "guess", canSend: false };
  return null;
}

export function createDesktop(deps: DesktopDeps) {
  async function locate(pid: number, names: string[]): Promise<DeskSpot | null> {
    try {
      return spotOf(await deps.processWindows(pid, WINDOWS_HOSTS), names);
    } catch {
      return null;
    }
  }

  async function raise(spot: DeskSpot) {
    try {
      await deps.raiseWindow(spot.window.hwnd);
    } catch {
      throw new AnswerError("Impossible d'amener la fenêtre de cette session devant", 409);
    }
  }

  async function focus(pid: number, names: string[]) {
    const spot = await locate(pid, names);
    if (!spot) throw new AnswerError("Je ne trouve pas la fenêtre de cette session sur l'ordinateur", 409);
    await raise(spot);
  }

  /** Fenêtre de la session devant (et bon onglet pour Terminal), puis chaque frappe vérifiée juste avant. */
  async function typeInto(pid: number, names: string[], steps: ({ keys: string } | { text: string })[]) {
    const spot = await locate(pid, names);
    if (!spot?.canSend)
      throw new AnswerError(
        spot?.kind === "tabs"
          ? "L'onglet de cette session n'est pas celui affiché dans le Terminal : affiche-le, ou écris-lui sur l'ordinateur"
          : "Cette session n'est pas dans une fenêtre de terminal où écrire sans risque : écris-lui sur l'ordinateur",
        409,
      );
    await raise(spot);
    let typed = false;
    for (const step of steps) {
      const front = await deps.frontWindow().catch(() => null);
      const ours = front?.hwnd === spot.window.hwnd && (spot.kind !== "tabs" || titleMatches(front.title, names));
      if (!ours)
        throw new AnswerError(
          typed
            ? "La fenêtre de cette session n'est plus devant : message interrompu, sans Entrée"
            : "La fenêtre de cette session n'a pas pu passer devant : rien n'a été tapé",
          409,
        );
      if ("keys" in step) await deps.sendKeys(step.keys);
      else await deps.typeText(step.text);
      typed = true;
    }
  }

  return {
    locate,
    focus,
    /** Vide la saisie (Ctrl-U, comme pour Orca), colle la ligne, Entrée. */
    send: (pid: number, names: string[], line: string) => typeInto(pid, names, [{ keys: "ctrl+u" }, { text: line }, { keys: "enter" }]),
    /** Échap (jamais Ctrl-C : deux de suite quittent Claude). */
    interrupt: (pid: number, names: string[]) => typeInto(pid, names, [{ keys: "escape" }]),
  };
}

export type Desktop = ReturnType<typeof createDesktop>;
