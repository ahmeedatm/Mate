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
};

export type WindowsInfo = { app: string; running: boolean; windows: AppWindow[]; displays: Display[] };

/** keep : même place relative sur l'écran cible ; left/right : moitié d'écran ; max : tout l'écran utile. */
export type Layout = "keep" | "left" | "right" | "max";

/** `index` 0 : fenêtre d'un autre bureau (Mac), retrouvée par son titre. */
export type WindowTarget = { index: number; title: string };

/** Écran tel que lu par le script de la plateforme, avant libellé. */
export type RawScreen = { id: number; name: string; builtin: boolean; main: boolean; frame: { x: number; y: number; w: number; h: number } };

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
