/**
 * Jira de l'utilisateur, via le jeton API du compte (Basic).
 * Optionnel : actif seulement si JIRA_SITE, JIRA_EMAIL et JIRA_API_TOKEN sont définis dans .env.
 * Jeton : https://id.atlassian.com/manage-profile/security/api-tokens (droits du compte).
 * Usages : temps saisi dans la journée (worklogs), tickets assignés et leurs transitions (onglet Jira).
 */

const rawSite = process.env.JIRA_SITE?.trim().replace(/\/+$/, "");
const email = process.env.JIRA_EMAIL?.trim();
const token = process.env.JIRA_API_TOKEN?.trim();

// Toujours en https (jamais d'identifiants en clair sur le réseau) ; seul un faux Jira local, donné explicitement
// en `http://127.0.0.1:<port>` ou `http://localhost:<port>` (tests), reste en http.
const LOCAL_HTTP = /^http:\/\/(127\.0\.0\.1|localhost)(:\d+)?$/i;
const base = rawSite && (LOCAL_HTTP.test(rawSite) ? rawSite : `https://${rawSite.replace(/^https?:\/\//i, "")}`);

export const jiraConfigured = Boolean(base && email && token);

/** Jira refuse la transition : un champ obligatoire reste à remplir (le drapeau `required` peut être incomplet). */
export class JiraFieldsError extends Error {
  constructor() {
    super("À finir dans Jira : ce statut demande des champs à remplir.");
  }
}

class JiraHttpError extends Error {
  // Pas `status` ni `statusCode` : Fastify les renverrait à la tablette, où 401 veut dire « jeton d'appairage refusé ».
  constructor(readonly jiraStatus: number) {
    super(
      jiraStatus === 401 || jiraStatus === 403
        ? "Jira refuse les identifiants du fichier .env de l'ordinateur."
        : jiraStatus === 404
          ? "Ticket introuvable dans Jira."
          : `Jira HTTP ${jiraStatus}`,
    );
  }
}

const CACHE_MS = 5 * 60_000;
let accountId: string | undefined;
let cache: { day: string; at: number; seconds: number } | undefined;

async function jira<T>(path: string, init?: { method: "POST"; body: object }): Promise<T> {
  const res = await fetch(`${base}${path}`, {
    method: init?.method ?? "GET",
    headers: {
      authorization: "Basic " + Buffer.from(`${email}:${token}`).toString("base64"),
      accept: "application/json",
      ...(init ? { "content-type": "application/json" } : {}),
    },
    body: init && JSON.stringify(init.body),
    signal: AbortSignal.timeout(15_000),
  }).catch(() => {
    // Réseau coupé, DNS, délai dépassé : « fetch failed » ne dit rien à l'écran de la tablette.
    throw new Error("Jira ne répond pas : vérifie la connexion de l'ordinateur.");
  });
  if (!res.ok) throw new JiraHttpError(res.status);
  return (res.status === 204 ? undefined : await res.json()) as T;
}

const isoDay = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;

/** Secondes saisies le jour donné (heure locale de l'ordinateur). Résultat gardé 5 min sauf `force`. */
export async function worklogSecondsOn(day: Date, force = false): Promise<number> {
  const key = isoDay(day);
  if (!force && cache?.day === key && Date.now() - cache.at < CACHE_MS) return cache.seconds;

  accountId ??= (await jira<{ accountId: string }>("/rest/api/3/myself")).accountId;
  const start = new Date(day);
  start.setHours(0, 0, 0, 0);
  const end = start.getTime() + 86_400_000;

  const { issues } = await jira<{ issues: { id: string }[] }>("/rest/api/3/search/jql", {
    method: "POST",
    body: { jql: `worklogAuthor = currentUser() AND worklogDate = "${key}"`, fields: ["summary"], maxResults: 100 },
  });
  let seconds = 0;
  for (const issue of issues) {
    const { worklogs } = await jira<{ worklogs: { author: { accountId: string }; timeSpentSeconds: number }[] }>(
      `/rest/api/3/issue/${issue.id}/worklog?startedAfter=${start.getTime()}&startedBefore=${end}&maxResults=1000`,
    );
    for (const w of worklogs) if (w.author.accountId === accountId) seconds += w.timeSpentSeconds;
  }
  cache = { day: key, at: Date.now(), seconds };
  return seconds;
}

// --- Onglet Jira de la tablette : tickets assignés, transitions

type RawIssue = {
  key: string;
  fields: {
    summary: string;
    status: { name: string; statusCategory: { key: string } };
    issuetype?: { name: string } | null;
    priority?: { name: string } | null;
  };
};

export type JiraIssue = { key: string; summary: string; type: string | null; priority: string | null };
export type JiraColumn = { status: string; category: string; issues: JiraIssue[] };

const CATEGORY_ORDER = ["new", "indeterminate"];
const categoryRank = (c: string) => (CATEGORY_ORDER.includes(c) ? CATEGORY_ORDER.indexOf(c) : CATEGORY_ORDER.length);

/** Une colonne par statut : catégorie « à faire » d'abord, puis « en cours », puis le reste ; alphabétique dans une catégorie ; tickets dans l'ordre de Jira. */
export function groupIssues(issues: RawIssue[]): JiraColumn[] {
  const columns = new Map<string, JiraColumn>();
  for (const { key, fields } of issues) {
    const name = fields.status.name;
    let column = columns.get(name);
    if (!column) columns.set(name, (column = { status: name, category: fields.status.statusCategory.key, issues: [] }));
    column.issues.push({ key, summary: fields.summary, type: fields.issuetype?.name ?? null, priority: fields.priority?.name ?? null });
  }
  return [...columns.values()].sort(
    (a, b) => categoryRank(a.category) - categoryRank(b.category) || a.status.localeCompare(b.status, "fr"),
  );
}

/** Tickets assignés au propriétaire du jeton et pas terminés. */
export async function myIssues(): Promise<JiraColumn[]> {
  const { issues } = await jira<{ issues: RawIssue[] }>("/rest/api/3/search/jql", {
    method: "POST",
    body: {
      jql: "assignee = currentUser() AND statusCategory != Done ORDER BY updated DESC",
      fields: ["summary", "status", "issuetype", "priority"],
      maxResults: 100,
    },
  });
  return groupIssues(issues);
}

export type JiraTransition = { id: string; name: string; to: string; needsJira: boolean };

/** Transitions possibles depuis le statut courant ; `needsJira` : un champ obligatoire sans valeur par défaut. */
export async function issueTransitions(key: string): Promise<JiraTransition[]> {
  const path = `/rest/api/3/issue/${key}`;
  const [{ fields }, { transitions }] = await Promise.all([
    jira<{ fields: { status: { id: string } } }>(`${path}?fields=status`),
    jira<{
      transitions: {
        id: string;
        name: string;
        to: { id: string; name: string };
        isAvailable?: boolean;
        fields?: Record<string, { required?: boolean; hasDefaultValue?: boolean }>;
      }[];
    }>(`${path}/transitions?expand=transitions.fields`),
  ]);
  return transitions
    .filter((t) => t.isAvailable !== false && t.to.id !== fields.status.id)
    .map((t) => ({
      id: t.id,
      name: t.name,
      to: t.to.name,
      needsJira: Object.values(t.fields ?? {}).some((f) => f.required && !f.hasDefaultValue),
    }));
}

export async function transitionIssue(key: string, id: string): Promise<void> {
  try {
    await jira<void>(`/rest/api/3/issue/${key}/transitions`, { method: "POST", body: { transition: { id } } });
  } catch (e) {
    if (e instanceof JiraHttpError && e.jiraStatus === 400) throw new JiraFieldsError();
    throw e;
  }
}

export const issueUrl = (key: string) => `${base}/browse/${key}`;
