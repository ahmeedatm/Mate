/**
 * Jira de l'utilisateur, via le jeton API du compte (Basic).
 * Optionnel : site, e-mail et jeton d'API sont collés dans la carte Jira de la page du compagnon (src/jira-auth.ts,
 * .data/jira-auth.json), sinon lus dans JIRA_SITE, JIRA_EMAIL et JIRA_API_TOKEN du .env (secours). Pris en compte tout de suite.
 * Jeton : https://id.atlassian.com/manage-profile/security/api-tokens (droits du compte).
 * Usages : temps saisi dans la journée (worklogs), tickets assignés et leurs transitions (onglet Jira).
 */

import path from "node:path";
import { config } from "./config.ts";
import { jiraAccount } from "./jira-auth.ts";
import { singleFlight } from "./single-flight.ts";
import { readJson, removeJson, updateJson } from "./store.ts";

export const jiraConfigured = () => jiraAccount() !== undefined;

const requireAccount = () => {
  const account = jiraAccount();
  if (!account) throw new Error("Jira n'est pas relié : connecte-le dans la page de Mate sur l'ordinateur.");
  return account;
};

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
        ? "Jira refuse les identifiants de l'ordinateur : reconnecte Jira dans la page de Mate."
        : jiraStatus === 404
          ? "Ticket introuvable dans Jira."
          : `Jira HTTP ${jiraStatus}`,
    );
  }
}

const CACHE_MS = 5 * 60_000;
let accountId: string | undefined;
let cache: { day: string; at: number; seconds: number } | undefined;

/** Change à chaque oubli : une lecture d'`accountId` partie avant ne doit pas écrire son résultat après. */
let generation = 0;

/**
 * Compte changé ou déconnecté : plus rien de ce qui a été lu avec l'ancien ne vaut. `wipeChoices` : site ou e-mail
 * différent, ou déconnexion : les tableaux et colonnes choisis (.data/jira.json) sont ceux de l'ancien compte, effacés.
 */
export async function forgetJiraAccount(wipeChoices: boolean) {
  generation++;
  accountId = undefined;
  cache = undefined;
  configs.clear();
  if (wipeChoices) await removeJson(boardsFile);
}

async function jira<T>(path: string, init?: { method: "POST"; body: object }): Promise<T> {
  const { base, email, token } = requireAccount();
  const res = await fetch(`${base}${path}`, {
    method: init?.method ?? "GET",
    headers: {
      authorization: "Basic " + Buffer.from(`${email}:${token}`).toString("base64"),
      accept: "application/json",
      ...(init ? { "content-type": "application/json" } : {}),
    },
    body: init && JSON.stringify(init.body),
    // Le jeton ne suit aucune redirection.
    redirect: "error",
    signal: AbortSignal.timeout(15_000),
  }).catch(() => {
    // Réseau coupé, DNS, délai dépassé : « fetch failed » ne dit rien à l'écran de la tablette.
    throw new Error("Jira ne répond pas : vérifie la connexion de l'ordinateur.");
  });
  if (!res.ok) throw new JiraHttpError(res.status);
  return (res.status === 204 ? undefined : await res.json()) as T;
}

async function myAccountId(): Promise<string> {
  if (accountId) return accountId;
  const gen = generation;
  const id = (await jira<{ accountId: string }>("/rest/api/3/myself")).accountId;
  if (gen === generation) accountId = id;
  return id;
}

const isoDay = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;

/** Secondes saisies le jour donné (heure locale de l'ordinateur). Résultat gardé 5 min sauf `force`. */
export async function worklogSecondsOn(day: Date, force = false): Promise<number> {
  const key = isoDay(day);
  if (!force && cache?.day === key && Date.now() - cache.at < CACHE_MS) return cache.seconds;

  const gen = generation;
  const me = await myAccountId();
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
    for (const w of worklogs) if (w.author.accountId === me) seconds += w.timeSpentSeconds;
  }
  if (gen === generation) cache = { day: key, at: Date.now(), seconds };
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
    assignee?: { accountId: string; displayName: string } | null;
    updated?: string;
  };
};

/** `assignee` : nom de l'assigné, seulement pour un ticket qui n'est pas à moi (colonne « tous les tickets »). */
export type JiraIssue = { key: string; summary: string; type: string | null; priority: string | null; done: boolean; assignee?: string };
export type JiraColumn = { title: string; issues: JiraIssue[] };
export type BoardColumn = { name: string; statusIds: string[] };
export type MergedColumn = { title: string; statusIds: string[] };

const CATEGORY_ORDER = ["new", "indeterminate"];
const categoryRank = (c: string) => (CATEGORY_ORDER.includes(c) ? CATEGORY_ORDER.indexOf(c) : CATEGORY_ORDER.length);

const toIssue = ({ key, fields }: RawIssue, done: boolean, me?: string): JiraIssue => ({
  key,
  summary: fields.summary,
  type: fields.issuetype?.name ?? null,
  priority: fields.priority?.name ?? null,
  done,
  ...(me !== undefined && fields.assignee && fields.assignee.accountId !== me ? { assignee: fields.assignee.displayName } : {}),
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

// Noms entiers (normalisés) tenus pour la même colonne d'un tableau à l'autre. Pas de recherche dans le nom :
// « Ready for Dev » et « Ready for QA », ou « Code Review » et « QA Review », sont des colonnes distinctes.
const SYNONYMS: [string, string[]][] = [
  ["blocked", ["blocked", "bloque", "blocked stand by", "blocked standing by", "stand by", "standing by"]],
  ["todo", ["to do", "todo", "a faire"]],
  ["progress", ["in progress", "en cours"]],
  ["review", ["review", "in review", "revue", "en revue"]],
  ["goqa", ["ready", "go qa", "ready go qa", "ready for qa"]],
  ["inqa", ["in qa"]],
  ["done", ["done", "termine", "terminee"]],
  ["backlog", ["backlog"]],
];

/** Clé de fusion d'un nom de colonne : celle de son groupe de synonymes s'il en fait partie, sinon le nom normalisé (minuscules, sans accents, mots alphanumériques). */
export function columnKey(name: string): string {
  const words = name
    .normalize("NFD")
    .replace(/\p{M}/gu, "")
    .toLowerCase()
    .split(/[^a-z0-9]+/)
    .filter(Boolean)
    .join(" ");
  for (const [key, names] of SYNONYMS) if (names.includes(words)) return key;
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
type JiraStore = { boards: number[]; hidden?: string[]; everyone?: string[] };
const emptyStore = (): JiraStore => ({ boards: [] });

export async function selectedBoards(): Promise<number[]> {
  const { boards } = await readJson(boardsFile, emptyStore);
  return Array.isArray(boards) ? boards.filter((id) => Number.isInteger(id) && id > 0) : [];
}

export async function selectBoards(ids: number[]): Promise<void> {
  await updateJson(boardsFile, emptyStore, (store) => {
    store.boards = [...new Set(ids)];
  });
}

const strings = (list: unknown): string[] => (Array.isArray(list) ? list.filter((k): k is string => typeof k === "string") : []);

/** Choix par colonne, repérées par leur clé de fusion : masquées, et « tous les tickets » (pas seulement les miens). */
export type ColumnChoices = { hidden: string[]; everyone: string[] };

export async function columnChoices(): Promise<ColumnChoices> {
  const store = await readJson(boardsFile, emptyStore);
  return { hidden: strings(store.hidden), everyone: strings(store.everyone) };
}

export async function saveColumnChoices({ hidden, everyone }: ColumnChoices): Promise<void> {
  await updateJson(boardsFile, emptyStore, (store) => {
    store.hidden = [...new Set(hidden)];
    store.everyone = [...new Set(everyone)];
  });
}

/**
 * Tableaux des projets de mes tickets (ouverts et terminés depuis 7 jours), sans doublon : ceux dont le filtre
 * nomme le projet (projectKeyOrId), puis ceux rangés dans le projet (projectLocation), que le premier omet.
 */
export async function myBoards(): Promise<JiraBoard[]> {
  const issues = [...(await search(OPEN_JQL)), ...(await search(DONE_JQL))];
  const projects = [...new Set(issues.map((i) => i.key.replace(/-\d+$/, "")))];
  const perProject = await Promise.all(
    projects.map(async (project) => {
      const found: JiraBoard[] = [];
      for (const param of ["projectKeyOrId", "projectLocation"]) {
        for (let startAt = 0; ;) {
          const page = await jira<{ values: { id: number; name: string }[]; isLast?: boolean }>(
            `/rest/agile/1.0/board?${param}=${encodeURIComponent(project)}&startAt=${startAt}&maxResults=50`,
          );
          for (const { id, name } of page.values) found.push({ id, name, project });
          startAt += page.values.length;
          if (page.isLast !== false || page.values.length === 0) break;
        }
      }
      return found;
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

const EVERYONE_MAX = 50;
const EVERYONE_MS = 20_000;
const EVERYONE_AT_ONCE = 4;

/** Au plus EVERYONE_AT_ONCE lectures de tableau à la fois (colonnes × tableaux ne doivent pas inonder Jira). */
let running = 0;
const waiting: (() => void)[] = [];
async function limited<T>(task: () => Promise<T>): Promise<T> {
  if (running >= EVERYONE_AT_ONCE) await new Promise<void>((resolve) => waiting.push(resolve));
  else running++;
  try {
    return await task();
  } finally {
    // La place libérée passe directement à la lecture suivante en attente.
    const next = waiting.shift();
    if (next) next();
    else running--;
  }
}

// Une lecture gardée 20 s par jeu (tableaux, statuts) : la vue relit chaque minute, et chaque colonne a sa lecture.
const everyoneFlights = new Map<string, ReturnType<typeof singleFlight<JiraIssue[]>>>();

const everyoneIssues = (boardIds: number[], statusIds: string[]): Promise<JiraIssue[]> => {
  const ids = statusIds.filter((id) => /^\d+$/.test(id));
  if (ids.length === 0) return Promise.resolve([]);
  const key = `${boardIds.join(",")}|${ids.join(",")}`;
  if (!everyoneFlights.has(key)) {
    if (everyoneFlights.size >= 50) everyoneFlights.clear();
    everyoneFlights.set(key, singleFlight<JiraIssue[]>(EVERYONE_MS));
  }
  return everyoneFlights.get(key)!.get(() => readEveryoneIssues(boardIds, ids));
};

/** Tickets de tout le monde dans ces statuts, vus par les tableaux choisis (leur filtre s'applique), les plus récents d'abord. */
async function readEveryoneIssues(boardIds: number[], ids: string[]): Promise<JiraIssue[]> {
  const me = await myAccountId();
  const jql = encodeURIComponent(`status in (${ids.join(",")}) ORDER BY updated DESC`);
  const fields = [...SEARCH_FIELDS, "assignee", "updated"].join(",");
  const pages = await Promise.all(
    boardIds.map((id) =>
      limited(() =>
        jira<{ issues: RawIssue[] }>(`/rest/agile/1.0/board/${id}/issue?jql=${jql}&fields=${fields}&maxResults=${EVERYONE_MAX}`),
      ),
    ),
  );
  const byKey = new Map<string, RawIssue>();
  for (const raw of pages.flatMap((p) => p.issues)) if (!byKey.has(raw.key)) byKey.set(raw.key, raw);
  const time = (r: RawIssue) => Date.parse(r.fields.updated ?? "") || 0;
  return [...byKey.values()]
    .sort((a, b) => time(b) - time(a))
    .slice(0, EVERYONE_MAX)
    .map((raw) => toIssue(raw, raw.fields.status.statusCategory.key === "done", me));
}

/** Colonnes des tableaux choisis, réunies. */
const mergedColumns = async (ids: number[]) => mergeBoardColumns(await Promise.all(ids.map(boardColumns)));

export type ColumnChoice = { key: string; title: string; shown: boolean; everyone: boolean };

/** Colonnes de l'union des tableaux choisis avec leurs choix ; un choix sur une colonne qui n'existe plus est ignoré. */
export async function columnList(): Promise<{ boardsChosen: boolean; columns: ColumnChoice[] }> {
  const ids = await selectedBoards();
  if (ids.length === 0) return { boardsChosen: false, columns: [] };
  const [merged, { hidden, everyone }] = await Promise.all([mergedColumns(ids), columnChoices()]);
  return {
    boardsChosen: true,
    columns: merged.map(({ title }) => {
      const key = columnKey(title);
      return { key, title, shown: !hidden.includes(key), everyone: everyone.includes(key) };
    }),
  };
}

/**
 * Tickets assignés au propriétaire du jeton. Sans tableau choisi : une colonne par statut, sans les terminés.
 * Sinon : les colonnes des tableaux choisis (vides comprises) avec les tickets terminés des 7 derniers jours ;
 * un statut absent des tableaux passe en colonne de fin. Une colonne masquée disparaît ; une colonne « tous les
 * tickets » montre ceux de tout le monde (50 au plus) à la place des miens.
 */
export async function myIssues(): Promise<{ boardsChosen: boolean; columns: JiraColumn[] }> {
  const ids = await selectedBoards();
  if (ids.length === 0) return { boardsChosen: false, columns: groupIssues(await search(OPEN_JQL)) };

  const [open, done, merged, { hidden, everyone }] = await Promise.all([
    search(OPEN_JQL),
    search(DONE_JQL),
    mergedColumns(ids),
    columnChoices(),
  ]);
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
  const keys = merged.map((m) => columnKey(m.title));
  await Promise.all(
    merged.map(async (m, i) => {
      if (everyone.includes(keys[i]) && !hidden.includes(keys[i])) columns[i].issues = await everyoneIssues(ids, m.statusIds);
    }),
  );
  return { boardsChosen: true, columns: [...columns.filter((_, i) => !hidden.includes(keys[i])), ...extra.values()] };
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

export const issueUrl = (key: string) => `${requireAccount().base}/browse/${key}`;
