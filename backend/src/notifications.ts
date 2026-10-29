/**
 * Notifications de l'ordinateur sur la tablette (spec docs/superpowers/specs/2026-10-29-notifications-design.md) :
 * surveillance de la base de notifications du système (lecture : src/win/notifications.ts, src/mac/notifications.ts,
 * via os.ts), historique en mémoire, envoi en direct à la tablette. Rien sur le disque, rien du contenu dans le journal.
 */

/** Notification lue dans la base du système. `at` : heure d'arrivée (ms depuis 1970). */
export type RawNotification = { app: string; title: string; body: string; at: number };

export type NotificationItem = RawNotification & { id: string };

/** Lecture de la base : `cursor` absent = premier appel (notifications récentes), sinon celles arrivées depuis. */
export type NotificationBatch = { cursor: string; items: RawNotification[] };

export const NOTIFICATIONS_MAX = 100;
export const NOTIFICATIONS_TTL_MS = 24 * 60 * 60 * 1000;
/** Titre et texte coupés : une notification n'a pas à transporter un mail entier. */
export const NOTIFICATION_TEXT_MAX = 1000;

const ENTITIES: Record<string, string> = { amp: "&", lt: "<", gt: ">", quot: '"', apos: "'" };

function decode(text: string) {
  return text.replace(/&(#x[0-9a-f]+|#\d+|[a-z]+);/gi, (whole, code: string) => {
    if (code[0] !== "#") return ENTITIES[code.toLowerCase()] ?? whole;
    const n = code[1] === "x" || code[1] === "X" ? parseInt(code.slice(2), 16) : parseInt(code.slice(1), 10);
    return Number.isFinite(n) && n > 0 && n <= 0x10ffff ? String.fromCodePoint(n) : whole;
  });
}

function clip(text: string) {
  const clean = text.replace(/\s+/g, " ").trim();
  return clean.length > NOTIFICATION_TEXT_MAX ? clean.slice(0, NOTIFICATION_TEXT_MAX) + "…" : clean;
}

/**
 * Titre et texte d'un toast Windows (XML de la colonne Payload) : premier `<text>` = titre, les suivants = texte, sauf
 * l'attribution (`placement="attribution"`, « via Outlook »). Sans `<text>` : titre et texte vides.
 */
export function parseToast(xml: string): { title: string; body: string } {
  const texts: string[] = [];
  for (const match of xml.matchAll(/<text\b([^>]*?)(?:\/>|>([\s\S]*?)<\/text>)/gi)) {
    if (/placement\s*=\s*["']attribution["']/i.test(match[1])) continue;
    const value = clip(decode((match[2] ?? "").replace(/<!\[CDATA\[([\s\S]*?)\]\]>/g, "$1")));
    if (value) texts.push(value);
  }
  return { title: texts[0] ?? "", body: clip(texts.slice(1).join("\n")) };
}

/** Nom lisible d'une appli d'après son identifiant (AppUserModelID Windows, bundle id Mac), faute de mieux. */
export function appNameFromId(id: string): string {
  const tail = (id.includes("!") ? id.slice(id.lastIndexOf("!") + 1) : id).replace(/\.exe$/i, "");
  const last =
    tail
      .split(/[.\\/]/)
      .filter(Boolean)
      .at(-1) ?? tail;
  const name = last.replace(/^Microsoft/, "").replace(/for(Windows|Mac)$/i, "");
  return name || id;
}

export class NotificationHistory {
  private items: NotificationItem[] = [];
  private next = 1;

  constructor(private readonly now: () => number = Date.now) {}

  /** Ajoute une notification et la renvoie avec son identifiant ; sans titre ni texte : ignorée (undefined). */
  add(raw: RawNotification): NotificationItem | undefined {
    if (!raw.title && !raw.body) return undefined;
    const item = { id: String(this.next++), app: raw.app, title: clip(raw.title), body: clip(raw.body), at: raw.at };
    this.items.push(item);
    this.items.sort((a, b) => b.at - a.at);
    this.items.length = Math.min(this.items.length, NOTIFICATIONS_MAX);
    return this.items.includes(item) ? item : undefined;
  }

  /** La plus récente en premier, sans celles de plus de 24 h. */
  list(): NotificationItem[] {
    const since = this.now() - NOTIFICATIONS_TTL_MS;
    this.items = this.items.filter((item) => item.at > since);
    return [...this.items];
  }

  remove(id: string): boolean {
    const before = this.items.length;
    this.items = this.items.filter((item) => item.id !== id);
    return this.items.length < before;
  }

  clear() {
    this.items = [];
  }
}

export const notificationHistory = new NotificationHistory();

/**
 * Surveille la base de notifications toutes les `intervalMs`. Premier passage : les notifications récentes encore dans
 * la base vont dans l'historique sans être envoyées ; ensuite chaque nouvelle part aussi vers la tablette (`push`, sans
 * attendre). Base illisible (droits, appli fermée) : le passage suivant réessaie, sans journal du contenu. Renvoie l'arrêt.
 */
export function watchNotifications(
  read: (cursor?: string) => Promise<NotificationBatch>,
  history: NotificationHistory,
  push: (item: NotificationItem) => void,
  intervalMs: number,
  onError: (error: Error) => void = () => {},
): () => void {
  let cursor: string | undefined;
  let busy = false;
  let lastError = "";
  const tick = async () => {
    if (busy) return;
    busy = true;
    try {
      const first = cursor === undefined;
      const batch = await read(cursor);
      cursor = batch.cursor;
      lastError = "";
      for (const raw of [...batch.items].sort((a, b) => a.at - b.at)) {
        const item = history.add(raw);
        if (item && !first) push(item);
      }
    } catch (e) {
      // Une seule fois par cause : la même erreur toutes les 2 s noierait le journal. Le message ne cite pas de contenu.
      const message = (e as Error).message;
      if (message !== lastError) onError(e as Error);
      lastError = message;
    } finally {
      busy = false;
    }
  };
  void tick();
  const timer = setInterval(() => void tick(), intervalMs);
  timer.unref();
  return () => clearInterval(timer);
}
