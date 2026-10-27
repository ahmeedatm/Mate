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

// --- Ordres de lecture (page Média de la tablette) : l'extension les attend en long-polling, puis agit dans la page.
// Sur Mac, le seul moyen de relancer Opera GX en pause : ni MediaRemote ni la touche multimédia simulée n'y arrivent.

export type ExtensionCommand = { action: "play-pause" | "next" | "previous" | "seek"; seconds?: number; title?: string };

/** Demande gardée ouverte au plus 20 s : l'extension en refait une aussitôt. */
const HOLD_MS = 20_000;
const queued = new Map<string, ExtensionCommand[]>();
const waiting = new Map<string, (commands: ExtensionCommand[]) => void>();
const polledAt = new Map<string, number>();

/** « Google Chrome » (MediaRemote, menu Démarrer) = « Chrome » (extension), « Microsoft Edge » = « Edge ». */
const browserKey = (name: string) => name.toLowerCase().replace(/^(microsoft|google) /, "");

/** Ordres pour ce navigateur, dès qu'il y en a, ou liste vide au bout de `holdMs`. */
export function waitCommands(browser: string, signal?: AbortSignal, holdMs = HOLD_MS): Promise<ExtensionCommand[]> {
  const key = browserKey(browser);
  polledAt.set(key, Date.now());
  const ready = queued.get(key);
  if (ready?.length) {
    queued.delete(key);
    return Promise.resolve(ready);
  }
  // Une seule attente par navigateur : l'ancienne (onglet du service worker relancé) se termine à vide.
  waiting.get(key)?.([]);
  return new Promise((resolve) => {
    const done = (commands: ExtensionCommand[]) => {
      clearTimeout(timer);
      if (waiting.get(key) === done) waiting.delete(key);
      polledAt.set(key, Date.now());
      resolve(commands);
    };
    const timer = setTimeout(() => done([]), holdMs);
    waiting.set(key, done);
    signal?.addEventListener("abort", () => done([]), { once: true });
  });
}

/** Ordre pour l'extension de ce navigateur ; false si elle n'écoute pas (pas installée, navigateur fermé). */
export function sendToExtension(browser: string, command: ExtensionCommand): boolean {
  const key = browserKey(browser);
  const waiter = waiting.get(key);
  if (waiter) {
    waiter([command]);
    return true;
  }
  // Entre deux attentes (l'extension exécute un ordre) : gardé pour la suivante.
  if (Date.now() - (polledAt.get(key) ?? 0) > 5_000) return false;
  queued.set(key, [...(queued.get(key) ?? []), command]);
  return true;
}
