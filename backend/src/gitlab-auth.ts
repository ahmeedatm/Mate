import { createHash, randomBytes } from "node:crypto";
import path from "node:path";
import { accountsFile, config } from "./config.ts";
import { readJson, updateJson } from "./store.ts";

/**
 * Connexion à GitLab par OAuth « appli de bureau » (code d'autorisation + PKCE), sur le modèle de src/accounts.ts :
 * page d'autorisation ouverte dans le navigateur de l'ordinateur, retour sur http://127.0.0.1:<port>/oauth/gitlab/callback.
 * Appli non confidentielle : aucun secret, seul l'identifiant public de l'appli (config/accounts.json, clé `gitlab`, ou
 * GITLAB_CLIENT_ID dans .env). Jetons d'accès (2 h) et de renouvellement (tournant : chaque renouvellement invalide
 * l'ancien) : le premier en mémoire, le second dans .data/gitlab.json (0600) sur l'ordinateur. Rien n'en sort : ni
 * réponse, ni message d'erreur, ni journal, ni tablette.
 */

const DEFAULT_URL = "https://gitlab.com";
const DEFAULT_SCOPE = "read_api";
const TIMEOUT_MS = 15_000;

/** Lue à chaque appel (les tests posent l'environnement). */
export const gitlabBaseUrl = () => (process.env.GITLAB_URL?.trim() || DEFAULT_URL).replace(/\/+$/, "");

const gitlabConfig = async () =>
  (await readJson<{ gitlab?: { clientId?: string; scope?: string } }>(accountsFile(), () => ({}), { quarantine: false })).gitlab ?? {};

/** Identifiant public de l'appli : .env d'abord (essai), sinon config/accounts.json, relu à chaque appel. */
export async function gitlabClientId(): Promise<string | undefined> {
  const fromEnv = process.env.GITLAB_CLIENT_ID?.trim();
  if (fromEnv) return fromEnv;
  return (await gitlabConfig()).clientId?.trim() || undefined;
}

/** Portée demandée à la connexion : `gitlab.scope` de config/accounts.json, sinon read_api (lecture : liste et ouverture des MR). */
export async function gitlabScope(): Promise<string> {
  return (await gitlabConfig()).scope?.trim() || DEFAULT_SCOPE;
}

// --- Compte connecté (.data/gitlab.json)

type Stored = { refreshToken?: string; redirectUri?: string; username?: string; expired?: boolean; scope?: string };
const storeFile = path.join(config.dataDir, "gitlab.json");
const load = () => readJson<Stored>(storeFile, () => ({}));
/** Relit le fichier, applique `change`, réécrit : un renouvellement de jeton ne perd pas un autre changement. */
const edit = <R = void>(change: (stored: Stored) => R) => updateJson<Stored, R>(storeFile, () => ({}), change, { mode: 0o600 });

/** `expired` : GitLab a refusé le renouvellement (accès retiré, appli révoquée) : se reconnecter. Le jeton ne sort pas d'ici. */
export async function gitlabAccount(): Promise<{ username: string; expired: boolean } | undefined> {
  const stored = await load();
  return stored.refreshToken ? { username: stored.username ?? "", expired: !!stored.expired } : undefined;
}

/** Le compte connecté a reçu la portée `api` (celle qui permet d'écrire, donc de fusionner). Portée inconnue : non. */
export async function gitlabAccountCanWrite(): Promise<boolean> {
  return ((await load()).scope ?? "").split(/\s+/).includes("api");
}

export async function disconnectGitlab() {
  access = undefined;
  await edit((stored) => {
    for (const key of Object.keys(stored)) delete stored[key as keyof Stored];
  });
}

// --- Connexion

const pending = new Map<string, { verifier: string; redirectUri: string; scope: string; expires: number }>();
const base64url = (b: Buffer) => b.toString("base64url");

/** Adresse de la page d'autorisation de GitLab ; le retour arrive sur `redirectUri` avec `state`. */
export async function gitlabLoginUrl(redirectUri: string): Promise<string> {
  const clientId = await gitlabClientId();
  if (!clientId) throw new Error("Connexion GitLab non configurée");
  const now = Date.now();
  for (const [k, v] of pending) if (v.expires < now) pending.delete(k);
  const state = base64url(randomBytes(24));
  const verifier = base64url(randomBytes(48));
  const scope = await gitlabScope();
  pending.set(state, { verifier, redirectUri, scope, expires: now + 10 * 60_000 });
  const params = new URLSearchParams({
    client_id: clientId,
    response_type: "code",
    redirect_uri: redirectUri,
    scope,
    state,
    code_challenge: base64url(createHash("sha256").update(verifier).digest()),
    code_challenge_method: "S256",
  });
  return `${gitlabBaseUrl()}/oauth/authorize?${params}`;
}

/** Retour de GitLab : échange le code contre les jetons et garde le compte. */
export async function finishGitlabLogin(state: string, code: string): Promise<{ username: string }> {
  const login = pending.get(state);
  pending.delete(state);
  if (!login || login.expires < Date.now()) throw new Error("Connexion expirée : recommence depuis la page du compagnon");
  const clientId = await gitlabClientId();
  if (!clientId) throw new Error("Connexion GitLab non configurée");
  const tokens = await tokenRequest(clientId, {
    grant_type: "authorization_code",
    code,
    redirect_uri: login.redirectUri,
    code_verifier: login.verifier,
  });
  if (!tokens.refresh_token) throw new Error("GitLab n'a pas donné d'accès durable : recommence");
  const username = await userName(tokens.access_token);
  await edit((stored) => {
    for (const key of Object.keys(stored)) delete stored[key as keyof Stored];
    Object.assign(stored, {
      refreshToken: tokens.refresh_token,
      redirectUri: login.redirectUri,
      username,
      scope: tokens.scope ?? login.scope,
    });
  });
  access = { token: tokens.access_token, expires: expiry(tokens) };
  return { username };
}

type TokenResponse = { access_token: string; refresh_token?: string; expires_in?: number; scope?: string };
const expiry = (t: TokenResponse) => Date.now() + ((t.expires_in ?? 7200) - 60) * 1000;

async function tokenRequest(clientId: string, params: Record<string, string>): Promise<TokenResponse> {
  const body = new URLSearchParams({ client_id: clientId, ...params });
  const res = await fetch(`${gitlabBaseUrl()}/oauth/token`, { method: "POST", body, signal: AbortSignal.timeout(TIMEOUT_MS) });
  const json = (await res.json().catch(() => ({}))) as TokenResponse & { error?: string; error_description?: string };
  // Message de GitLab seulement : le corps de la réponse (jetons compris) n'est jamais recopié dans l'erreur.
  if (!res.ok || !json.access_token) {
    throw Object.assign(new Error(json.error_description || json.error || `HTTP ${res.status}`), { oauthError: json.error });
  }
  return json;
}

/** Nom de l'utilisateur pour la page locale ; sans importance pour la suite : un échec le laisse vide. */
async function userName(token: string): Promise<string> {
  try {
    const res = await fetch(`${gitlabBaseUrl()}/api/v4/user`, {
      headers: { authorization: `Bearer ${token}` },
      signal: AbortSignal.timeout(TIMEOUT_MS),
    });
    const json = (await res.json()) as { username?: unknown };
    return res.ok && typeof json.username === "string" ? json.username : "";
  } catch {
    return "";
  }
}

// --- Jeton d'accès

let access: { token: string; expires: number } | undefined;
let renewing: Promise<string | undefined> | undefined;

/**
 * Jeton d'accès du compte connecté, renouvelé seul quand il expire. `undefined` : pas de compte utilisable (aucun, ou
 * renouvellement refusé : le compte passe à « à reconnecter »). Erreur réseau ou GitLab en panne : lève, le compte reste.
 */
export function gitlabAccessToken(): Promise<string | undefined> {
  if (access && access.expires > Date.now()) return Promise.resolve(access.token);
  // Un seul renouvellement à la fois : le jeton de renouvellement est à usage unique.
  return (renewing ??= renew().finally(() => (renewing = undefined)));
}

/** GitLab a refusé le jeton d'accès (révoqué) : le prochain appel en demande un nouveau. */
export const forgetGitlabAccessToken = () => {
  access = undefined;
};

async function renew(): Promise<string | undefined> {
  const stored = await load();
  const clientId = await gitlabClientId();
  if (!stored.refreshToken || stored.expired || !clientId) return undefined;
  let tokens: TokenResponse;
  try {
    tokens = await tokenRequest(clientId, {
      grant_type: "refresh_token",
      refresh_token: stored.refreshToken,
      redirect_uri: stored.redirectUri ?? "",
    });
  } catch (e) {
    if ((e as { oauthError?: string }).oauthError === "invalid_grant") {
      access = undefined;
      await edit((s) => {
        if (s.refreshToken === stored.refreshToken) s.expired = true;
      });
      return undefined;
    }
    throw e;
  }
  // Déconnecté ou reconnecté pendant le renouvellement : ce résultat est périmé, on ne le garde pas.
  const kept = await edit((s) => {
    if (s.refreshToken !== stored.refreshToken) return false;
    if (tokens.refresh_token) s.refreshToken = tokens.refresh_token;
    if (tokens.scope) s.scope = tokens.scope;
    return true;
  });
  if (!kept) return undefined;
  access = { token: tokens.access_token, expires: expiry(tokens) };
  return tokens.access_token;
}
