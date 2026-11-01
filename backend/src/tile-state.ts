/**
 * Touches qui montrent l'état réel de ce qu'elles pilotent (`state` d'une tuile, src/deck.ts) : micro coupé, Ne pas
 * déranger, appli lancée. L'ordinateur lit ces états toutes les `intervalMs` (2 s), seulement tant qu'une tablette est
 * connectée, et lui envoie les identifiants des touches allumées (ordre `tilestate` du canal /tablet) à chaque
 * changement, et une fois quand elle (re)vient. Les dépendances sont passées par l'appelant, ce qui garde ce module
 * testable seul.
 */
import { appOf, loadDeck, type Tile } from "./deck.ts";
import { getAudio, getDnd, isAppRunning } from "./os.ts";

export type StateReaders = {
  micMuted: () => Promise<boolean>;
  dnd: () => Promise<boolean | null>;
  running: (app: string) => Promise<boolean>;
};

const systemReaders: StateReaders = { micMuted: async () => (await getAudio()).micMuted, dnd: getDnd, running: isAppRunning };

/** Une lecture par type d'état et par appli pour tout le tour, quel que soit le nombre de touches qui la partagent. */
function sharedReaders(read: StateReaders): StateReaders {
  const once = <T>(fn: () => Promise<T>) => {
    let value: Promise<T> | undefined;
    return () => (value ??= fn());
  };
  const running = new Map<string, Promise<boolean>>();
  return {
    micMuted: once(read.micMuted),
    dnd: once(read.dnd),
    running: (app) => {
      const key = app.toLowerCase();
      if (!running.has(key)) running.set(key, read.running(app));
      return running.get(key)!;
    },
  };
}

/**
 * Identifiants des touches allumées, parmi `tiles` ; une lecture qui échoue laisse sa touche éteinte.
 * shortcut: Mac, la première lecture de Ne pas déranger ouvre un instant le Centre de contrôle (src/mac/dnd.ts), puis
 * l'état est gardé en mémoire et ne suit que les bascules faites avec Mate ; à revoir si une lecture sans panneau existe.
 */
export async function litTiles(tiles: Tile[], read: StateReaders = systemReaders): Promise<string[]> {
  const shared = sharedReaders(read);
  const lit = await Promise.all(
    tiles.map(async (tile) => {
      try {
        switch (tile.state) {
          case "mic-muted":
            return await shared.micMuted();
          case "dnd":
            return (await shared.dnd()) === true;
          case "running": {
            const app = appOf(tile.action);
            return app ? await shared.running(app) : false;
          }
        }
      } catch {
        // Lecture ratée (pilotage indisponible un instant) : la touche reste éteinte, les autres ne dépendent pas d'elle.
      }
      return false;
    }),
  );
  return tiles.filter((_, i) => lit[i]).map((t) => t.id);
}

/** Touches du deck qui ont un `state`. */
export async function stateTiles(): Promise<Tile[]> {
  return (await loadDeck()).flatMap((p) => p.tiles).filter((t): t is Tile => !("empty" in t) && t.state !== undefined);
}

/** Une lecture bloquée (osascript, helper Windows) ne doit pas arrêter la veille. */
const READ_TIMEOUT_MS = 5000;

export type TileStateWatchDeps = {
  /** Une tablette écoute le canal en ce moment. */
  connected: () => boolean;
  /** Identifiants des touches allumées (`litTiles` sur `stateTiles`). */
  read: () => Promise<string[]>;
  intervalMs?: number;
  /** Délai maximal d'une lecture (5 s par défaut). */
  readTimeoutMs?: number;
};

/** Démarre la veille ; renvoie la fonction qui l'arrête. */
export function startTileStateWatch(notify: (lit: string[]) => void, deps: TileStateWatchDeps): () => void {
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
        const lit = await Promise.race([
          deps.read(),
          new Promise<never>((_, reject) =>
            setTimeout(() => reject(new Error("lecture trop longue")), deps.readTimeoutMs ?? READ_TIMEOUT_MS).unref(),
          ),
        ]);
        const key = lit.join("|");
        if (key !== current && !stopped) {
          current = key;
          notify(lit);
        }
      } catch {
        // Lecture ratée ou trop longue : on garde l'état et on relit au tour suivant.
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
