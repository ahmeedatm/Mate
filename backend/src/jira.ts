/**
 * Temps saisi dans Jira (worklogs natifs) par le propriétaire du jeton, pour une journée.
 * Optionnel : actif seulement si JIRA_SITE, JIRA_EMAIL et JIRA_API_TOKEN sont définis dans .env.
 * Jeton : https://id.atlassian.com/manage-profile/security/api-tokens (droits du compte, lecture seule ici).
 */

const site = process.env.JIRA_SITE?.trim().replace(/^https?:\/\//, "").replace(/\/+$/, "");
const email = process.env.JIRA_EMAIL?.trim();
const token = process.env.JIRA_API_TOKEN?.trim();

export const jiraConfigured = Boolean(site && email && token);

const CACHE_MS = 5 * 60_000;
let accountId: string | undefined;
let cache: { day: string; at: number; seconds: number } | undefined;

async function jira<T>(path: string, init?: { method: "POST"; body: object }): Promise<T> {
  const res = await fetch(`https://${site}${path}`, {
    method: init?.method ?? "GET",
    headers: {
      authorization: "Basic " + Buffer.from(`${email}:${token}`).toString("base64"),
      accept: "application/json",
      ...(init ? { "content-type": "application/json" } : {}),
    },
    body: init && JSON.stringify(init.body),
    signal: AbortSignal.timeout(15_000),
  });
  if (!res.ok) throw new Error(`Jira HTTP ${res.status}`);
  return (await res.json()) as T;
}

const isoDay = (d: Date) =>
  `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;

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
