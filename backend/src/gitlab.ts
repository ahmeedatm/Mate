import { gitlabAccess, gitlabAccount, gitlabApiUrl, gitlabTokenRefused } from "./gitlab-auth.ts";
import { join } from "node:path";
import { config } from "./config.ts";
import { logFailure } from "./log.ts";
import { singleFlight } from "./single-flight.ts";
import { readJson as readStoreJson, updateJson } from "./store.ts";

/**
 * MR GitLab de l'utilisateur connecté : les siennes et celles à relire, avec pipeline, approbations et état de fusion ;
 * fusion d'une MR prête. Optionnel. Accès : le jeton personnel collé dans la page du compagnon (src/gitlab-auth.ts,
 * prioritaire), sinon GITLAB_TOKEN dans .env (secours). Lire suffit avec `read_api` ; fusionner demande `api` : le jeton
 * collé s'il l'a, sinon GITLAB_TOKEN (supposé `api`), sinon la fusion est refusée. GITLAB_URL : défaut
 * https://gitlab.com. Aucun jeton ne quitte l'ordinateur : ni réponse, ni message d'erreur, ni journal.
 * Config lue à chaque appel (les tests posent l'environnement).
 */

export type Pipeline = "success" | "running" | "failed" | "canceled" | "none";
/** Un stage du pipeline : son nom et son état réduit comme celui du pipeline. */
export type Stage = { name: string; status: Pipeline };
/**
 * Colonne du tableau des MR de la tablette. Mes MR, une seule colonne chacune, par priorité : `draft` (brouillon), `fix`
 * (discussion de review non résolue plus récente que mon dernier commit), `merge` (approuvée), `running` (pipeline en cours),
 * `ready` (le reste : prête pour la review). `review` : MR que je dois relire.
 */
export type MrColumn = "draft" | "fix" | "merge" | "running" | "ready" | "review";
export type Mr = {
  project: string;
  iid: number;
  title: string;
  draft: boolean;
  author: string;
  pipeline: Pipeline;
  approvals: { given: number; required: number; by: string[] };
  status: string;
  statusLabel: string;
  mergeable: boolean;
  sha: string;
  updatedAt: string;
  /** GitLab seulement (une PR GitHub n'a ni colonne ni stages). */
  column?: MrColumn;
  stages?: Stage[];
};

/** Message en français montré sur la tablette : jamais le jeton ni le corps de la réponse de GitLab. */
export class GitLabError extends Error {
  constructor(
    readonly statusCode: number,
    message: string,
  ) {
    super(message);
  }
}

const CACHE_MS = 20_000;
const CHOICES_MS = 5 * 60_000;
const TIMEOUT_MS = 15_000;

const envToken = () => process.env.GITLAB_TOKEN?.trim() ?? "";

const refusedUrl = (message: string) => new GitLabError(502, message);

export const mrUrl = (project: string, iid: number) => `${gitlabApiUrl(refusedUrl)}/${project}/-/merge_requests/${iid}`;

// Valeurs de DetailedMergeStatus relevées par introspection sur gitlab.com (18.10.8-ee), le 29/10/2026.
const LABELS: Record<string, string> = {
  mergeable: "Prête à fusionner",
  not_approved: "Approbation manquante",
  ci_still_running: "Pipeline en cours",
  ci_must_pass: "Pipeline à faire passer",
  draft_status: "Brouillon",
  conflict: "Conflit à résoudre",
  need_rebase: "Rebase nécessaire",
  discussions_not_resolved: "Discussions à résoudre",
  blocked_status: "Bloquée par une autre MR",
  unchecked: "Vérification en cours",
  checking: "Vérification en cours",
  preparing: "Vérification en cours",
  approvals_syncing: "Vérification en cours",
  requested_changes: "Modifications demandées",
  commits_status: "Branche source vide ou absente",
  not_open: "MR fermée",
  external_status_checks: "Contrôles externes en attente",
  jira_association: "Ticket Jira manquant",
  locked_paths: "Fichiers verrouillés",
  locked_lfs_files: "Fichiers LFS verrouillés",
  merge_time: "Fusion pas encore autorisée",
  security_policies_violations: "Politique de sécurité non respectée",
  title_not_matching: "Titre non conforme",
};
export const statusLabel = (status: string) => LABELS[status.toLowerCase()] ?? "Pas fusionnable pour l'instant";

const PIPELINES: Record<string, Pipeline> = {
  SUCCESS: "success",
  SUCCESS_WITH_WARNINGS: "success",
  FAILED: "failed",
  CANCELED: "canceled",
  CANCELING: "canceled",
  RUNNING: "running",
  PENDING: "running",
  CREATED: "running",
  PREPARING: "running",
  WAITING_FOR_RESOURCE: "running",
  WAITING_FOR_CALLBACK: "running",
  SCHEDULED: "running",
};
export const pipelineOf = (status: string | null | undefined): Pipeline => PIPELINES[status ?? ""] ?? "none";

const PIPELINE_FIELDS = "id status ref createdAt startedAt finishedAt duration stages { nodes { name status } }";
const MR_FIELDS = `iid title draft detailedMergeStatus diffHeadSha updatedAt approved approvalsRequired approvalsLeft
  approvedBy { nodes { name username } } author { name } project { fullPath } headPipeline { ${PIPELINE_FIELDS} }
  commitsWithoutMergeCommits(first: 20) { nodes { committedDate author { username } } }
  discussions(first: 50) { nodes { resolvable resolved notes(first: 50) { nodes { createdAt system author { username } } } } }`;
const QUERY = `{ currentUser { username
  assignedMergeRequests(state: opened, first: 50) { nodes { ${MR_FIELDS} } }
  reviewRequestedMergeRequests(state: opened, first: 50) { nodes { ${MR_FIELDS} } }
} }`;

type Person = { username?: string | null } | null;
type GqlPipeline = {
  /** `gid://gitlab/Ci::Pipeline/<id>`. */
  id: string;
  status: string;
  ref?: string | null;
  createdAt?: string | null;
  startedAt?: string | null;
  finishedAt?: string | null;
  duration?: number | null;
  stages?: { nodes: { name: string; status: string }[] } | null;
};
export type MrNode = {
  iid: string;
  title: string;
  draft?: boolean;
  detailedMergeStatus: string;
  diffHeadSha: string;
  updatedAt: string;
  approved?: boolean | null;
  approvalsRequired?: number | null;
  approvalsLeft?: number | null;
  approvedBy?: { nodes: { name: string; username?: string | null }[] } | null;
  author?: { name: string } | null;
  project: { fullPath: string };
  headPipeline?: GqlPipeline | null;
  commitsWithoutMergeCommits?: { nodes: { committedDate?: string | null; author?: Person }[] } | null;
  discussions?: {
    nodes: {
      resolvable?: boolean;
      resolved?: boolean;
      notes?: { nodes: { createdAt?: string | null; system?: boolean; author?: Person }[] } | null;
    }[];
  } | null;
};
type MrList = { nodes: MrNode[] };

const stagesOf = (p?: GqlPipeline | null): Stage[] =>
  (p?.stages?.nodes ?? []).map((s) => ({ name: s.name, status: pipelineOf(s.status.toUpperCase()) }));

const timeOf = (iso?: string | null) => {
  const t = Date.parse(iso ?? "");
  return Number.isFinite(t) ? t : -Infinity;
};
const isMe = (who: Person | undefined, me: string) => me !== "" && who?.username === me;
const discussionsOf = (n: MrNode) => n.discussions?.nodes ?? [];
const notesOf = (d: { notes?: { nodes: { createdAt?: string | null; system?: boolean; author?: Person }[] } | null }) =>
  d.notes?.nodes ?? [];

/** Discussion de review ouverte, avec un message d'un autre que moi plus récent que mon dernier commit (ou sans commit de moi). */
function needsFix(n: MrNode, me: string): boolean {
  const lastMine = Math.max(
    -Infinity,
    ...(n.commitsWithoutMergeCommits?.nodes ?? []).filter((c) => isMe(c.author, me)).map((c) => timeOf(c.committedDate)),
  );
  return discussionsOf(n).some(
    (d) =>
      d.resolvable === true &&
      d.resolved !== true &&
      notesOf(d).some((note) => note.system !== true && !isMe(note.author, me) && timeOf(note.createdAt) > lastMine),
  );
}

/** Colonne d'une de mes MR (assignées à moi, `me` = mon identifiant GitLab) : la première qui s'applique, dans l'ordre de [MrColumn]. */
export function mrColumn(n: MrNode, me: string): Exclude<MrColumn, "review"> {
  if (n.draft === true) return "draft";
  if (needsFix(n, me)) return "fix";
  // `approved` est vrai aussi quand aucune approbation n'est exigée : il faut qu'une personne ait approuvé.
  if (n.approved === true && (n.approvedBy?.nodes.length ?? 0) > 0) return "merge";
  if (pipelineOf(n.headPipeline?.status) === "running") return "running";
  return "ready";
}

/** MR à relire : ni approuvée par moi, ni commentée par moi. */
export function needsMyReview(n: MrNode, me: string): boolean {
  const approved = (n.approvedBy?.nodes ?? []).some((a) => isMe(a, me));
  const commented = discussionsOf(n).some((d) => notesOf(d).some((note) => note.system !== true && isMe(note.author, me)));
  return !approved && !commented;
}

function toMr(n: MrNode, column: MrColumn): Mr {
  const required = n.approvalsRequired ?? 0;
  const status = n.detailedMergeStatus.toLowerCase();
  return {
    project: n.project.fullPath,
    iid: Number(n.iid),
    title: n.title,
    draft: n.draft === true,
    author: n.author?.name ?? "",
    pipeline: pipelineOf(n.headPipeline?.status),
    approvals: { given: Math.max(0, required - (n.approvalsLeft ?? 0)), required, by: (n.approvedBy?.nodes ?? []).map((a) => a.name) },
    status,
    statusLabel: statusLabel(status),
    mergeable: status === "mergeable",
    sha: n.diffHeadSha,
    updatedAt: n.updatedAt,
    column,
    stages: stagesOf(n.headPipeline),
  };
}
const newestFirst = <T extends { updatedAt: string }>(list: T[]) => list.sort((a, b) => Date.parse(b.updatedAt) - Date.parse(a.updatedAt));

const NOT_CONNECTED = new GitLabError(503, "GitLab n'est pas connecté sur l'ordinateur");
const UNREACHABLE = new GitLabError(502, "GitLab ne répond pas");
const TOKEN_REFUSED = new GitLabError(
  502,
  "GitLab refuse l'accès de l'ordinateur : colle un nouveau jeton dans la page de Mate sur l'ordinateur",
);

const READ_ONLY = new GitLabError(409, "La connexion GitLab est en lecture seule : fusionne sur l'ordinateur");

type Auth = Record<string, string>;

/**
 * En-têtes d'accès. `read` : le jeton collé prime, sinon GITLAB_TOKEN. `write` : le jeton collé s'il a la portée `api`,
 * sinon GITLAB_TOKEN. `undefined` : pas d'accès de ce genre.
 */
async function authHeaders(): Promise<{ read?: Auth; write?: Auth }> {
  const access = await gitlabAccess();
  const account = access ? { "PRIVATE-TOKEN": access.token } : undefined;
  const personal = envToken() ? { "PRIVATE-TOKEN": envToken() } : undefined;
  return { read: account ?? personal, write: account && access?.canWrite ? account : personal };
}

/** Appel GitLab : tout échec réseau, redirection ou délai devient « ne répond pas ». */
async function call(path: string, method: "GET" | "POST" | "PUT", body: object | undefined, auth: Auth): Promise<Response> {
  const base = gitlabApiUrl(refusedUrl);
  try {
    return await fetch(base + path, {
      method,
      headers: { ...auth, "content-type": "application/json", accept: "application/json" },
      body: body && JSON.stringify(body),
      redirect: "error",
      signal: AbortSignal.timeout(TIMEOUT_MS),
    });
  } catch {
    throw UNREACHABLE;
  }
}

/** Ce que la lecture GraphQL rapporte : mes MR et celles à relire, et le pipeline de chacune de mes MR (widget Pipelines). */
type MrRead = MrLists & { mrPipelines: MrPipeline[] };
type MrPipeline = { project: string; iid: number; title: string; updatedAt: string; pipeline: GqlPipeline };

async function readMrs(auth: Auth): Promise<MrRead> {
  const res = await call("/api/graphql", "POST", { query: QUERY }, auth);
  if (res.status === 401 || res.status === 403) {
    // Jeton révoqué, expiré ou sans droit de lecture : le compte passe à « à remplacer ».
    await gitlabTokenRefused(auth["PRIVATE-TOKEN"]);
    throw TOKEN_REFUSED;
  }
  if (!res.ok) throw UNREACHABLE;
  let data: {
    errors?: unknown;
    data?: { currentUser?: { username?: string; assignedMergeRequests: MrList; reviewRequestedMergeRequests: MrList } | null };
  };
  try {
    data = await res.json();
  } catch {
    throw UNREACHABLE;
  }
  if (data.errors) throw UNREACHABLE;
  const user = data.data?.currentUser;
  if (!user) {
    await gitlabTokenRefused(auth["PRIVATE-TOKEN"]);
    throw TOKEN_REFUSED;
  }
  const me = user.username ?? "";
  const assigned = user.assignedMergeRequests.nodes;
  return {
    mine: newestFirst(assigned.map((n) => toMr(n, mrColumn(n, me)))),
    review: newestFirst(user.reviewRequestedMergeRequests.nodes.filter((n) => needsMyReview(n, me)).map((n) => toMr(n, "review"))),
    mrPipelines: assigned.flatMap((n) =>
      n.headPipeline
        ? [{ project: n.project.fullPath, iid: Number(n.iid), title: n.title, updatedAt: n.updatedAt, pipeline: n.headPipeline }]
        : [],
    ),
  };
}

const mrsFlight = singleFlight<MrRead>(CACHE_MS);
const pipelinesFlight = singleFlight<PipelinesRead>(CACHE_MS);
const choicesFlight = singleFlight<ScheduleProject[]>(CHOICES_MS);

export const clearGitlabCache = () => {
  mrsFlight.clear();
  pipelinesFlight.clear();
  choicesFlight.clear();
};

export type MrLists = { mine: Mr[]; review: Mr[] };
/**
 * `connected: false` : ni jeton collé ni jeton .env ; `expired` : un jeton a été collé mais GitLab le refuse.
 * `canMerge` : un accès en écriture existe (jeton collé avec `api`, ou GITLAB_TOKEN) ; sinon la tablette renvoie vers l'ordinateur.
 */
export type MrsAnswer =
  ({ connected: true; canMerge: boolean } & MrLists) | { connected: false; expired: boolean; canMerge: false; mine: []; review: [] };

/** Mes MR et celles à relire. Résultat gardé 20 s, une seule lecture à la fois ; une erreur n'est pas gardée. Pas connecté : réponse normale, GitLab n'est pas appelé. */
export async function listMrs(): Promise<MrsAnswer> {
  const { read: auth, write } = await authHeaders();
  if (!auth) return { connected: false, expired: !!(await gitlabAccount())?.expired, canMerge: false, mine: [], review: [] };
  const { mine, review } = await read(auth);
  return { connected: true, canMerge: !!write, mine, review };
}

// Une fusion vide le cache : la lecture déjà partie ne le remplit plus (elle date d'avant la fusion).
const read = (auth: Auth) => mrsFlight.get(() => readMrs(auth));

const MERGE_ERRORS: Record<number, GitLabError> = {
  405: new GitLabError(409, "Cette MR ne peut pas être fusionnée pour l'instant"),
  406: new GitLabError(409, "Cette MR ne peut pas être fusionnée pour l'instant"),
  409: new GitLabError(409, "La MR a changé depuis l'affichage : relis la liste"),
  401: new GitLabError(502, "Le jeton GitLab n'a pas le droit de fusionner"),
  403: new GitLabError(502, "Le jeton GitLab n'a pas le droit de fusionner"),
  404: new GitLabError(404, "MR introuvable"),
  422: new GitLabError(409, "La branche ne peut pas être fusionnée"),
};

/** Fusionne la MR à condition que sa tête soit encore `sha` (GitLab refuse en 409 sinon). */
export async function mergeMr(project: string, iid: number, sha: string): Promise<void> {
  // Compte en lecture seule et pas de jeton .env : refus tout de suite, sans toucher à GitLab.
  const access = await gitlabAccess();
  if (!envToken() && access && !access.canWrite) throw READ_ONLY;
  const { read: connected, write: auth } = await authHeaders();
  if (!auth) throw connected ? READ_ONLY : NOT_CONNECTED;
  const res = await call(`/api/v4/projects/${encodeURIComponent(project)}/merge_requests/${iid}/merge`, "PUT", { sha }, auth);
  // Fusionnée ou refusée (elle a bougé, n'est plus fusionnable…) : la liste suivante relit GitLab.
  clearGitlabCache();
  if (res.status === 401) await gitlabTokenRefused(auth["PRIVATE-TOKEN"]);
  if (!res.ok) throw MERGE_ERRORS[res.status] ?? UNREACHABLE;
}

// --- Pipelines (console de déploiement) : le dernier run des pipelines planifiées choisies, et les pipelines de mes MR.

/** Une ligne de pipeline (ou de run GitHub Actions, backend/src/github.ts, qui n'a que ces champs). */
export type PipelineRow = {
  project: string;
  ref: string;
  id: number;
  pipeline: Pipeline;
  /** Durée du pipeline terminé ; null tant qu'il tourne. */
  durationSec: number | null;
  /** Âge à la lecture : depuis la fin, ou depuis le départ s'il tourne. */
  ageSec: number;
  /** Jobs en échec (3 au plus), vide hors échec. */
  failedJobs: string[];
};
export type PipelinesAnswer = { connected: true; pipelines: PipelineRow[] } | { connected: false; expired: boolean; pipelines: [] };

/** Ligne GitLab : `title` = description de la planification ou titre de la MR ; `mrIid` : numéro de la MR, null pour une planification. */
export type GitlabPipelineRow = PipelineRow & { title: string; mrIid: number | null; stages: Stage[] };
export type GitlabPipelinesAnswer =
  | { connected: true; schedules: GitlabPipelineRow[]; mrs: GitlabPipelineRow[] }
  | { connected: false; expired: boolean; schedules: []; mrs: [] };

const MAX_MR_PIPELINES = 12;
const MAX_FAILED_JOBS = 3;
const MAX_SCHEDULES = 20;

export const pipelineUrl = (project: string, id: number) => `${gitlabApiUrl(refusedUrl)}/${project}/-/pipelines/${id}`;

/** En cours d'abord, puis les échecs, puis le reste ; du plus récent au plus ancien dans chaque groupe. */
export function sortPipelines<T extends { pipeline: Pipeline; ageSec: number }>(rows: T[]): T[] {
  const rank = (r: T) => (r.pipeline === "running" ? 0 : r.pipeline === "failed" ? 1 : 2);
  return [...rows].sort((a, b) => rank(a) - rank(b) || a.ageSec - b.ageSec);
}

/** Jeton refusé : le compte passe à « à remplacer », la tablette ne reçoit jamais de 401. */
async function refuseIfDenied(res: Response, auth: Auth) {
  if (res.status === 401) {
    await gitlabTokenRefused(auth["PRIVATE-TOKEN"]);
    throw TOKEN_REFUSED;
  }
}

/** Lecture JSON d'un appel GitLab ; null si la ressource est introuvable (404) ou interdite au compte (403). */
async function readJson<T>(path: string, auth: Auth): Promise<T | null> {
  const res = await call(path, "GET", undefined, auth);
  await refuseIfDenied(res, auth);
  if (res.status === 404 || res.status === 403) return null;
  if (!res.ok) throw UNREACHABLE;
  try {
    return (await res.json()) as T;
  } catch {
    throw UNREACHABLE;
  }
}

async function failedJobs(project: string, id: number, auth: Auth): Promise<string[]> {
  try {
    const res = await call(
      `/api/v4/projects/${encodeURIComponent(project)}/pipelines/${id}/jobs?scope[]=failed&per_page=${MAX_FAILED_JOBS}`,
      "GET",
      undefined,
      auth,
    );
    if (!res.ok) return [];
    const jobs = (await res.json()) as { name?: string }[];
    return jobs.flatMap((j) => (j.name ? [j.name] : [])).slice(0, MAX_FAILED_JOBS);
  } catch (e) {
    // Détail secondaire : la ligne s'affiche sans les jobs.
    logFailure("Jobs en échec d'un pipeline GitLab", e);
    return [];
  }
}

/** Numéro à la fin d'un identifiant global (`gid://gitlab/Ci::Pipeline/383203` → 383203) ; 0 s'il n'y en a pas. */
const numericId = (gid: string) => Number(/(\d+)$/.exec(gid)?.[1] ?? 0);

async function rowOf(project: string, title: string, mrIid: number | null, p: GqlPipeline, auth: Auth): Promise<GitlabPipelineRow> {
  const status = pipelineOf(p.status.toUpperCase());
  const id = numericId(p.id);
  const finished = Date.parse(p.finishedAt ?? "");
  const since = Date.parse(p.finishedAt ?? p.startedAt ?? p.createdAt ?? "");
  return {
    project,
    title,
    mrIid,
    ref: p.ref ?? "",
    id,
    pipeline: status,
    durationSec: status === "running" || !Number.isFinite(finished) ? null : Math.round(p.duration ?? 0),
    ageSec: Number.isFinite(since) ? Math.max(0, Math.round((Date.now() - since) / 1000)) : 0,
    failedJobs: status === "failed" && id > 0 ? await failedJobs(project, id, auth) : [],
    stages: stagesOf(p),
  };
}

// Planifications choisies (par le ⚙ du widget Pipelines), gardées sur l'ordinateur.
export type ScheduleRef = { project: string; id: number };
const schedulesFile = join(config.dataDir, "gitlab-schedules.json");
const emptySchedules = (): { schedules: ScheduleRef[] } => ({ schedules: [] });

export async function selectedSchedules(): Promise<ScheduleRef[]> {
  const { schedules } = await readStoreJson(schedulesFile, emptySchedules);
  return Array.isArray(schedules)
    ? schedules.filter((s): s is ScheduleRef => typeof s?.project === "string" && Number.isInteger(s.id) && s.id > 0)
    : [];
}

export async function selectSchedules(refs: ScheduleRef[]): Promise<void> {
  const seen = new Set<string>();
  const unique = refs.filter((r) => !seen.has(`${r.project}#${r.id}`) && seen.add(`${r.project}#${r.id}`)).slice(0, MAX_SCHEDULES);
  await updateJson(schedulesFile, emptySchedules, (store) => {
    store.schedules = unique;
  });
  // Le choix change les lignes : la prochaine lecture repart de GitLab.
  pipelinesFlight.clear();
}

type ScheduleNode = { id: string; description?: string | null; ref?: string | null; active?: boolean; lastPipeline?: GqlPipeline | null };
const SCHEDULES_QUERY = `query($path: ID!) { project(fullPath: $path) { pipelineSchedules(first: 50) { nodes { id description ref active
  lastPipeline { ${PIPELINE_FIELDS} } } } } }`;

/** Planifications d'un projet ; liste vide si le projet est introuvable ou sa CI interdite au compte. */
async function projectSchedules(project: string, auth: Auth): Promise<ScheduleNode[]> {
  const res = await call("/api/graphql", "POST", { query: SCHEDULES_QUERY, variables: { path: project } }, auth);
  await refuseIfDenied(res, auth);
  if (res.status === 403) return [];
  if (!res.ok) throw UNREACHABLE;
  let data: { errors?: unknown; data?: { project?: { pipelineSchedules?: { nodes: ScheduleNode[] } | null } | null } };
  try {
    data = await res.json();
  } catch {
    throw UNREACHABLE;
  }
  if (data.errors) {
    // Un projet qui répond mal n'empêche pas les autres.
    logFailure("Planifications d'un projet GitLab", data.errors);
    return [];
  }
  return data.data?.project?.pipelineSchedules?.nodes ?? [];
}

/** Un projet qui répond mal (5xx, réseau, réponse illisible) n'empêche pas les autres : il n'a pas de ligne, l'échec est journalisé. */
async function safeProjectSchedules(project: string, auth: Auth): Promise<ScheduleNode[]> {
  try {
    return await projectSchedules(project, auth);
  } catch (e) {
    if (e === TOKEN_REFUSED) throw e;
    logFailure("Planifications d'un projet GitLab", e);
    return [];
  }
}

/** Dernier run de chaque planification choisie ; sans run (jamais lancée), pas de ligne. */
async function readScheduleRows(auth: Auth, selected: ScheduleRef[]): Promise<GitlabPipelineRow[]> {
  const byProject = new Map<string, Set<number>>();
  for (const s of selected) byProject.set(s.project, (byProject.get(s.project) ?? new Set()).add(s.id));
  const rows = await Promise.all(
    [...byProject].map(async ([project, ids]) => {
      const nodes = (await safeProjectSchedules(project, auth)).filter((n) => ids.has(numericId(n.id)));
      return Promise.all(
        nodes.flatMap((n) => (n.lastPipeline ? [rowOf(project, n.description || n.ref || "", null, n.lastPipeline, auth)] : [])),
      );
    }),
  );
  return sortPipelines(rows.flat());
}

/** Pipelines de mes MR ouvertes (celles qui me sont assignées), les MR les plus récentes d'abord. */
async function readMrRows(auth: Auth, list: MrPipeline[]): Promise<GitlabPipelineRow[]> {
  const newest = newestFirst([...list]).slice(0, MAX_MR_PIPELINES);
  return sortPipelines(await Promise.all(newest.map((m) => rowOf(m.project, m.title, m.iid, m.pipeline, auth))));
}

type PipelinesRead = { schedules: GitlabPipelineRow[]; mrs: GitlabPipelineRow[] };

/** Les pipelines qui comptent pour l'utilisateur. Même cache (20 s) et mêmes erreurs que les MR ; pas connecté : réponse normale. */
export async function listPipelines(): Promise<GitlabPipelinesAnswer> {
  const { read: auth } = await authHeaders();
  if (!auth) return { connected: false, expired: !!(await gitlabAccount())?.expired, schedules: [], mrs: [] };
  const value = await pipelinesFlight.get(async () => {
    const [lists, selected] = await Promise.all([read(auth), selectedSchedules()]);
    const [schedules, mrs] = await Promise.all([readScheduleRows(auth, selected), readMrRows(auth, lists.mrPipelines)]);
    return { schedules, mrs };
  });
  return { connected: true, ...value };
}

// --- Choix des planifications : celles des projets dont le compte est membre.

export type ScheduleChoice = { id: number; description: string; ref: string; active: boolean };
export type ScheduleProject = { project: string; schedules: ScheduleChoice[] };
export type ScheduleChoicesAnswer =
  | { connected: true; projects: ScheduleProject[]; selected: ScheduleRef[] }
  | { connected: false; expired: boolean; projects: []; selected: [] };

const PROJECT_PAGES = 3;
const PROJECTS_PER_PAGE = 100;
const SCHEDULE_READS_AT_ONCE = 8;

/** `failed` : projets dont la lecture a échoué (la liste est alors incomplète). */
async function readScheduleProjects(auth: Auth): Promise<{ projects: ScheduleProject[]; failed: number }> {
  const paths: string[] = [];
  for (let page = 1; page <= PROJECT_PAGES; page++) {
    const projects = await readJson<{ path_with_namespace: string }[]>(
      `/api/v4/projects?membership=true&archived=false&simple=true&order_by=last_activity_at&per_page=${PROJECTS_PER_PAGE}&page=${page}`,
      auth,
    );
    paths.push(...(projects ?? []).map((p) => p.path_with_namespace));
    if ((projects?.length ?? 0) < PROJECTS_PER_PAGE) break;
  }
  const found: ScheduleProject[] = [];
  let failed = 0;
  let next = 0;
  // Quelques lectures à la fois : un compte membre de centaines de projets ne doit pas inonder GitLab.
  const worker = async () => {
    while (next < paths.length) {
      const project = paths[next++];
      try {
        const list = await readJson<{ id: number; description?: string | null; ref?: string | null; active?: boolean }[]>(
          `/api/v4/projects/${encodeURIComponent(project)}/pipeline_schedules`,
          auth,
        );
        const schedules = (list ?? []).map((s) => ({
          id: s.id,
          description: s.description ?? "",
          ref: (s.ref ?? "").replace(/^refs\/heads\//, ""),
          active: s.active === true,
        }));
        if (schedules.length > 0) found.push({ project, schedules });
      } catch (e) {
        if (e === TOKEN_REFUSED) throw e;
        // Un projet qui ne répond pas n'empêche pas de choisir dans les autres.
        failed++;
        logFailure("Planifications d'un projet GitLab", e);
      }
    }
  };
  await Promise.all(Array.from({ length: SCHEDULE_READS_AT_ONCE }, worker));
  return { projects: found.sort((a, b) => a.project.localeCompare(b.project)), failed };
}

/** Planifications de pipelines des projets dont le compte est membre (300 projets les plus actifs au plus), gardées 5 min ; avec le choix en cours. */
export async function listScheduleChoices(): Promise<ScheduleChoicesAnswer> {
  const { read: auth } = await authHeaders();
  if (!auth) return { connected: false, expired: !!(await gitlabAccount())?.expired, projects: [], selected: [] };
  const projects = await choicesFlight.get(async () => {
    const read = await readScheduleProjects(auth);
    // Une liste où des projets ont échoué n'est pas gardée : la prochaine ouverture relit.
    if (read.failed > 0) choicesFlight.clear();
    return read.projects;
  });
  return { connected: true, projects: projects, selected: await selectedSchedules() };
}
