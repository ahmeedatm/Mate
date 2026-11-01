import { createHash, randomBytes } from "node:crypto";
import path from "node:path";
import { accountsFile, config } from "./config.ts";
import type { CalendarEvent } from "./calendar.ts";
import { meetingLink } from "./meeting-links.ts";
import { readJson, updateJson } from "./store.ts";

/**
 * Agenda par connexion au compte (Microsoft 365 / Outlook.com, Google), sans lien ICS à chercher.
 *
 * Connexion OAuth « appli de bureau » : code d'autorisation + PKCE, retour sur http://localhost:<port>/oauth/…/callback
 * (page ouverte dans le navigateur de l'ordinateur). Aucun secret côté client : l'identifiant de l'appli (enregistrée
 * une fois chez Microsoft et Google) est public, dans config/accounts.json. Le jeton de renouvellement
 * reste dans .data/accounts.json, sur l'ordinateur ; la tablette ne voit que les événements et le nombre de mails non lus.
 */

export type Provider = "microsoft" | "google";

const PROVIDERS: Record<Provider, { label: string; authUrl: string; tokenUrl: string; scope: string; extra: Record<string, string> }> = {
  microsoft: {
    label: "Microsoft",
    authUrl: "https://login.microsoftonline.com/common/oauth2/v2.0/authorize",
    tokenUrl: "https://login.microsoftonline.com/common/oauth2/v2.0/token",
    scope: "openid email offline_access https://graph.microsoft.com/Calendars.Read https://graph.microsoft.com/Mail.ReadBasic",
    extra: { prompt: "select_account" },
  },
  google: {
    label: "Google",
    authUrl: "https://accounts.google.com/o/oauth2/v2/auth",
    tokenUrl: "https://oauth2.googleapis.com/token",
    scope: "openid email https://www.googleapis.com/auth/calendar.readonly https://www.googleapis.com/auth/gmail.metadata",
    // offline + consent : Google ne donne le jeton de renouvellement qu'à ces conditions.
    extra: { access_type: "offline", prompt: "consent" },
  },
};

export const isProvider = (p: string): p is Provider => p === "microsoft" || p === "google";

type ClientConfig = { clientId: string; clientSecret?: string };

/** Identifiants des applis enregistrées (config/accounts.json, livré avec le logiciel ; .env pour tester). */
async function client(provider: Provider): Promise<ClientConfig | undefined> {
  const file = await readJson<Partial<Record<Provider, ClientConfig>>>(accountsFile(), () => ({}), {
    quarantine: false,
  });
  const fromFile = file[provider];
  const env =
    provider === "microsoft"
      ? { clientId: process.env.MICROSOFT_CLIENT_ID }
      : { clientId: process.env.GOOGLE_CLIENT_ID, clientSecret: process.env.GOOGLE_CLIENT_SECRET };
  const clientId = env.clientId?.trim() || fromFile?.clientId?.trim();
  if (!clientId) return undefined;
  return { clientId, clientSecret: env.clientSecret?.trim() || fromFile?.clientSecret?.trim() || undefined };
}

/** Fournisseurs proposés sur la page de l'ordinateur (ceux dont l'appli est enregistrée). */
export async function availableProviders(): Promise<{ provider: Provider; label: string }[]> {
  const all = Object.keys(PROVIDERS) as Provider[];
  const ok = await Promise.all(all.map(async (p) => ((await client(p)) ? p : undefined)));
  return ok.filter((p): p is Provider => !!p).map((p) => ({ provider: p, label: PROVIDERS[p].label }));
}

// --- Comptes connectés (.data/accounts.json)

type Stored = { email: string; refreshToken: string; expired?: boolean };
const storeFile = path.join(config.dataDir, "accounts.json");

async function load(): Promise<Partial<Record<Provider, Stored>>> {
  return readJson<Partial<Record<Provider, Stored>>>(storeFile, () => ({}));
}

/** Relit le fichier, applique `change`, réécrit : un renouvellement de jeton ne perd pas un autre changement. */
function edit<R = void>(change: (accounts: Partial<Record<Provider, Stored>>) => R) {
  return updateJson<Partial<Record<Provider, Stored>>, R>(storeFile, () => ({}), change, { mode: 0o600 });
}

/** `expired` : le compte a refusé le renouvellement (mot de passe changé, accès retiré) : se reconnecter. */
export async function connectedAccounts(): Promise<{ provider: Provider; label: string; email: string; expired: boolean }[]> {
  const accounts = await load();
  return (Object.keys(accounts) as Provider[]).map((p) => ({
    provider: p,
    label: PROVIDERS[p].label,
    email: accounts[p]!.email,
    expired: !!accounts[p]!.expired,
  }));
}

export async function disconnect(provider: Provider) {
  accessTokens.delete(provider);
  await edit((accounts) => void delete accounts[provider]);
}

// --- Connexion

const pending = new Map<string, { provider: Provider; verifier: string; redirectUri: string; expires: number }>();
const base64url = (b: Buffer) => b.toString("base64url");

/** Adresse de la page de connexion du fournisseur ; le retour arrive sur `redirectUri` avec `state`. */
export async function loginUrl(provider: Provider, redirectUri: string): Promise<string> {
  const app = await client(provider);
  if (!app) throw new Error(`Connexion ${PROVIDERS[provider].label} non configurée`);
  const now = Date.now();
  for (const [k, v] of pending) if (v.expires < now) pending.delete(k);
  const state = base64url(randomBytes(24));
  const verifier = base64url(randomBytes(48));
  pending.set(state, { provider, verifier, redirectUri, expires: now + 10 * 60_000 });
  const p = PROVIDERS[provider];
  const params = new URLSearchParams({
    client_id: app.clientId,
    response_type: "code",
    redirect_uri: redirectUri,
    scope: p.scope,
    state,
    code_challenge: base64url(createHash("sha256").update(verifier).digest()),
    code_challenge_method: "S256",
    ...p.extra,
  });
  return `${p.authUrl}?${params}`;
}

/** Retour du fournisseur : échange le code contre les jetons et garde le compte. */
export async function finishLogin(state: string, code: string): Promise<{ provider: Provider; email: string }> {
  const login = pending.get(state);
  pending.delete(state);
  if (!login || login.expires < Date.now()) throw new Error("Connexion expirée : recommence depuis la page du compagnon");
  const app = (await client(login.provider))!;
  const tokens = await tokenRequest(login.provider, app, {
    grant_type: "authorization_code",
    code,
    redirect_uri: login.redirectUri,
    code_verifier: login.verifier,
  });
  if (!tokens.refresh_token) throw new Error("Le compte n'a pas donné d'accès durable : recommence");
  const email = emailFromIdToken(tokens.id_token) ?? PROVIDERS[login.provider].label;
  await edit((accounts) => void (accounts[login.provider] = { email, refreshToken: tokens.refresh_token! }));
  accessTokens.set(login.provider, { token: tokens.access_token, expires: Date.now() + (tokens.expires_in - 60) * 1000 });
  return { provider: login.provider, email };
}

type TokenResponse = { access_token: string; refresh_token?: string; id_token?: string; expires_in: number };

async function tokenRequest(provider: Provider, app: ClientConfig, params: Record<string, string>): Promise<TokenResponse> {
  const body = new URLSearchParams({ client_id: app.clientId, ...params });
  if (app.clientSecret) body.set("client_secret", app.clientSecret);
  // Au renouvellement, pas de scope : un compte connecté avant l'ajout des mails garde son agenda (sinon Microsoft refuse en bloc).
  if (provider === "microsoft" && params.grant_type === "authorization_code") body.set("scope", PROVIDERS[provider].scope);
  const res = await fetch(PROVIDERS[provider].tokenUrl, { method: "POST", body, signal: AbortSignal.timeout(15_000) });
  const json = (await res.json().catch(() => ({}))) as TokenResponse & { error?: string; error_description?: string };
  if (!res.ok) throw Object.assign(new Error(json.error_description || json.error || `HTTP ${res.status}`), { oauthError: json.error });
  return json;
}

/** Adresse du compte, lue dans le jeton d'identité (reçu directement du fournisseur : pas de vérification de signature). */
function emailFromIdToken(idToken?: string): string | undefined {
  const payload = idToken?.split(".")[1];
  if (!payload) return undefined;
  const claims = JSON.parse(Buffer.from(payload, "base64url").toString("utf8")) as Record<string, string>;
  return claims.email || claims.preferred_username || undefined;
}

const accessTokens = new Map<Provider, { token: string; expires: number }>();

async function accessToken(provider: Provider, stored: Stored): Promise<string> {
  const cached = accessTokens.get(provider);
  if (cached && cached.expires > Date.now()) return cached.token;
  const app = await client(provider);
  if (!app) throw new Error(`Connexion ${PROVIDERS[provider].label} non configurée`);
  try {
    const tokens = await tokenRequest(provider, app, { grant_type: "refresh_token", refresh_token: stored.refreshToken });
    accessTokens.set(provider, { token: tokens.access_token, expires: Date.now() + (tokens.expires_in - 60) * 1000 });
    // Microsoft renouvelle aussi le jeton de renouvellement : on garde le dernier.
    if (tokens.refresh_token && tokens.refresh_token !== stored.refreshToken) {
      await edit((accounts) => {
        if (accounts[provider]) accounts[provider]!.refreshToken = tokens.refresh_token!;
      });
    }
    return tokens.access_token;
  } catch (e) {
    if ((e as { oauthError?: string }).oauthError === "invalid_grant") {
      await edit((accounts) => {
        if (accounts[provider]) accounts[provider]!.expired = true;
      });
    }
    throw e;
  }
}

// --- Événements

/**
 * Événements de tous les comptes connectés entre `from` et `to` (récurrences dépliées par le fournisseur) ; un compte en panne
 * n'empêche pas les autres, son erreur est rendue à part.
 */
export async function accountEvents(from: Date, to: Date): Promise<{ events: CalendarEvent[]; errors: Error[] }> {
  const accounts = await load();
  const events: CalendarEvent[] = [];
  const errors: Error[] = [];
  for (const provider of Object.keys(accounts) as Provider[]) {
    const stored = accounts[provider]!;
    if (stored.expired) continue;
    try {
      const token = await accessToken(provider, stored);
      events.push(...(provider === "microsoft" ? await microsoftEvents(token, from, to) : await googleEvents(token, from, to)));
    } catch (e) {
      errors.push(e as Error);
    }
  }
  return { events, errors };
}

async function getJson(url: string, token: string, headers: Record<string, string> = {}) {
  const res = await fetch(url, { headers: { authorization: `Bearer ${token}`, ...headers }, signal: AbortSignal.timeout(15_000) });
  if (!res.ok) throw new Error(`Agenda du compte : HTTP ${res.status}`);
  return res.json() as Promise<Record<string, unknown>>;
}

/** Jour « toute la journée » : à minuit heure locale (le fournisseur donne une date sans fuseau). */
const localDay = (date: string) => {
  const [y, m, d] = date.slice(0, 10).split("-").map(Number);
  return new Date(y, m - 1, d).getTime();
};

type GraphEvent = {
  iCalUId: string;
  subject?: string;
  isAllDay: boolean;
  isCancelled: boolean;
  showAs?: string;
  start: { dateTime: string };
  end: { dateTime: string };
  location?: { displayName?: string };
  onlineMeeting?: { joinUrl?: string } | null;
  bodyPreview?: string;
};

export async function microsoftEvents(token: string, from: Date, to: Date): Promise<CalendarEvent[]> {
  const select = "iCalUId,subject,isAllDay,isCancelled,showAs,start,end,location,onlineMeeting,bodyPreview";
  let url: string | undefined =
    "https://graph.microsoft.com/v1.0/me/calendarView?" +
    new URLSearchParams({
      startDateTime: from.toISOString(),
      endDateTime: to.toISOString(),
      $select: select,
      $top: "100",
      $orderby: "start/dateTime",
    });
  const events: CalendarEvent[] = [];
  // Heures en UTC (Prefer) : converties ensuite dans le fuseau de l'ordinateur.
  for (let page = 0; url && page < 5; page++) {
    const json = await getJson(url, token, { prefer: 'outlook.timezone="UTC"' });
    for (const e of json.value as GraphEvent[]) {
      if (e.isCancelled) continue;
      const startMs = e.isAllDay ? localDay(e.start.dateTime) : Date.parse(e.start.dateTime + "Z");
      events.push({
        id: `${e.iCalUId}@${startMs}`,
        title: e.subject || "(sans titre)",
        location: e.location?.displayName ?? "",
        startMs,
        endMs: e.isAllDay ? localDay(e.end.dateTime) : Date.parse(e.end.dateTime + "Z"),
        allDay: e.isAllDay,
        free: e.showAs === "free",
        joinUrl: e.onlineMeeting?.joinUrl ?? meetingLink(e.bodyPreview, e.location?.displayName),
      });
    }
    url = json["@odata.nextLink"] as string | undefined;
  }
  return events;
}

type GoogleEvent = {
  id: string;
  iCalUID?: string;
  status?: string;
  summary?: string;
  location?: string;
  description?: string;
  transparency?: string;
  start: { dateTime?: string; date?: string };
  end: { dateTime?: string; date?: string };
  hangoutLink?: string;
  conferenceData?: { entryPoints?: { entryPointType: string; uri: string }[] };
};

async function googleEvents(token: string, from: Date, to: Date): Promise<CalendarEvent[]> {
  const url =
    "https://www.googleapis.com/calendar/v3/calendars/primary/events?" +
    new URLSearchParams({
      timeMin: from.toISOString(),
      timeMax: to.toISOString(),
      singleEvents: "true",
      orderBy: "startTime",
      maxResults: "250",
    });
  const json = await getJson(url, token);
  return (json.items as GoogleEvent[])
    .filter((e) => e.status !== "cancelled")
    .map((e) => {
      const allDay = !e.start.dateTime;
      const startMs = allDay ? localDay(e.start.date!) : Date.parse(e.start.dateTime!);
      return {
        id: `${e.iCalUID ?? e.id}@${startMs}`,
        title: e.summary || "(sans titre)",
        location: e.location ?? "",
        startMs,
        endMs: allDay ? localDay(e.end.date!) : Date.parse(e.end.dateTime!),
        allDay,
        free: e.transparency === "transparent",
        joinUrl:
          e.hangoutLink ??
          e.conferenceData?.entryPoints?.find((p) => p.entryPointType === "video")?.uri ??
          meetingLink(e.description, e.location),
      };
    });
}

// --- Mails non lus

export type MailUnread = { provider: Provider; label: string; email: string; unread: number | null; error?: string };

/** Nombre de non lus dans la réponse du fournisseur (dossier Boîte de réception / libellé INBOX). */
export function unreadFrom(provider: Provider, json: Record<string, unknown>): number {
  const n = provider === "microsoft" ? json.unreadItemCount : json.messagesUnread;
  if (typeof n !== "number" || !Number.isInteger(n) || n < 0) throw new Error("Réponse du compte illisible");
  return n;
}

const UNREAD_URL: Record<Provider, string> = {
  microsoft: "https://graph.microsoft.com/v1.0/me/mailFolders/inbox?$select=unreadItemCount",
  google: "https://www.googleapis.com/gmail/v1/users/me/labels/INBOX",
};

const RECONNECT = "Reconnecte le compte pour autoriser la lecture des mails";

async function unreadOf(provider: Provider, stored: Stored): Promise<number> {
  const token = await accessToken(provider, stored);
  const res = await fetch(UNREAD_URL[provider], { headers: { authorization: `Bearer ${token}` }, signal: AbortSignal.timeout(15_000) });
  // Compte connecté avant l'ajout des mails : le fournisseur refuse (401/403) tant qu'il n'a pas re-consenti.
  if (res.status === 401 || res.status === 403) throw new Error(RECONNECT);
  if (!res.ok) throw new Error("Les mails du compte ne répondent pas");
  return unreadFrom(provider, (await res.json()) as Record<string, unknown>);
}

/** Non lus de chaque compte connecté ; un compte en panne reste dans la liste avec une phrase, sans cacher les autres. */
export async function accountUnread(): Promise<MailUnread[]> {
  const accounts = await load();
  const rows: MailUnread[] = [];
  for (const provider of Object.keys(accounts) as Provider[]) {
    const stored = accounts[provider]!;
    const row = { provider, label: PROVIDERS[provider].label, email: stored.email };
    if (stored.expired) {
      rows.push({ ...row, unread: null, error: RECONNECT });
      continue;
    }
    try {
      rows.push({ ...row, unread: await unreadOf(provider, stored) });
    } catch (e) {
      const known = e instanceof Error && (e.message === RECONNECT || e.message.startsWith("Les mails") || e.message.startsWith("Réponse"));
      rows.push({ ...row, unread: null, error: known ? e.message : "Les mails du compte ne répondent pas" });
    }
  }
  return rows;
}

/** Page de la boîte de réception, ouverte sur l'ordinateur : adresse fixe par fournisseur, jamais une donnée de la tablette. */
export async function inboxUrl(provider: Provider): Promise<string | undefined> {
  const stored = (await load())[provider];
  if (!stored) return undefined;
  return provider === "google"
    ? `https://mail.google.com/mail/?authuser=${encodeURIComponent(stored.email)}`
    : "https://outlook.office.com/mail/";
}
