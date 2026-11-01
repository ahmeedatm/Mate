/**
 * Appli au premier plan de l'ordinateur (page par appli) : l'ordinateur dit à la tablette quelle appli est devant (ordre
 * `foreground` du canal /tablet), elle compare à sa liste d'applis de jeu et passe sur l'écran choisi. La liste vit sur
 * la tablette : rien à synchroniser. Suivi toutes les `intervalMs` (2 s), seulement tant qu'une tablette est connectée ;
 * un ordre par changement d'appli, et un ordre de plus quand la tablette (re)vient, pour qu'elle sache où on en est.
 * Les dépendances sont passées par l'appelant (server.ts), ce qui garde ce module testable seul.
 */
/** `file` : nom du fichier de l'appli tel que le catalogue la liste (Mac : le .app, quelle que soit la langue du système). */
export type ForegroundEvent = { app: string; bundleId: string; file?: string };

export type ForegroundWatchDeps = {
  /** Une tablette écoute le canal en ce moment. */
  connected: () => boolean;
  /** Appli au premier plan (`frontApp` de os.ts). */
  front: () => Promise<{ name: string; bundleId: string; file?: string }>;
  intervalMs?: number;
};

/** Démarre la veille ; renvoie la fonction qui l'arrête. */
export function startForegroundWatch(notify: (event: ForegroundEvent) => void, deps: ForegroundWatchDeps): () => void {
  const intervalMs = deps.intervalMs ?? 2000;
  let current: string | null = null;
  let wasConnected = false;
  let stopped = false;
  let timer: NodeJS.Timeout | undefined;

  const tick = async () => {
    const connected = deps.connected();
    if (connected && !wasConnected) current = null;
    wasConnected = connected;
    if (connected) {
      try {
        const next = await deps.front();
        const key = `${next.bundleId}|${next.name}|${next.file ?? ""}`;
        if (key !== current && !stopped) {
          current = key;
          notify({ app: next.name, bundleId: next.bundleId, ...(next.file ? { file: next.file } : {}) });
        }
      } catch {
        // Lecture ratée (pilotage indisponible un instant) : on garde l'état et on relit au tour suivant.
      }
    }
    if (!stopped) timer = setTimeout(() => void tick(), intervalMs);
  };

  timer = setTimeout(() => void tick(), intervalMs);
  return () => {
    stopped = true;
    clearTimeout(timer);
  };
}
