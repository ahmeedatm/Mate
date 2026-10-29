/**
 * Jira de l'utilisateur, via le jeton API du compte (Basic).
 * Optionnel : actif seulement si JIRA_SITE, JIRA_EMAIL et JIRA_API_TOKEN sont définis dans .env.
 * Jeton : https://id.atlassian.com/manage-profile/security/api-tokens (droits du compte).
 * Usages : temps saisi dans la journée (worklogs), tickets assignés et leurs transitions (onglet Jira).
 */

import path from "node:path";
import { config } from "./config.ts";
import { readJson, updateJson } from "./store.ts";

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

// --- Onglet Jira de la tablette : tickets assignés, colonnes des tableaux choisis, transitions

type RawIssue = {
  key: string;
  fields: {
    summary: string;
    status: { id?: string; name: string; statusCategory: { key: string } };
    issuetype?: { name: string } | null;
    priority?: { name: string } | null;
  };
};

export type JiraIssue = { key: string; summary: string; type: string | null; priority: string | null; done: boolean };
export type JiraColumn = { title: string; issues: JiraIssue[] };
export type BoardColumn = { name: string; statusIds: string[] };
export type MergedColumn = { title: string; statusIds: string[] };

const CATEGORY_ORDER = ["new", "indeterminate"];
const categoryRank = (c: string) => (CATEGORY_ORDER.includes(c) ? CATEGORY_ORDER.indexOf(c) : CATEGORY_ORDER.length);

const toIssue = ({ key, fields }: RawIssue, done: boolean): JiraIssue => ({
  key,
  summary: fields.summary,
  type: fields.issuetype?.name ?? null,
  priority: fields.priority?.name ?? null,
  done,
});

/** Une colonne par statut : catégorie « à faire » d'abord, puis « en cours », puis le reste ; alphabétique dans une catégorie ; tickets dans l'ordre de Jira. */
export function groupIssues(issues: RawIssue[]): JiraColumn[] {
  const columns = new Map<string, JiraColumn & { category: string }>();
  for (const raw of issues) {
    const name = raw.fields.status.name;
    let column = columns.get(name);
    if (!column) columns.set(name, (column = { title: name, category: raw.fields.status.statusCategory.key, issues: [] }));
    column.issues.push(toIssue(raw, false));
  }
  return [...columns.values()]
    .sort((a, b) => categoryRank(a.category) - categoryRank(b.category) || a.title.localeCompare(b.title, "fr"))
    .map(({ title, issues }) => ({ title, issues }));
}

const KEYWORDS: [string, string[]][] = [
  ["blocked", ["blocked", "bloque", "stand by", "standing by"]],
  ["todo", ["to do", "todo", "a faire"]],
  ["progress", ["in progress", "en cours"]],
  ["review", ["review", "revue"]],
  ["goqa", ["go qa", "ready"]],
  ["inqa", ["in qa"]],
  ["done", ["done", "termine"]],
  ["backlog", ["backlog"]],
];

/** Clé de fusion d'un nom de colonne : son mot-clé s'il en contient un, sinon le nom normalisé (minuscules, sans accents, mots alphanumériques). */
export function columnKey(name: string): string {
  const words = name
    .normalize("NFD")
    .replace(/\p{M}/gu, "")
    .toLowerCase()
    .split(/[^a-z0-9]+/)
    .filter(Boolean)
    .join(" ");
  const padded = ` ${words} `;
  for (const [key, phrases] of KEYWORDS) if (phrases.some((p) => padded.includes(` ${p} `))) return key;
  return words;
}

/**
 * Union des colonnes de plusieurs tableaux, dans l'ordre du premier. Même clé = même colonne (titre du premier
 * tableau, statuts réunis). Une colonne nouvelle est insérée juste après la dernière colonne de son tableau déjà
 * placée. Deux colonnes d'un même tableau ne fusionnent jamais entre elles.
 */
export function mergeBoardColumns(boards: BoardColumn[][]): MergedColumn[] {
  const merged: (MergedColumn & { key: string })[] = [];
  for (const board of boards) {
    const claimed = new Set<MergedColumn>();
    let anchor = -1;
    for (const column of board) {
      const key = columnKey(column.name);
      const same = merged.find((m) => m.key === key && !claimed.has(m));
      if (same) {
        for (const id of column.statusIds) if (!same.statusIds.includes(id)) same.statusIds.push(id);
        claimed.add(same);
        anchor = merged.indexOf(same);
      } else {
        const added = { key, title: column.name, statusIds: [...new Set(column.statusIds)] };
        merged.splice(anchor + 1, 0, added);
        claimed.add(added);
        anchor++;
      }
    }
  }
  return merged.map(({ title, statusIds }) => ({ title, statusIds }));
}

const SEARCH_FIELDS = ["summary", "status", "issuetype", "priority"];
const OPEN_JQL = "assignee = currentUser() AND statusCategory != Done ORDER BY updated DESC";
const DONE_JQL = "assignee = currentUser() AND statusCategory = Done AND statusCategoryChangedDate >= -7d ORDER BY updated DESC";

const search = async (jql: string) =>
  (await jira<{ issues: RawIssue[] }>("/rest/api/3/search/jql", { method: "POST", body: { jql, fields: SEARCH_FIELDS, maxResults: 100 } }))
    .issues;

// --- Tableaux Jira (API Agile) choisis sur la tablette

export type JiraBoard = { id: number; name: string; project: string };

const boardsFile = path.join(config.dataDir, "jira.json");
const emptyStore = (): { boards: number[] } => ({ boards: [] });

export async function selectedBoards(): Promise<number[]> {
  const { boards } = await readJson(boardsFile, emptyStore);
  return Array.isArray(boards) ? boards.filter((id) => Number.isInteger(id) && id > 0) : [];
}

export async function selectBoards(ids: number[]): Promise<void> {
  await updateJson(boardsFile, emptyStore, (store) => {
    store.boards = [...new Set(ids)];
  });
}

/** Tableaux des projets de mes tickets (ouverts et terminés depuis 7 jours), sans doublon. */
export async function myBoards(): Promise<JiraBoard[]> {
  const issues = [...(await search(OPEN_JQL)), ...(await search(DONE_JQL))];
  const projects = [...new Set(issues.map((i) => i.key.replace(/-\d+$/, "")))];
  const perProject = await Promise.all(
    projects.map(async (project) => {
      const found: JiraBoard[] = [];
      for (let startAt = 0; ;) {
        const page = await jira<{ values: { id: number; name: string }[]; isLast?: boolean }>(
          `/rest/agile/1.0/board?projectKeyOrId=${encodeURIComponent(project)}&startAt=${startAt}&maxResults=50`,
        );
        for (const { id, name } of page.values) found.push({ id, name, project });
        startAt += page.values.length;
        if (page.isLast !== false || page.values.length === 0) return found;
      }
    }),
  );
  const byId = new Map<number, JiraBoard>();
  for (const board of perProject.flat()) if (!byId.has(board.id)) byId.set(board.id, board);
  return [...byId.values()];
}

const CONFIG_MS = 10 * 60_000;
const configs = new Map<number, { at: number; columns: BoardColumn[] }>();

async function boardColumns(id: number): Promise<BoardColumn[]> {
  const known = configs.get(id);
  if (known && Date.now() - known.at < CONFIG_MS) return known.columns;
  const raw = await jira<{ columnConfig: { columns: { name: string; statuses?: { id: string }[] }[] } }>(
    `/rest/agile/1.0/board/${id}/configuration`,
  ).catch((e) => {
    if (e instanceof JiraHttpError && e.jiraStatus === 404)
      throw new Error("Un tableau choisi n'existe plus dans Jira : choisis-le à nouveau.");
    throw e;
  });
  const columns = raw.columnConfig.columns.map((c) => ({ name: c.name, statusIds: (c.statuses ?? []).map((s) => String(s.id)) }));
  configs.set(id, { at: Date.now(), columns });
  return columns;
}

/**
 * Tickets assignés au propriétaire du jeton. Sans tableau choisi : une colonne par statut, sans les terminés.
 * Sinon : les colonnes des tableaux choisis (vides comprises) avec les tickets terminés des 7 derniers jours ;
 * un statut absent des tableaux passe en colonne de fin.
 */
export async function myIssues(): Promise<{ boardsChosen: boolean; columns: JiraColumn[] }> {
  const ids = await selectedBoards();
  if (ids.length === 0) return { boardsChosen: false, columns: groupIssues(await search(OPEN_JQL)) };

  const [open, done, boards] = await Promise.all([search(OPEN_JQL), search(DONE_JQL), Promise.all(ids.map(boardColumns))]);
  const merged = mergeBoardColumns(boards);
  const columns: JiraColumn[] = merged.map((m) => ({ title: m.title, issues: [] }));
  const columnOfStatus = new Map<string, JiraColumn>();
  merged.forEach((m, i) => {
    for (const id of m.statusIds) if (!columnOfStatus.has(id)) columnOfStatus.set(id, columns[i]);
  });
  const extra = new Map<string, JiraColumn>();
  for (const [raw, isDone] of [...open.map((i) => [i, false] as const), ...done.map((i) => [i, true] as const)]) {
    const { id, name } = raw.fields.status;
    let column = id === undefined ? undefined : columnOfStatus.get(String(id));
    if (!column) {
      column = extra.get(name);
      if (!column) extra.set(name, (column = { title: name, issues: [] }));
    }
    column.issues.push(toIssue(raw, isDone));
  }
  return { boardsChosen: true, columns: [...columns, ...extra.values()] };
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
