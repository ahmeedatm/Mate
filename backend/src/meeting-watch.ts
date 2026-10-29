/**
 * Réunion en cours (Deck V2) : l'ordinateur prévient la tablette qu'un appel commence ou finit (ordre `meeting` du
 * canal /tablet), pour qu'elle passe d'elle-même sur son profil Réunion. Suivi toutes les `intervalMs` (3 s), seulement
 * tant qu'une tablette est connectée ; un ordre par changement d'état. Tablette revenue en pleine réunion : le début
 * lui est renvoyé. Les dépendances sont passées par l'appelant (server.ts), ce qui garde ce module testable seul.
 */
export type MeetingEvent = { state: "start" | "end"; app: string };

export type MeetingWatchDeps = {
  /** Une tablette écoute le canal en ce moment. */
  connected: () => boolean;
  /** Applis des pages d'appel du deck qui utilisent le micro (même calcul que `apps` de GET /deck/live). */
  liveApps: () => Promise<string[]>;
  intervalMs?: number;
};

/** Démarre la veille ; renvoie la fonction qui l'arrête. */
export function startMeetingWatch(notify: (event: MeetingEvent) => void, deps: MeetingWatchDeps): () => void {
  const intervalMs = deps.intervalMs ?? 3000;
  let current: string | null = null;
  let wasConnected = false;
  let stopped = false;
  let timer: NodeJS.Timeout | undefined;

  const tick = async () => {
    const connected = deps.connected();
    // Tablette (re)venue : elle ne sait rien de l'appel en cours, on le lui redit.
    if (connected && !wasConnected) current = null;
    wasConnected = connected;
    if (connected) {
      try {
        const next = (await deps.liveApps())[0] ?? null;
        if (next !== current && !stopped) {
          if (current) notify({ state: "end", app: current });
          if (next) notify({ state: "start", app: next });
          current = next;
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
