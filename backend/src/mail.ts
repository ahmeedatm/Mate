import { microsoftEvents, microsoftMailAccess } from "./accounts.ts";

/**
 * Page Outlook du Deck : compteurs du courrier, derniers non lus et prochain rendez-vous, lus par Microsoft Graph
 * avec le compte Microsoft relié sur la page de l'ordinateur (scopes Mail.Read et Calendars.Read, src/accounts.ts).
 * Lecture seule : rien n'est écrit dans la boîte, le corps des messages n'est jamais demandé.
 */

const GRAPH = "https://graph.microsoft.com/v1.0";
const RECENT_COUNT = 5;
const NEXT_DAYS = 7;

export type MailSummary =
  /** Aucun compte Microsoft relié. */
  | { status: "no-account" }
  /** Compte relié avant le courrier (sans Mail.Read) ou dont l'accès est refusé : à reconnecter. */
  | { status: "reconnect" }
  | {
      status: "ok";
      /** Non lus de la réception et du courrier indésirable, brouillons et envoyés (nombre de messages du dossier). */
      unread: number;
      junk: number;
      drafts: number;
      sent: number;
      recent: { from: string; subject: string; receivedMs: number }[];
      next: { title: string; startMs: number; endMs: number } | null;
    };

/** Refus d'accès de Graph (jeton sans Mail.Read, compte révoqué) : se reconnecter règle le cas. */
class AccessDenied extends Error {}

async function graph<T>(path: string, token: string): Promise<T> {
  const res = await fetch(GRAPH + path, { headers: { authorization: `Bearer ${token}` }, signal: AbortSignal.timeout(15_000) });
  if (res.status === 401 || res.status === 403) throw new AccessDenied(`Courrier du compte : HTTP ${res.status}`);
  if (!res.ok) throw new Error(`Courrier du compte : HTTP ${res.status}`);
  return (await res.json()) as T;
}

type Folder = { unreadItemCount: number; totalItemCount: number };
type Message = { subject?: string; receivedDateTime: string; from?: { emailAddress?: { name?: string; address?: string } } };

const folder = (name: string, token: string) => graph<Folder>(`/me/mailFolders/${name}?$select=unreadItemCount,totalItemCount`, token);

export async function mailSummary(now = Date.now()): Promise<MailSummary> {
  const access = await microsoftMailAccess();
  if (access.state !== "ok") return { status: access.state };
  const { token } = access;
  try {
    // Graph : un `$orderby` n'est accepté qu'avec ses propriétés en tête du `$filter` (sinon InefficientFilter).
    const recentQuery = new URLSearchParams({
      $filter: "receivedDateTime ge 1900-01-01T00:00:00Z and isRead eq false",
      $orderby: "receivedDateTime desc",
      $top: String(RECENT_COUNT),
      $select: "from,subject,receivedDateTime",
    });
    const [inbox, junk, drafts, sent, recent, events] = await Promise.all([
      folder("inbox", token),
      folder("junkemail", token),
      folder("drafts", token),
      folder("sentitems", token),
      graph<{ value: Message[] }>(`/me/mailFolders/inbox/messages?${recentQuery}`, token),
      microsoftEvents(token, new Date(now), new Date(now + NEXT_DAYS * 86_400_000)),
    ]);
    // Prochain rendez-vous : le premier qui n'est pas fini, hors journée entière et hors « disponible ».
    const next = events.find((e) => !e.allDay && !e.free && e.endMs > now);
    return {
      status: "ok",
      unread: inbox.unreadItemCount,
      junk: junk.unreadItemCount,
      drafts: drafts.totalItemCount,
      sent: sent.totalItemCount,
      recent: recent.value.map((m) => ({
        from: m.from?.emailAddress?.name || m.from?.emailAddress?.address || "",
        subject: m.subject || "",
        receivedMs: Date.parse(m.receivedDateTime),
      })),
      next: next ? { title: next.title, startMs: next.startMs, endMs: next.endMs } : null,
    };
  } catch (e) {
    if (e instanceof AccessDenied) return { status: "reconnect" };
    throw e;
  }
}
