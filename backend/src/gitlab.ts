/**
 * MR GitLab de l'utilisateur du jeton : les siennes et celles à relire, avec pipeline, approbations et état de fusion ;
 * fusion d'une MR prête. Optionnel : actif seulement si GITLAB_TOKEN est défini dans .env (portée `api`, nécessaire à
 * la fusion). GITLAB_URL : défaut https://gitlab.unowhy.dev. Le jeton ne quitte jamais l'ordinateur : ni réponse, ni
 * message d'erreur, ni journal. Config lue à chaque appel (les tests posent l'environnement).
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

const DEFAULT_URL = "https://gitlab.unowhy.dev";
const CACHE_MS = 20_000;
const TIMEOUT_MS = 15_000;

const token = () => process.env.GITLAB_TOKEN?.trim() ?? "";
const baseUrl = () => (process.env.GITLAB_URL?.trim() || DEFAULT_URL).replace(/\/+$/, "");

export const gitlabConfigured = () => token() !== "";

export const mrUrl = (project: string, iid: number) => `${baseUrl()}/${project}/-/merge_requests/${iid}`;

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

const NOT_SET = new GitLabError(503, "GitLab n'est pas réglé sur l'ordinateur (GITLAB_TOKEN dans .env)");
const UNREACHABLE = new GitLabError(502, "GitLab ne répond pas");
const TOKEN_REFUSED = new GitLabError(502, "Le jeton GitLab de l'ordinateur est refusé");

/** Appel GitLab : tout échec réseau ou délai devient « ne répond pas ». */
async function call(path: string, method: "POST" | "PUT", body: object): Promise<Response> {
  try {
    return await fetch(baseUrl() + path, {
      method,
      headers: { "PRIVATE-TOKEN": token(), "content-type": "application/json", accept: "application/json" },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(TIMEOUT_MS),
    });
  } catch {
    throw UNREACHABLE;
  }
}

async function readMrs(): Promise<{ mine: Mr[]; review: Mr[] }> {
  const res = await call("/api/graphql", "POST", { query: QUERY });
  if (res.status === 401 || res.status === 403) throw TOKEN_REFUSED;
  if (!res.ok) throw UNREACHABLE;
  let data: { errors?: unknown; data?: { currentUser?: { assignedMergeRequests: MrList; reviewRequestedMergeRequests: MrList } | null } };
  try {
    data = await res.json();
  } catch {
    throw UNREACHABLE;
  }
  if (data.errors) throw UNREACHABLE;
  const user = data.data?.currentUser;
  if (!user) throw TOKEN_REFUSED;
  return { mine: newestFirst(user.assignedMergeRequests), review: newestFirst(user.reviewRequestedMergeRequests) };
}

let cache: { at: number; value: { mine: Mr[]; review: Mr[] } } | undefined;
let inflight: Promise<{ mine: Mr[]; review: Mr[] }> | undefined;

export const clearGitlabCache = () => {
  cache = undefined;
  inflight = undefined;
};

/** Mes MR et celles à relire. Résultat gardé 20 s, une seule lecture à la fois ; une erreur n'est pas gardée. */
export function listMrs(): Promise<{ mine: Mr[]; review: Mr[] }> {
  if (!gitlabConfigured()) return Promise.reject(NOT_SET);
  if (cache && Date.now() - cache.at < CACHE_MS) return Promise.resolve(cache.value);
  if (inflight) return inflight;
  // Une fusion vide `inflight` : la lecture déjà partie ne remplit plus le cache (elle date d'avant la fusion).
  const run: Promise<{ mine: Mr[]; review: Mr[] }> = readMrs()
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
  if (!gitlabConfigured()) throw NOT_SET;
  const res = await call(`/api/v4/projects/${encodeURIComponent(project)}/merge_requests/${iid}/merge`, "PUT", { sha });
  // Fusionnée ou refusée (elle a bougé, n'est plus fusionnable…) : la liste suivante relit GitLab.
  clearGitlabCache();
  if (!res.ok) throw MERGE_ERRORS[res.status] ?? UNREACHABLE;
}
