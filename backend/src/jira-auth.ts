import fs from "node:fs";
import path from "node:path";
import { config } from "./config.ts";
import { removeJson, updateJson } from "./store.ts";

/**
 * Connexion à Jira par site, e-mail et jeton d'API collés dans la page du compagnon (carte Jira). Le trio est vérifié
 * auprès de Jira (`GET /rest/api/3/myself`), puis gardé dans .data/jira-auth.json (0600) sur l'ordinateur, à part de
 * .data/jira.json (tableaux et colonnes). Rien n'en sort : ni réponse, ni message d'erreur, ni journal, ni tablette.
 * Le .env (JIRA_SITE, JIRA_EMAIL, JIRA_API_TOKEN) reste en secours ; les identifiants collés priment.
 */

const TIMEOUT_MS = 15_000;

// Toujours en https (jamais d'identifiants en clair sur le réseau) ; seul un faux Jira local, donné explicitement
// en `http://127.0.0.1:<port>` ou `http://localhost:<port>` (tests), reste en http.
const LOCAL_HTTP = /^http:\/\/(127\.0\.0\.1|localhost)(:\d+)?$/i;

/** Message en français montré dans la page locale : jamais le jeton ni le corps de la réponse de Jira. */
export class JiraAuthError extends Error {
  constructor(
    readonly statusCode: number,
    message: string,
  ) {
    super(message);
  }
}
const BAD_SITE = new JiraAuthError(400, "Site Jira non reconnu : écris par exemple « unowhy » ou « unowhy.atlassian.net »");
const BAD_EMAIL = new JiraAuthError(400, "Adresse e-mail invalide : c'est celle de ton compte Atlassian");
const REFUSED = new JiraAuthError(400, "Jira refuse ces identifiants : vérifie l'e-mail, et que le jeton est complet et n'a pas expiré");
const UNREACHABLE = new JiraAuthError(502, "Jira ne répond pas : vérifie le nom du site et la connexion de l'ordinateur");

export type JiraAccount = { base: string; email: string; token: string; source: "page" | "env" };

type Stored = { site?: string; email?: string; token?: string };
const storeFile = path.join(config.dataDir, "jira-auth.json");

/** Lu une fois au démarrage, puis tenu à jour par connectJira / disconnectJira : les appelants le lisent sans attendre. */
let pasted: Stored = readStored();

function readStored(): Stored {
  try {
    const data = JSON.parse(fs.readFileSync(storeFile, "utf8")) as Stored;
    return typeof data.site === "string" && typeof data.email === "string" && typeof data.token === "string" ? data : {};
  } catch {
    return {};
  }
}

/** Identifiants utilisés : ceux collés dans la page, sinon ceux du .env. `undefined` : Jira n'est pas relié. */
export function jiraAccount(): JiraAccount | undefined {
  if (pasted.site && pasted.email && pasted.token) return { base: pasted.site, email: pasted.email, token: pasted.token, source: "page" };
  const rawSite = process.env.JIRA_SITE?.trim().replace(/\/+$/, "");
  const email = process.env.JIRA_EMAIL?.trim();
  const token = process.env.JIRA_API_TOKEN?.trim();
  const base = rawSite && (LOCAL_HTTP.test(rawSite) ? rawSite : `https://${rawSite.replace(/^https?:\/\//i, "")}`);
  return base && email && token ? { base, email, token, source: "env" } : undefined;
}

/** Ce que la page locale voit : l'hôte du site relié et d'où viennent les identifiants, jamais l'e-mail ni le jeton. */
export function jiraStatus() {
  const account = jiraAccount();
  return account ? { configured: true, site: new URL(account.base).host, source: account.source } : { configured: false, site: null };
}

/** « unowhy », « unowhy.atlassian.net » ou une adresse entière → `https://unowhy.atlassian.net`. `undefined` : pas un site Atlassian. */
export function normalizeJiraSite(input: string): string | undefined {
  const text = input.trim().replace(/\/+$/, "");
  if (LOCAL_HTTP.test(text)) return text.toLowerCase();
  const host = text
    .replace(/^https?:\/\//i, "")
    .split(/[/?#]/)[0]
    .toLowerCase();
  const full = host.includes(".") ? host : `${host}.atlassian.net`;
  return /^[a-z0-9][a-z0-9-]*\.atlassian\.net$/.test(full) ? `https://${full}` : undefined;
}

/** Vérifie les identifiants auprès de Jira puis les garde. Refusés ou injoignable : lève, rien n'est enregistré. */
export async function connectJira(input: { site: string; email: string; token: string }): Promise<{ site: string }> {
  const base = normalizeJiraSite(input.site);
  if (!base) throw BAD_SITE;
  const email = input.email.trim();
  if (!/^[^\s@]+@[^\s@]+$/.test(email)) throw BAD_EMAIL;
  const token = input.token.trim();
  if (!/^\S{8,500}$/.test(token)) throw REFUSED;

  let res: Response;
  try {
    res = await fetch(`${base}/rest/api/3/myself`, {
      headers: { authorization: "Basic " + Buffer.from(`${email}:${token}`).toString("base64"), accept: "application/json" },
      // Pas de redirection suivie : un site inexistant renvoie vers une page de connexion, pas un Jira.
      redirect: "manual",
      signal: AbortSignal.timeout(TIMEOUT_MS),
    });
  } catch {
    throw UNREACHABLE;
  }
  if (res.status === 401 || res.status === 403) throw REFUSED;
  const me = (await res.json().catch(() => ({}))) as { accountId?: unknown };
  if (!res.ok || typeof me.accountId !== "string") throw UNREACHABLE;

  await updateJson<Stored>(
    storeFile,
    () => ({}),
    (stored) => {
      for (const key of Object.keys(stored)) delete stored[key as keyof Stored];
      Object.assign(stored, { site: base, email, token });
    },
    { mode: 0o600 },
  );
  pasted = { site: base, email, token };
  return { site: new URL(base).host };
}

export async function disconnectJira() {
  await removeJson(storeFile);
  pasted = {};
}
