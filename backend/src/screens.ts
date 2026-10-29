/**
 * Écrans et fenêtres de l'ordinateur, mêmes formes sur Mac (src/mac/windows.ts) et Windows (src/win/).
 *
 * Écran : `id` stable tant que l'écran reste branché (CGDirectDisplayID sur Mac, numéro DISPLAYn sur Windows).
 * x, y, w, h : place dans l'arrangement des écrans (origine en haut à gauche de l'écran principal).
 */
export type Display = { id: number; label: string; main: boolean; x: number; y: number; w: number; h: number };

export type AppWindow = {
  /** Mac : position dans la liste d'Accessibilité (repli si le titre a changé). Windows : handle de la fenêtre. */
  index: number;
  title: string;
  minimized: boolean;
  fullscreen: boolean;
  /** Écran qui contient le centre de la fenêtre (null : sur un autre bureau). */
  display: number | null;
  /** Sur un autre bureau (Spaces, plein écran caché) : connue par le menu Fenêtre, sans position. */
  elsewhere: boolean;
  /** Vue « Ouvertes » : fenêtre au premier plan de l'ordinateur ; agrandie (Mac : remplit l'écran, ou plein écran). */
  front?: boolean;
  maximized?: boolean;
  /** Mac : page plein écran (Page.id) que cette fenêtre occupe. */
  page?: number;
};

export type WindowsInfo = { app: string; running: boolean; windows: AppWindow[]; displays: Display[] };

/**
 * Vue « Ouvertes » : une appli et ses fenêtres. `key` désigne l'appli pour la piloter (Mac : identifiant de bundle ;
 * Windows : chemin du programme, les fenêtres y étant désignées par leur handle) ; `name` : son nom, qui sert aussi
 * à trouver son icône (menu Démarrer, dossier Applications), absent si l'appli n'y est pas.
 */
export type OpenApp = { key: string; name: string; windows: AppWindow[] };

/**
 * Mac : page du balayage à trois doigts (Space), dans l'ordre du balayage. `display` null : écrans qui partagent leurs
 * pages ; `app` : appli d'une page plein écran.
 */
export type Page = { id: number; display: number | null; fullscreen: boolean; current: boolean; app?: string };

/** `pages` : Mac seulement (absent si illisible). */
export type OpenWindows = { apps: OpenApp[]; displays: Display[]; pages?: Page[] };

/**
 * focus : mettre devant ; place : changer d'écran ou de moitié ; minimize : réduire ; zoom : agrandir, ou rendre sa taille ;
 * fullscreen (Mac) : plein écran sur son propre bureau, ou en sortir.
 */
/** Onglet d'un navigateur de l'ordinateur (choix de la page sur la tablette) ; `active` : affiché dans sa fenêtre. */
export type BrowserTab = { id: string; title: string; url: string; browser: string; active: boolean };

export type OpenAction = "focus" | "place" | "minimize" | "zoom" | "fullscreen" | "close";

/** keep : même place relative sur l'écran cible ; left/right : moitié d'écran ; max : tout l'écran utile. */
export type Layout = "keep" | "left" | "right" | "max";

/** `index` 0 : fenêtre d'un autre bureau (Mac), retrouvée par son titre. */
export type WindowTarget = { index: number; title: string };

/** Écran tel que lu par le script de la plateforme, avant libellé. */
export type RawScreen = {
  id: number;
  name: string;
  builtin: boolean;
  main: boolean;
  frame: { x: number; y: number; w: number; h: number };
};

/** Libellés courts, lisibles de loin : "Mac" pour l'écran intégré d'un Mac, sinon le nom du moniteur. */
export function labelDisplays(raw: RawScreen[]): Display[] {
  const sorted = [...raw].sort((a, b) => a.frame.x - b.frame.x);
  const base = sorted.map((s) => (s.builtin ? "Mac" : s.name.replace(/\s*\(\d+\)$/, "")));
  return sorted.map((s, i) => {
    const twins = base.filter((b) => b === base[i]).length > 1;
    const rank = base.slice(0, i + 1).filter((b) => b === base[i]).length;
    return { id: s.id, label: twins ? `${base[i]} ${rank}` : base[i], main: s.main, ...s.frame };
  });
}

// --- Son, touches multimédia et commandes système : mêmes formes sur Mac (src/mac/) et Windows (src/win/).

export type AudioState = {
  /** null quand la sortie ne se pilote pas (ex. certaines sorties HDMI / DisplayLink). */
  output: number | null;
  muted: boolean;
  input: number;
  micMuted: boolean;
};

export type AudioChange = { output?: number; outputDelta?: number; muted?: boolean; micMuted?: boolean };

/**
 * Tranche du mélangeur (onglet Son du deck) : `id` "master" (sortie), "mic" ou une appli ; `volume` null s'il ne se
 * pilote pas ; `level` : niveau sonore instantané 0-100, null si l'ordinateur ne le donne pas (Mac).
 */
export type MixerChannel = { id: string; name: string; volume: number | null; muted: boolean; level: number | null };
/** `apps` null : pas de volume par appli sur cet ordinateur (Mac). */
export type MixerState = { master: MixerChannel; mic: MixerChannel; apps: MixerChannel[] | null };
export type MixerChange = { id: string; volume?: number; muted?: boolean };
/**
 * Presse-papiers de l'ordinateur, tel que la tablette le lit : `protected` = contenu sensible (mot de passe d'un
 * gestionnaire), jamais lu ; `other` = ni texte ni image (liste de fichiers…).
 */
export type ClipboardContent =
  { kind: "text"; text: string; truncated?: true } | { kind: "image" } | { kind: "empty" } | { kind: "protected" } | { kind: "other" };

export type MediaCommand = "play-pause" | "next" | "previous";

export type SystemCommand = "sleep-display" | "toggle-dark-mode" | "screenshot-clipboard" | "lock" | "mute-mic" | "dnd";

/** Commandes `system` acceptées dans le deck et les touches perso. `mute-mic` et `dnd` basculent l'état courant. */
export const SYSTEM_COMMANDS: SystemCommand[] = ["sleep-display", "toggle-dark-mode", "screenshot-clipboard", "lock", "mute-mic", "dnd"];

/**
 * Symboles d'une tuile (`symbol`) : icônes vectorielles fournies avec la tablette (Material Symbols), affichées à la
 * place de l'emoji (`glyph`, gardé en secours pour les tablettes d'avant les symboles).
 */
export const TILE_SYMBOLS = [
  "lock",
  "screenshot",
  "dnd",
  "mic_off",
  "videocam_off",
  "clipboard",
  "windows",
  "dark_mode",
  "bedtime",
  "play_pause",
  "skip_next",
  "skip_previous",
  "volume_up",
  "volume_off",
  "present",
  "hand",
  "call_end",
  "keyboard",
  "link",
  "apps",
  "search",
  "call",
] as const;
