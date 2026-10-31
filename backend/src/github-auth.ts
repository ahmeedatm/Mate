import path from "node:path";
import { accountsFile, config } from "./config.ts";
import { logFailure } from "./log.ts";
import { readJson, updateJson } from "./store.ts";

/**
 * Connexion à GitHub par OAuth « device flow » (RFC 8628) : GitHub ne fait pas de PKCE sans secret pour une appli OAuth, le
 * device flow est sa voie pour une appli de bureau publique. La page du compagnon demande un code (`/login/device/code`),
 * l'affiche et ouvre https://github.com/login/device, où l'utilisateur le saisit et autorise ; le compagnon interroge
 * `/login/oauth/access_token` en attendant. Aucun secret : seul l'identifiant public de l'appli OAuth (config/accounts.json,
 * clé `github`, ou GITHUB_CLIENT_ID dans .env). Le jeton d'une appli OAuth n'expire pas : il est gardé dans .data/github.json
 * (0600) sur l'ordinateur, révocable depuis GitHub ; GitHub le refuse (401) : le compte passe à « à reconnecter ». Rien n'en
 * sort : ni réponse, ni message d'erreur, ni journal, ni tablette.
 */

const DEFAULT_SCOPE = "repo";
const TIMEOUT_MS = 15_000;

/** Site GitHub (pages de connexion, adresses ouvertes sur l'ordinateur). GITHUB_URL : faux GitHub des tests. */
export const githubWebUrl = () => (process.env.GITHUB_URL?.trim() || "https://github.com").replace(/\/+$/, "");
/** API REST et GraphQL : api.github.com pour github.com, sinon la même adresse que le site (faux GitHub). */
export const githubApiUrl = () => {
  const web = githubWebUrl();
  return web === "https://github.com" ? "https://api.github.com" : web;
};

const githubConfig = async () =>
  (await readJson<{ github?: { clientId?: string; scope?: string } }>(accountsFile(), () => ({}), { quarantine: false })).github ?? {};

/** Identifiant public de l'appli OAuth : .env d'abord (essai), sinon config/accounts.json, relu à chaque appel. */
export async function githubClientId(): Promise<string | undefined> {
  const fromEnv = process.env.GITHUB_CLIENT_ID?.trim();
  if (fromEnv) return fromEnv;
  return (await githubConfig()).clientId?.trim() || undefined;
}

/** Portée demandée : `github.scope` de config/accounts.json, sinon `repo` (GitHub n'a pas de lecture seule des dépôts privés). */
export async function githubScope(): Promise<string> {
  return (await githubConfig()).scope?.trim() || DEFAULT_SCOPE;
}

// --- Compte connecté (.data/github.json)

type Stored = { accessToken?: string; username?: string; scope?: string; expired?: boolean };
const storeFile = path.join(config.dataDir, "github.json");
const load = () => readJson<Stored>(storeFile, () => ({}));
const edit = <R = void>(change: (stored: Stored) => R) => updateJson<Stored, R>(storeFile, () => ({}), change, { mode: 0o600 });
const clear = (stored: Stored) => {
  for (const key of Object.keys(stored)) delete stored[key as keyof Stored];
};

/** `expired` : GitHub a refusé le jeton (révoqué, appli retirée) : se reconnecter. Le jeton ne sort pas d'ici. */
export async function githubAccount(): Promise<{ username: string; expired: boolean } | undefined> {
  const stored = await load();
  return stored.accessToken ? { username: stored.username ?? "", expired: !!stored.expired } : undefined;
}

/** Jeton du compte connecté ; `undefined` sans compte ou compte à reconnecter. */
export async function githubAccessToken(): Promise<string | undefined> {
  const stored = await load();
  return stored.accessToken && !stored.expired ? stored.accessToken : undefined;
}

/** Portées qui permettent d'écrire dans un dépôt, donc de fusionner (GitHub les sépare par des virgules). */
export const scopeCanWrite = (scope: string) => scope.split(/[\s,]+/).some((s) => s === "repo" || s === "public_repo");

export async function githubAccountCanWrite(): Promise<boolean> {
  return scopeCanWrite((await load()).scope ?? "");
}

/** GitHub a refusé `token` (401) : le compte passe à « à reconnecter », sauf s'il a changé entre-temps. */
export async function expireGithubToken(token: string) {
  await edit((s) => {
    if (s.accessToken === token) s.expired = true;
  });
}

export async function disconnectGithub() {
  login = undefined;
  await edit(clear);
}

// --- Connexion (device flow)

/** `waiting` : code affiché, en attente de l'autorisation ; les autres : résultat du dernier essai, montré par la page. */
export type GithubLoginState = "waiting" | "ok" | "refused" | "expired" | "error";
type Login = { id: number; state: GithubLoginState; userCode: string; verificationUri: string; expires: number };
let login: Login | undefined;
let loginCounter = 0;

/** Pour /local/status : le code tant qu'on attend, sinon le dernier résultat (jamais le code d'appareil, secret). */
export function githubLogin(): { state: GithubLoginState; userCode?: string; verificationUri?: string } | null {
  if (!login) return null;
  if (login.state === "waiting" && login.expires < Date.now()) login.state = "expired";
  return login.state === "waiting"
    ? { state: login.state, userCode: login.userCode, verificationUri: login.verificationUri }
    : { state: login.state };
}

type DeviceCode = { device_code: string; user_code: string; verification_uri: string; expires_in?: number; interval?: number };
type TokenAnswer = { access_token?: string; scope?: string; error?: string; interval?: number };

async function post<T>(url: string, params: Record<string, string>): Promise<{ status: number; json: T }> {
  const res = await fetch(url, {
    method: "POST",
    headers: { accept: "application/json", "content-type": "application/x-www-form-urlencoded", "user-agent": "UnoMate" },
    body: new URLSearchParams(params),
    signal: AbortSignal.timeout(TIMEOUT_MS),
  });
  return { status: res.status, json: (await res.json().catch(() => ({}))) as T };
}

/**
 * Demande un code à GitHub et attend l'autorisation en arrière-plan. Un nouvel essai remplace le précédent. Le code
 * d'appareil (secret) reste dans cette fonction ; seul le code à saisir et l'adresse sortent.
 */
export async function startGithubLogin(): Promise<{ userCode: string; verificationUri: string }> {
  const clientId = await githubClientId();
  if (!clientId) throw new Error("Connexion GitHub non configurée");
  const { status, json } = await post<DeviceCode & { error?: string }>(`${githubWebUrl()}/login/device/code`, {
    client_id: clientId,
    scope: await githubScope(),
  });
  if (status !== 200 || !json.device_code || !json.user_code) throw new Error(`GitHub refuse la connexion (${json.error ?? status})`);
  const id = ++loginCounter;
  login = {
    id,
    state: "waiting",
    userCode: json.user_code,
    verificationUri: json.verification_uri,
    expires: Date.now() + (json.expires_in ?? 900) * 1000,
  };
  void waitForToken(id, clientId, json.device_code, json.interval ?? 5).catch((e) => {
    logFailure("Connexion GitHub", e);
    if (login?.id === id) login.state = "error";
  });
  return { userCode: json.user_code, verificationUri: json.verification_uri };
}

const sleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms).unref());

async function waitForToken(id: number, clientId: string, deviceCode: string, intervalSec: number) {
  // GitHub annonce au moins 5 s ; le faux GitHub des tests, moins.
  let interval = Math.max(0.05, intervalSec);
  const current = () => login?.id === id && login.state === "waiting";
  while (current()) {
    await sleep(interval * 1000);
    if (!current()) return;
    if (login!.expires < Date.now()) {
      login!.state = "expired";
      return;
    }
    const { json } = await post<TokenAnswer>(`${githubWebUrl()}/login/oauth/access_token`, {
      client_id: clientId,
      device_code: deviceCode,
      grant_type: "urn:ietf:params:oauth:grant-type:device_code",
    });
    if (!current()) return;
    if (json.access_token) {
      const token = json.access_token;
      const username = await userName(token);
      await edit((s) => {
        clear(s);
        Object.assign(s, { accessToken: token, username, scope: json.scope ?? "" });
      });
      if (login?.id === id) login.state = "ok";
      return;
    }
    if (json.error === "authorization_pending") continue;
    if (json.error === "slow_down") {
      interval = Math.max(interval + 5, json.interval ?? 0);
      continue;
    }
    login!.state = json.error === "access_denied" ? "refused" : json.error === "expired_token" ? "expired" : "error";
    return;
  }
}

/** Nom de l'utilisateur pour la page locale ; sans importance pour la suite : un échec le laisse vide. */
async function userName(token: string): Promise<string> {
  try {
    const res = await fetch(`${githubApiUrl()}/user`, {
      headers: { authorization: `Bearer ${token}`, accept: "application/vnd.github+json", "user-agent": "UnoMate" },
      signal: AbortSignal.timeout(TIMEOUT_MS),
    });
    const json = (await res.json()) as { login?: unknown };
    return res.ok && typeof json.login === "string" ? json.login : "";
  } catch {
    return "";
  }
}
