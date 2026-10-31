import { randomUUID } from "node:crypto";
import { isScreenLocked } from "./screen-lock.ts";

/**
 * Canal Mac → tablette. La tablette ne peut pas être appelée (pas de serveur dessus) :
 * elle garde une requête POST /tablet/poll ouverte (long-polling) et le Mac y répond
 * dès qu'un ordre arrive. Elle renvoie ensuite le résultat de chaque ordre sur /tablet/result.
 * Une seule tablette par Mac : un nouveau poll remplace le précédent.
 *
 * Livraison "au moins une fois" : un ordre reste en attente jusqu'à son résultat (ou l'expiration).
 * En WiFi, une connexion coupée n'est pas toujours vue tout de suite : un ordre remis à un poll mort
 * est renvoyé au poll suivant, et la tablette ignore les identifiants déjà exécutés.
 */

export const MODES = ["home", "deck", "docs", "focus", "camera", "tasks", "settings"] as const;
export const NOISES = ["storm", "forest", "japan", "sea", "lofi", "off"] as const;
export const FOCUS_ACTIONS = ["start", "pause", "reset", "skip"] as const;
/** Thèmes de la tablette (AppTheme dans ui/Themes.kt), dans son ordre : Console (par défaut, nuit), Console jour, Contraste élevé, Hush. */
export const THEMES = ["CONSOLE", "CONSOLE_JOUR", "CONTRASTE", "HUSH"] as const;

/** Réglages de la tablette changés depuis la page de l'ordinateur ; un champ absent reste tel quel. */
export type TabletSettings = {
  theme?: (typeof THEMES)[number];
  /** Luminosité de l'écran, 0,05 à 1. */
  brightness?: number;
  /** Volume du haut-parleur, 0 à 1. */
  volume?: number;
  nightDim?: boolean;
  focusDnd?: boolean;
};

/**
 * Profils et barres de la tablette changés depuis la page de l'ordinateur (routes/local-profiles.ts) : `layout` est la
 * disposition v2 de l'Accueil (HomeLayout.toJson), `topBar` les clés du bandeau du haut dans l'ordre, `rail` la barre de
 * gauche. La tablette vérifie tout et rend son état (`get`) dans `data`.
 */
export type TabletLayoutChange = {
  layout?: Record<string, unknown>;
  topBar?: string[];
  rail?: { labels?: boolean; tools?: string[] };
};

export type TabletDoc = { name: string; path: string; kind: string; size: number; mtimeMs: number };

export type TabletCommand =
  | { type: "mode"; mode: (typeof MODES)[number] }
  | { type: "message"; text: string; speak?: boolean; seconds?: number }
  | { type: "document"; doc: TabletDoc | null }
  | { type: "focus"; action: (typeof FOCUS_ACTIONS)[number]; focusMinutes?: number; breakMinutes?: number }
  | { type: "noise"; kind: (typeof NOISES)[number]; volume?: number }
  | { type: "screenshot" }
  /** Panneau Tablette de la page locale (routes/local.ts). */
  | ({ type: "settings" } & TabletSettings)
  /** Profils et barres de la page locale : lire l'état de la tablette, ou lui envoyer une configuration. */
  | { type: "layout"; action: "get" }
  | ({ type: "layout"; action: "set" } & TabletLayoutChange)
  /** Mise à jour du Mate depuis la page locale : chercher, ou installer la version trouvée. */
  | { type: "update"; action: "check" | "install" }
  /** Appel entrant (src/calls.ts) : carte Répondre / Ignorer sur la tablette, retirée à `ended`. */
  | { type: "call"; state: "ringing" | "ended"; app: string; caller: string | null; video: boolean }
  /** Réunion en cours (src/meeting-watch.ts) : la tablette passe sur son profil Réunion à `start`, en revient à `end`. */
  | { type: "meeting"; state: "start" | "end"; app: string }
  /** Appli au premier plan de l'ordinateur (src/foreground-watch.ts) : la tablette la compare à sa liste d'applis de jeu. */
  | { type: "foreground"; app: string; bundleId: string }
  /** Ordinateur verrouillé ou non (src/screen-lock.ts) : la tablette se couvre d'un écran bloquant, l'enlève au déverrouillage. */
  | { type: "lock"; locked: boolean }
  /** Notification de l'ordinateur (src/notifications.ts), affichée en bandeau et gardée dans l'historique. */
  | { type: "notification"; id: string; app: string; title: string; body: string; at: number }
  /** Agent Claude (src/agents.ts) : une session attend, a fini ou a atteint sa limite ; `clear` : plus rien n'attend. */
  | { type: "agent"; kind: "waiting" | "done" | "limit" | "clear"; session?: string; name?: string; askId?: string; waiting: number };

/** `received` : la tablette a répondu (même en échec, ex. message affiché mais pas lu faute de voix). */
export type TabletResult = { ok: boolean; error?: string; data?: Record<string, unknown>; received?: boolean };

type Queued = { id: string; command: TabletCommand };

/** Ordres envoyés mais sans résultat : redonnés à chaque poll jusqu'au résultat. */
const pending: Queued[] = [];
const inflight = new Map<string, { resolve: (r: TabletResult) => void; timer: NodeJS.Timeout }>();
let waiter: ((commands: Queued[]) => void) | undefined;
let lastSeen = 0;
let lastState: unknown = null;
let lastAddress: { ip: string; at: number } | undefined;

/** Connectée = un poll est en attente, ou la tablette vient d'en finir un (elle exécute un ordre). */
const isConnected = () => waiter !== undefined || Date.now() - lastSeen < 35_000;

export function tabletStatus() {
  return { connected: isConnected(), lastSeenMs: lastSeen || null, state: lastState };
}

/** Adresse WiFi d'où la tablette interroge cet ordinateur (poll) : elle y sert les installeurs (port 8080). */
export function noteTabletAddress(ip: string) {
  lastAddress = { ip: ip.replace(/^::ffff:/, ""), at: Date.now() };
}

/** Dernière adresse vue il y a moins de 2 minutes ; null en USB (127.0.0.1) ou sans tablette. */
export function tabletAddress(): string | null {
  const a = lastAddress;
  return a && Date.now() - a.at < 120_000 && /^\d+\.\d+\.\d+\.\d+$/.test(a.ip) && !a.ip.startsWith("127.") ? a.ip : null;
}

function flush() {
  if (!waiter || pending.length === 0) return;
  const deliver = waiter;
  waiter = undefined;
  deliver([...pending]);
}

function drop(id: string) {
  const i = pending.findIndex((q) => q.id === id);
  if (i >= 0) pending.splice(i, 1);
}

/** Seuls ordres remis pendant le verrouillage : rien de ce qui montre ou dit un contenu (rappels redonnés après). */
const WHILE_LOCKED = new Set<TabletCommand["type"]>(["lock", "meeting"]);

/** Envoie un ordre et attend la réponse de la tablette. */
export function sendToTablet(command: TabletCommand, timeoutMs = 15_000): Promise<TabletResult> {
  if (isScreenLocked() && !WHILE_LOCKED.has(command.type)) {
    return Promise.resolve({ ok: false, error: "Ordinateur verrouillé : la tablette n'affiche rien" });
  }
  if (!isConnected()) {
    return Promise.resolve({ ok: false, error: "Tablette non connectée (appli fermée, ou backend injoignable depuis la tablette)" });
  }
  const id = randomUUID();
  return new Promise((resolve) => {
    const timer = setTimeout(() => {
      inflight.delete(id);
      drop(id);
      resolve({ ok: false, error: "La tablette n'a pas répondu à temps" });
    }, timeoutMs);
    inflight.set(id, { resolve, timer });
    pending.push({ id, command });
    flush();
  });
}

/** Appelé par la tablette : renvoie les ordres en attente, ou attend jusqu'à `waitMs`. */
export function pollCommands(state: unknown, waitMs: number, signal: AbortSignal): Promise<Queued[]> {
  lastSeen = Date.now();
  lastState = state;
  if (pending.length > 0) return Promise.resolve([...pending]);
  waiter?.([]);
  return new Promise((resolve) => {
    const done = (commands: Queued[]) => {
      clearTimeout(timer);
      if (waiter === done) waiter = undefined;
      lastSeen = Date.now();
      resolve(commands);
    };
    const timer = setTimeout(() => done([]), waitMs);
    waiter = done;
    // Tablette déconnectée pendant l'attente : on ne lui confie plus d'ordre.
    signal.addEventListener("abort", () => {
      if (waiter === done) done([]);
    });
  });
}

/** État envoyé avec chaque résultat : tablette_etat est à jour juste après un ordre. */
export function reportState(state: unknown) {
  lastSeen = Date.now();
  lastState = state;
}

export function completeCommand(id: string, result: TabletResult): boolean {
  const waiting = inflight.get(id);
  if (!waiting) return false;
  clearTimeout(waiting.timer);
  inflight.delete(id);
  drop(id);
  waiting.resolve({ ...result, received: true });
  return true;
}
