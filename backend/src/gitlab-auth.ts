import path from "node:path";
import { config } from "./config.ts";
import { readJson, updateJson } from "./store.ts";

/**
 * Connexion à GitLab par jeton personnel collé dans la page du compagnon (carte GitLab). Le jeton est vérifié auprès de
 * GitLab (`GET /api/v4/user`), puis gardé dans .data/gitlab.json (0600) sur l'ordinateur : il lit et fusionne (portée
 * `api` ; si GitLab dit qu'il n'a que `read_api`, la fusion est refusée). Rien n'en sort : ni réponse, ni message
 * d'erreur, ni journal, ni tablette. Un ancien compte OAuth enregistré (sans `token`) est effacé.
 */

const DEFAULT_URL = "https://gitlab.com";
const TIMEOUT_MS = 15_000;

/** Lue à chaque appel (les tests posent l'environnement). Valeur brute : voir `gitlabUrlProblem` et `gitlabApiUrl`. */
export const gitlabBaseUrl = () => (process.env.GITLAB_URL?.trim() || DEFAULT_URL).replace(/\/+$/, "");

const BAD_URL =
  "GITLAB_URL doit commencer par https:// (http:// seulement pour 127.0.0.1, localhost ou [::1]), sans identifiant dans l'adresse";
/** Pourquoi `GITLAB_URL` est refusée (le jeton ne part jamais en clair sur le réseau ni vers un autre hôte), ou `undefined`. */
export function gitlabUrlProblem(): string | undefined {
  let url: URL;
  try {
    url = new URL(gitlabBaseUrl());
  } catch {
    return BAD_URL;
  }
  if (url.username || url.password) return BAD_URL;
  if (url.protocol === "https:") return undefined;
  return url.protocol === "http:" && /^(127\.0\.0\.1|localhost|\[::1\])$/i.test(url.hostname) ? undefined : BAD_URL;
}
/** Adresse de GitLab pour un appel qui porte un jeton ; lève la phrase de `gitlabUrlProblem` si elle est refusée. */
export function gitlabApiUrl(onProblem: (message: string) => Error): string {
  const problem = gitlabUrlProblem();
  if (problem) throw onProblem(problem);
  return gitlabBaseUrl();
}

/** Message en français montré dans la page locale : jamais le jeton ni le corps de la réponse de GitLab. */
export class GitlabTokenError extends Error {
  constructor(
    readonly statusCode: number,
    message: string,
  ) {
    super(message);
  }
}
const REFUSED = new GitlabTokenError(400, "GitLab refuse ce jeton : vérifie qu'il est complet et qu'il n'a pas expiré");
const WRONG_SCOPE = new GitlabTokenError(400, "Ce jeton n'a ni la portée api ni read_api : crée-le avec la case « api » cochée");
const UNREACHABLE = new GitlabTokenError(502, "GitLab ne répond pas");

// --- Compte connecté (.data/gitlab.json)

type Stored = { refreshToken?: string; token?: string; username?: string; canWrite?: boolean; expired?: boolean };
const storeFile = path.join(config.dataDir, "gitlab.json");
/** Un ancien compte OAuth (jeton de renouvellement, pas de jeton collé) est effacé du disque à la première lecture. */
const load = async (): Promise<Stored> => {
  const stored = await readJson<Stored>(storeFile, () => ({}));
  if (!stored.refreshToken || stored.token) return stored;
  await edit((s) => {
    if (s.refreshToken && !s.token) for (const key of Object.keys(s)) delete s[key as keyof Stored];
  });
  return {};
};
/** Relit le fichier, applique `change`, réécrit. */
const edit = <R = void>(change: (stored: Stored) => R) => updateJson<Stored, R>(storeFile, () => ({}), change, { mode: 0o600 });

/** `expired` : GitLab a refusé le jeton (révoqué, expiré) : en coller un autre. Le jeton ne sort pas d'ici. */
export async function gitlabAccount(): Promise<{ username: string; expired: boolean; canWrite: boolean } | undefined> {
  const stored = await load();
  return stored.token ? { username: stored.username ?? "", expired: !!stored.expired, canWrite: !!stored.canWrite } : undefined;
}

/** Jeton du compte et droit d'écrire (donc de fusionner). `undefined` : pas de compte utilisable (aucun, ou refusé par GitLab). */
export async function gitlabAccess(): Promise<{ token: string; canWrite: boolean } | undefined> {
  const stored = await load();
  return stored.token && !stored.expired ? { token: stored.token, canWrite: !!stored.canWrite } : undefined;
}

/**
 * GitLab a refusé `token` : le compte passe à « à remplacer » (la page le dit, la tablette renvoie vers elle), sauf si le
 * jeton refusé n'est pas celui du compte (GITLAB_TOKEN du .env, ou jeton remplacé entre-temps).
 */
export async function gitlabTokenRefused(token: string | undefined) {
  if (!token || (await load()).token !== token) return;
  await edit((stored) => {
    if (stored.token === token) stored.expired = true;
  });
}

export async function disconnectGitlab() {
  await edit((stored) => {
    for (const key of Object.keys(stored)) delete stored[key as keyof Stored];
  });
}

// --- Connexion

/** Appel de vérification : tout échec réseau, redirection ou délai devient « GitLab ne répond pas ». */
async function ask(route: string, token: string): Promise<Response> {
  const base = gitlabApiUrl((message) => new GitlabTokenError(400, message));
  try {
    return await fetch(base + route, {
      headers: { "PRIVATE-TOKEN": token, accept: "application/json" },
      redirect: "error",
      signal: AbortSignal.timeout(TIMEOUT_MS),
    });
  } catch {
    throw UNREACHABLE;
  }
}

/** Vérifie le jeton auprès de GitLab puis le garde. Refusé ou injoignable : lève, rien n'est enregistré. */
export async function connectGitlab(pasted: string): Promise<{ username: string }> {
  const token = pasted.trim();
  if (!/^[\w.-]{8,200}$/.test(token)) throw REFUSED;
  const res = await ask("/api/v4/user", token);
  if (res.status === 401) throw REFUSED;
  const user = (await res.json().catch(() => ({}))) as { username?: unknown };
  if (!res.ok || typeof user.username !== "string") throw UNREACHABLE;
  // Portées du jeton (GitLab 15.5+). Réponse absente ou illisible : on suppose `api`, la fusion le dira si c'est faux.
  const self = await ask("/api/v4/personal_access_tokens/self", token).catch(() => undefined);
  const scopes = self?.ok ? ((await self.json().catch(() => ({}))) as { scopes?: unknown }).scopes : undefined;
  const known = Array.isArray(scopes) ? scopes.map(String) : undefined;
  if (known && !known.includes("api") && !known.includes("read_api")) throw WRONG_SCOPE;
  const canWrite = !known || known.includes("api");
  await edit((stored) => {
    for (const key of Object.keys(stored)) delete stored[key as keyof Stored];
    Object.assign(stored, { token, username: user.username, canWrite });
  });
  return { username: user.username };
}
