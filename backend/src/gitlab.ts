import { forgetGitlabAccessToken, gitlabAccessToken, gitlabAccount, gitlabBaseUrl, gitlabClientId } from "./gitlab-auth.ts";
import { logFailure } from "./log.ts";

/**
 * MR GitLab de l'utilisateur connecté : les siennes et celles à relire, avec pipeline, approbations et état de fusion ;
 * fusion d'une MR prête. Optionnel. Accès : le compte connecté par OAuth (src/gitlab-auth.ts, prioritaire), sinon
 * GITLAB_TOKEN dans .env (jeton personnel, secours) ; portée `api`, nécessaire à la fusion. GITLAB_URL : défaut
 * https://gitlab.unowhy.dev. Aucun jeton ne quitte l'ordinateur : ni réponse, ni message d'erreur, ni journal.
 * Config lue à chaque appel (les tests posent l'environnement).
 */

export type Pipeline = "success" | "running" | "failed" | "canceled" | "none";
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
const TIMEOUT_MS = 15_000;

const envToken = () => process.env.GITLAB_TOKEN?.trim() ?? "";

/** L'onglet GitLab du Deck est proposé : jeton .env, compte connecté, ou appli OAuth réglée (la connexion se fait alors depuis la tablette). */
export async function gitlabAvailable(): Promise<boolean> {
  return envToken() !== "" || !!(await gitlabAccount()) || !!(await gitlabClientId());
}

export const mrUrl = (project: string, iid: number) => `${gitlabBaseUrl()}/${project}/-/merge_requests/${iid}`;

// Valeurs de DetailedMergeStatus relevées par introspection sur gitlab.unowhy.dev (18.10.8-ee), le 29/10/2026.
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

const MR_FIELDS = `iid title draft detailedMergeStatus diffHeadSha updatedAt approvalsRequired approvalsLeft
  approvedBy { nodes { name } } author { name } project { fullPath } headPipeline { status }`;
const QUERY = `{ currentUser {
  assignedMergeRequests(state: opened, first: 50) { nodes { ${MR_FIELDS} } }
  reviewRequestedMergeRequests(state: opened, first: 50) { nodes { ${MR_FIELDS} } }
} }`;

type MrNode = {
  iid: string;
  title: string;
  draft?: boolean;
  detailedMergeStatus: string;
  diffHeadSha: string;
  updatedAt: string;
  approvalsRequired?: number | null;
  approvalsLeft?: number | null;
  approvedBy?: { nodes: { name: string }[] } | null;
  author?: { name: string } | null;
  project: { fullPath: string };
  headPipeline?: { status: string } | null;
};
type MrList = { nodes: MrNode[] };

function toMr(n: MrNode): Mr {
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
  };
}
const newestFirst = (list: MrList) => list.nodes.map(toMr).sort((a, b) => Date.parse(b.updatedAt) - Date.parse(a.updatedAt));

const NOT_CONNECTED = new GitLabError(503, "GitLab n'est pas connecté sur l'ordinateur");
const UNREACHABLE = new GitLabError(502, "GitLab ne répond pas");
const TOKEN_REFUSED = new GitLabError(502, "GitLab refuse l'accès de l'ordinateur : reconnecte-le depuis la page du compagnon");

/** En-tête d'accès : le compte connecté prime, sinon GITLAB_TOKEN ; `undefined` si aucun des deux. */
async function authHeader(): Promise<Record<string, string> | undefined> {
  let bearer: string | undefined;
  try {
    bearer = await gitlabAccessToken();
  } catch (e) {
    logFailure("Renouvellement du jeton GitLab", e);
    throw UNREACHABLE;
  }
  if (bearer) return { authorization: `Bearer ${bearer}` };
  return envToken() ? { "PRIVATE-TOKEN": envToken() } : undefined;
}

/** Appel GitLab : tout échec réseau ou délai devient « ne répond pas ». */
async function call(path: string, method: "POST" | "PUT", body: object, auth: Record<string, string>): Promise<Response> {
  try {
    return await fetch(gitlabBaseUrl() + path, {
      method,
      headers: { ...auth, "content-type": "application/json", accept: "application/json" },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(TIMEOUT_MS),
    });
  } catch {
    throw UNREACHABLE;
  }
}

async function readMrs(auth: Record<string, string>): Promise<{ mine: Mr[]; review: Mr[] }> {
  const res = await call("/api/graphql", "POST", { query: QUERY }, auth);
  if (res.status === 401 || res.status === 403) {
    // Jeton d'accès révoqué : le prochain appel en renouvelle un (ou passe le compte à « à reconnecter »).
    forgetGitlabAccessToken();
    throw TOKEN_REFUSED;
  }
  if (!res.ok) throw UNREACHABLE;
  let data: { errors?: unknown; data?: { currentUser?: { assignedMergeRequests: MrList; reviewRequestedMergeRequests: MrList } | null } };
  try {
    data = await res.json();
  } catch {
    throw UNREACHABLE;
  }
  if (data.errors) throw UNREACHABLE;
  const user = data.data?.currentUser;
  if (!user) {
    forgetGitlabAccessToken();
    throw TOKEN_REFUSED;
  }
  return { mine: newestFirst(user.assignedMergeRequests), review: newestFirst(user.reviewRequestedMergeRequests) };
}

let cache: { at: number; value: MrLists } | undefined;
let inflight: Promise<MrLists> | undefined;

export const clearGitlabCache = () => {
  cache = undefined;
  inflight = undefined;
};

export type MrLists = { mine: Mr[]; review: Mr[] };
/** `connected: false` : ni compte connecté ni jeton .env ; `expired` : le compte a été connecté mais GitLab refuse de le renouveler. */
export type MrsAnswer = ({ connected: true } & MrLists) | { connected: false; expired: boolean; mine: []; review: [] };

/** Mes MR et celles à relire. Résultat gardé 20 s, une seule lecture à la fois ; une erreur n'est pas gardée. Pas connecté : réponse normale, GitLab n'est pas appelé. */
export async function listMrs(): Promise<MrsAnswer> {
  const auth = await authHeader();
  if (!auth) return { connected: false, expired: !!(await gitlabAccount())?.expired, mine: [], review: [] };
  return { connected: true, ...(await read(auth)) };
}

function read(auth: Record<string, string>): Promise<MrLists> {
  if (cache && Date.now() - cache.at < CACHE_MS) return Promise.resolve(cache.value);
  if (inflight) return inflight;
  // Une fusion vide `inflight` : la lecture déjà partie ne remplit plus le cache (elle date d'avant la fusion).
  const run: Promise<MrLists> = readMrs(auth)
    .then((value) => {
      if (inflight === run) cache = { at: Date.now(), value };
      return value;
    })
    .finally(() => {
      if (inflight === run) inflight = undefined;
    });
  inflight = run;
  return run;
}

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
  const auth = await authHeader();
  if (!auth) throw NOT_CONNECTED;
  const res = await call(`/api/v4/projects/${encodeURIComponent(project)}/merge_requests/${iid}/merge`, "PUT", { sha }, auth);
  // Fusionnée ou refusée (elle a bougé, n'est plus fusionnable…) : la liste suivante relit GitLab.
  clearGitlabCache();
  if (res.status === 401) forgetGitlabAccessToken();
  if (!res.ok) throw MERGE_ERRORS[res.status] ?? UNREACHABLE;
}
