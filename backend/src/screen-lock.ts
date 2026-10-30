/**
 * Ordinateur verrouillé : la tablette devient inutilisable. La session est relue toutes les `intervalMs` (1 s), même sans
 * tablette, car `isScreenLocked` ferme aussi l'API (server.ts : 423 sur toute route à jeton sauf le canal /tablet). Chaque
 * changement est poussé à la tablette (ordre `lock` du canal /tablet). Elle garde le dernier état reçu même hors ligne et
 * l'annonce dans l'état de son poll (`locked`) : à chaque tour, une tablette qui ne montre pas le bon (revenue, ordre perdu,
 * 423 reçu juste après le déverrouillage) reçoit l'état courant. Lecture ratée : l'état d'avant est gardé.
 */
let locked = false;

/** Dernier état lu ; faux tant que rien n'a été lu. */
export const isScreenLocked = () => locked;

export type ScreenLockDeps = {
  /** Session verrouillée ; null : illisible (os.ts : screenLocked). */
  read: () => Promise<boolean | null>;
  /** Une tablette écoute le canal en ce moment. */
  connected: () => boolean;
  /** Écran bloquant affiché par la tablette, d'après l'état de son dernier poll ; undefined : elle ne le dit pas. */
  tabletLocked: () => boolean | undefined;
  intervalMs?: number;
};

/** Démarre la veille ; renvoie la fonction qui l'arrête. `notify` rend vrai quand la tablette a reçu l'ordre. */
export function startScreenLockWatch(notify: (locked: boolean) => Promise<boolean>, deps: ScreenLockDeps): () => void {
  const intervalMs = deps.intervalMs ?? 1000;
  /** Dernier état reçu par une tablette qui ne l'annonce pas (ancienne appli) ; undefined : rien de sûr. */
  let told: boolean | undefined;
  let sending = false;
  let stopped = false;
  let timer: NodeJS.Timeout | undefined;

  const tick = async () => {
    try {
      const next = await deps.read();
      if (next !== null) locked = next;
    } catch {
      // Pilotage indisponible un instant : on garde l'état et on relit au tour suivant.
    }
    if (!deps.connected()) told = undefined;
    else if (!sending && !stopped && (deps.tabletLocked() ?? told ?? false) !== locked) {
      const sent = locked;
      sending = true;
      void notify(sent).then((received) => {
        sending = false;
        told = received ? sent : undefined;
      });
    }
    if (!stopped) timer = setTimeout(() => void tick(), intervalMs);
  };

  void tick();
  return () => {
    stopped = true;
    clearTimeout(timer);
  };
}
