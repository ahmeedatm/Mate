import type { BrowserTab } from "./screens.ts";

/**
 * Onglets envoyés par l'extension « Compagnon Unowhy » (backend/extension/) de chaque navigateur Chromium de
 * l'ordinateur. Sur Windows, seule source fiable : UI Automation ne voit rien quand Chromium coupe son accessibilité
 * (Opera GX), et le fichier de session est verrouillé tant que le navigateur tourne.
 */

export type ExtensionReport = {
  browser: string;
  /** Dernière fois qu'une fenêtre de ce navigateur est passée devant (ms) : le plus récent est en tête. */
  focusedAt: number;
  focusedWindow: number | null;
  tabs: { id: number; window: number; index: number; title: string; url: string; active: boolean }[];
};

/** L'extension renvoie la liste chaque minute : au-delà, le navigateur est fermé. */
const STALE_MS = 3 * 60_000;
const reports = new Map<string, ExtensionReport & { at: number }>();

export function receiveTabs(report: ExtensionReport) {
  reports.set(report.browser, { ...report, at: Date.now() });
}

/** Onglets de tous les navigateurs, celui passé devant le plus récemment d'abord, sa fenêtre devant en tête. */
export function extensionTabs(): BrowserTab[] | undefined {
  const fresh = [...reports.values()].filter((r) => Date.now() - r.at < STALE_MS).sort((a, b) => b.focusedAt - a.focusedAt);
  if (fresh.length === 0) return undefined;
  return fresh.flatMap((r) =>
    [...r.tabs]
      .sort(
        (a, b) => Number(b.window === r.focusedWindow) - Number(a.window === r.focusedWindow) || a.window - b.window || a.index - b.index,
      )
      .map((t) => ({ id: `${r.browser}|${t.id}`, title: t.title, url: t.url, browser: r.browser, active: t.active })),
  );
}

/**
 * Page affichée sur l'ordinateur : onglet actif de la fenêtre devant, dans le navigateur le plus récent ; si c'est
 * une page interne (opera://extensions…), l'onglet web actif suivant.
 */
export function extensionFrontPage() {
  const front = extensionTabs()?.find((t) => t.active && /^https?:\/\//.test(t.url));
  return front && { found: true, url: front.url, title: front.title, browser: front.browser };
}
