import path from "node:path";
import { config } from "./config.ts";
import { readJson, updateJson } from "./store.ts";

/**
 * Connexion à GitLab par jeton personnel collé dans la page du compagnon (carte GitLab). Le jeton est vérifié auprès de
 * GitLab (`GET /api/v4/user`), puis gardé dans .data/gitlab.json (0600) sur l'ordinateur : il lit et fusionne (portée
 * `api` ; si GitLab dit qu'il n'a que `read_api`, la fusion est refusée). Rien n'en sort : ni réponse, ni message
 * d'erreur, ni journal, ni tablette. Un ancien compte OAuth enregistré (sans `token`) est ignoré.
 */

const DEFAULT_URL = "https://gitlab.com";
const TIMEOUT_MS = 15_000;

/** Lue à chaque appel (les tests posent l'environnement). */
export const gitlabBaseUrl = () => (process.env.GITLAB_URL?.trim() || DEFAULT_URL).replace(/\/+$/, "");

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

type Stored = { token?: string; username?: string; canWrite?: boolean; expired?: boolean };
const storeFile = path.join(config.dataDir, "gitlab.json");
const load = () => readJson<Stored>(storeFile, () => ({}));
/** Relit le fichier, applique `change`, réécrit. */
const edit = <R = void>(change: (stored: Stored) => R) => updateJson<Stored, R>(storeFile, () => ({}), change, { mode: 0o600 });

/** `expired` : GitLab a refusé le jeton (révoqué, expiré) : en coller un autre. Le jeton ne sort pas d'ici. */
export async function gitlabAccount(): Promise<{ username: string; expired: boolean } | undefined> {
  const stored = await load();
  return stored.token ? { username: stored.username ?? "", expired: !!stored.expired } : undefined;
}

/** Jeton du compte et droit d'écrire (donc de fusionner). `undefined` : pas de compte utilisable (aucun, ou refusé par GitLab). */
export async function gitlabAccess(): Promise<{ token: string; canWrite: boolean } | undefined> {
  const stored = await load();
  return stored.token && !stored.expired ? { token: stored.token, canWrite: !!stored.canWrite } : undefined;
}

/** GitLab a refusé le jeton du compte : il passe à « à remplacer » (la page le dit, la tablette renvoie vers elle). */
export async function gitlabTokenRefused() {
  if (!(await load()).token) return;
  await edit((stored) => {
    if (stored.token) stored.expired = true;
  });
}

export async function disconnectGitlab() {
  await edit((stored) => {
    for (const key of Object.keys(stored)) delete stored[key as keyof Stored];
  });
}

// --- Connexion

/** Appel de vérification : tout échec réseau ou délai devient « GitLab ne répond pas ». */
async function ask(route: string, token: string): Promise<Response> {
  try {
    return await fetch(gitlabBaseUrl() + route, {
      headers: { "PRIVATE-TOKEN": token, accept: "application/json" },
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
