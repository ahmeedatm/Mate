import { sortPipelines, type MrsAnswer, type Pipeline, type PipelineRow, type PipelinesAnswer, type Mr } from "./gitlab.ts";
import {
  expireGithubToken,
  githubAccessToken,
  githubAccount,
  githubAccountCanWrite,
  githubApiUrl,
  githubClientId,
  githubWebUrl,
} from "./github-auth.ts";
import { logFailure } from "./log.ts";

/**
 * Pull requests GitHub de l'utilisateur connecté (les siennes : écrites ou assignées, et celles à relire), avec contrôles,
 * approbations et état de fusion ; fusion d'une PR prête ; dernier run GitHub Actions de la branche par défaut des dépôts de
 * ces PR. Calqué sur src/gitlab.ts, mêmes formes de réponse (la tablette partage ses vues) : `project` = `propriétaire/dépôt`,
 * `iid` = numéro de la PR, pipeline = contrôles du dernier commit. Accès : le compte connecté (src/github-auth.ts,
 * prioritaire), sinon GITHUB_TOKEN dans .env (jeton personnel, secours). Fusionner demande `repo` (ou `public_repo`) : le
 * compte connecté s'il l'a reçue, sinon GITHUB_TOKEN, sinon refusé. Aucun jeton ne quitte l'ordinateur.
 */

/** Message en français montré sur la tablette : jamais le jeton ni le corps de la réponse de GitHub. */
export class GitHubError extends Error {
  constructor(
    readonly statusCode: number,
    message: string,
  ) {
    super(message);
  }
}

const CACHE_MS = 20_000;
const TIMEOUT_MS = 15_000;
const MAX_RUNS = 8;
const MAX_FAILED_JOBS = 3;

const envToken = () => process.env.GITHUB_TOKEN?.trim() ?? "";

/** Widgets GitHub proposés : jeton .env, compte connecté, ou appli OAuth réglée (la connexion se fait alors depuis la tablette). */
export async function githubAvailable(): Promise<boolean> {
  return envToken() !== "" || !!(await githubAccount()) || !!(await githubClientId());
}

export const prUrl = (repo: string, n: number) => `${githubWebUrl()}/${repo}/pull/${n}`;
export const runUrl = (repo: string, id: number) => `${githubWebUrl()}/${repo}/actions/runs/${id}`;

/** Contrôles du dernier commit (`statusCheckRollup`), rendus comme un pipeline GitLab. */
const CHECKS: Record<string, Pipeline> = {
  SUCCESS: "success",
  FAILURE: "failed",
  ERROR: "failed",
  PENDING: "running",
  EXPECTED: "running",
};
export const checksOf = (state: string | null | undefined): Pipeline => CHECKS[state ?? ""] ?? "none";

/** États de fusion où GitHub accepte de fusionner (UNSTABLE : des contrôles non obligatoires échouent). */
const MERGEABLE = new Set(["CLEAN", "HAS_HOOKS", "UNSTABLE"]);

/** Phrase de l'état de fusion : `mergeStateStatus`, précisé par la relecture quand GitHub dit seulement « bloquée ». */
export function prStatusLabel(mergeState: string, reviewDecision: string | null | undefined, checks: Pipeline): string {
  switch (mergeState) {
    case "CLEAN":
    case "HAS_HOOKS":
      return "Prête à fusionner";
    case "UNSTABLE":
      return "Prête, des contrôles échouent";
    case "DRAFT":
      return "Brouillon";
    case "DIRTY":
      return "Conflit à résoudre";
    case "BEHIND":
      return "Branche en retard sur la base";
    case "UNKNOWN":
      return "Vérification en cours";
    case "BLOCKED":
      if (reviewDecision === "CHANGES_REQUESTED") return "Modifications demandées";
      if (reviewDecision === "REVIEW_REQUIRED") return "Approbation manquante";
      if (checks === "running") return "Contrôles en cours";
      if (checks === "failed") return "Contrôles à faire passer";
      return "Bloquée par les règles de la branche";
    default:
      return "Pas fusionnable pour l'instant";
  }
}

const PR_FIELDS = `fragment Pr on PullRequest {
  number title isDraft updatedAt headRefOid mergeStateStatus reviewDecision
  author { login }
  repository { nameWithOwner defaultBranchRef { name } }
  latestReviews(first: 20) { nodes { state author { login } } }
  commits(last: 1) { nodes { commit { statusCheckRollup { state } } } }
}`;
const search = (alias: string, qualifier: string) =>
  `${alias}: search(query: "is:pr is:open archived:false ${qualifier}", type: ISSUE, first: 50) { nodes { ...Pr } }`;
// « Les miennes » : écrites ou assignées (sur GitHub, on s'assigne rarement sa propre PR).
const QUERY = `query { ${search("authored", "author:@me")} ${search("assigned", "assignee:@me")} ${search("review", "review-requested:@me")} }
${PR_FIELDS}`;

type PrNode = {
  number?: number;
  title: string;
  isDraft?: boolean;
  updatedAt: string;
  headRefOid: string;
  mergeStateStatus?: string | null;
  reviewDecision?: string | null;
  author?: { login: string } | null;
  repository: { nameWithOwner: string; defaultBranchRef?: { name: string } | null };
  latestReviews?: { nodes: { state: string; author?: { login: string } | null }[] } | null;
  commits?: { nodes: { commit: { statusCheckRollup?: { state: string } | null } }[] } | null;
};
type Search = { nodes: (PrNode | Record<string, never>)[] };

export function toPr(n: PrNode): Mr {
  const checks = checksOf(n.commits?.nodes[0]?.commit.statusCheckRollup?.state);
  const by = (n.latestReviews?.nodes ?? []).filter((r) => r.state === "APPROVED").map((r) => r.author?.login ?? "");
  const mergeState = n.isDraft ? "DRAFT" : (n.mergeStateStatus ?? "UNKNOWN");
  return {
    project: n.repository.nameWithOwner,
    iid: n.number!,
    title: n.title,
    draft: n.isDraft === true,
    author: n.author?.login ?? "",
    pipeline: checks,
    // GitHub ne dit pas combien d'approbations sont exigées : une de plus tant qu'il en manque, sinon celles données.
    approvals: { given: by.length, required: by.length + (n.reviewDecision === "REVIEW_REQUIRED" ? 1 : 0), by },
    status: mergeState.toLowerCase(),
    statusLabel: prStatusLabel(mergeState, n.reviewDecision, checks),
    mergeable: MERGEABLE.has(mergeState),
    sha: n.headRefOid,
    updatedAt: n.updatedAt,
  };
}

const NOT_CONNECTED = new GitHubError(503, "GitHub n'est pas connecté sur l'ordinateur");
const UNREACHABLE = new GitHubError(502, "GitHub ne répond pas");
const TOKEN_REFUSED = new GitHubError(502, "GitHub refuse l'accès de l'ordinateur : reconnecte-le depuis la page du compagnon");
const READ_ONLY = new GitHubError(409, "La connexion GitHub est en lecture seule : fusionne sur l'ordinateur");

/** Jeton choisi et d'où il vient : un refus du compte le passe à « à reconnecter », pas celui du .env. */
type Auth = { token: string; account: boolean };

async function auth(): Promise<{ read?: Auth; write?: Auth }> {
  const bearer = await githubAccessToken();
  const account = bearer ? { token: bearer, account: true } : undefined;
  const personal = envToken() ? { token: envToken(), account: false } : undefined;
  return { read: account ?? personal, write: account && (await githubAccountCanWrite()) ? account : personal };
}

async function call(path: string, method: "GET" | "POST" | "PUT", body: object | undefined, a: Auth): Promise<Response> {
  try {
    return await fetch(githubApiUrl() + path, {
      method,
      headers: {
        authorization: `Bearer ${a.token}`,
        accept: "application/vnd.github+json",
        "x-github-api-version": "2022-11-28",
        "user-agent": "UnoMate",
        "content-type": "application/json",
      },
      body: body && JSON.stringify(body),
      signal: AbortSignal.timeout(TIMEOUT_MS),
    });
  } catch {
    throw UNREACHABLE;
  }
}

/** Jeton refusé : le compte passe à « à reconnecter » ; la tablette ne reçoit jamais de 401. */
async function refuseIfDenied(res: Response, a: Auth) {
  if (res.status !== 401) return;
  if (a.account) await expireGithubToken(a.token);
  throw TOKEN_REFUSED;
}

type PrLists = { mine: Mr[]; review: Mr[]; branches: Record<string, string> };

async function readPrs(a: Auth): Promise<PrLists> {
  const res = await call("/graphql", "POST", { query: QUERY }, a);
  await refuseIfDenied(res, a);
  if (!res.ok) throw UNREACHABLE;
  let data: { errors?: unknown; data?: { authored: Search; assigned: Search; review: Search } | null };
  try {
    data = await res.json();
  } catch {
    throw UNREACHABLE;
  }
  if (data.errors || !data.data) throw UNREACHABLE;
  const branches: Record<string, string> = {};
  const prs = (s: Search) =>
    // Les résultats de recherche peuvent être autre chose qu'une PR (objet vide dans le fragment) : ignorés.
    s.nodes.flatMap((n) => {
      if (!("number" in n) || typeof n.number !== "number") return [];
      const branch = n.repository.defaultBranchRef?.name;
      if (branch) branches[n.repository.nameWithOwner] = branch;
      return [toPr(n as PrNode)];
    });
  const newest = (list: Mr[]) => list.sort((x, y) => Date.parse(y.updatedAt) - Date.parse(x.updatedAt));
  const mine = new Map<string, Mr>();
  for (const pr of [...prs(data.data.authored), ...prs(data.data.assigned)]) mine.set(`${pr.project}#${pr.iid}`, pr);
  return { mine: newest([...mine.values()]), review: newest(prs(data.data.review)), branches };
}

let cache: { at: number; value: PrLists } | undefined;
let inflight: Promise<PrLists> | undefined;
let runsCache: { at: number; value: PipelineRow[] } | undefined;
let runsInflight: Promise<PipelineRow[]> | undefined;

export const clearGithubCache = () => {
  cache = undefined;
  inflight = undefined;
  runsCache = undefined;
  runsInflight = undefined;
};

function read(a: Auth): Promise<PrLists> {
  if (cache && Date.now() - cache.at < CACHE_MS) return Promise.resolve(cache.value);
  if (inflight) return inflight;
  const run: Promise<PrLists> = readPrs(a)
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

/** Mes PR et celles à relire. Gardées 20 s, une lecture à la fois ; pas connecté : réponse normale, GitHub n'est pas appelé. */
export async function listPrs(): Promise<MrsAnswer> {
  const { read: a, write } = await auth();
  if (!a) return { connected: false, expired: !!(await githubAccount())?.expired, canMerge: false, mine: [], review: [] };
  const { mine, review } = await read(a);
  return { connected: true, canMerge: !!write, mine, review };
}

const MERGE_ERRORS: Record<number, GitHubError> = {
  405: new GitHubError(409, "Cette PR ne peut pas être fusionnée pour l'instant"),
  409: new GitHubError(409, "La PR a changé depuis l'affichage : relis la liste"),
  401: new GitHubError(502, "Le jeton GitHub n'a pas le droit de fusionner"),
  403: new GitHubError(502, "Le jeton GitHub n'a pas le droit de fusionner"),
  404: new GitHubError(404, "PR introuvable"),
  422: new GitHubError(409, "La branche ne peut pas être fusionnée"),
};

/** Fusionne la PR à condition que sa tête soit encore `sha` (GitHub refuse en 409 sinon). */
export async function mergePr(repo: string, n: number, sha: string): Promise<void> {
  const { read: connected, write: a } = await auth();
  if (!a) throw connected ? READ_ONLY : NOT_CONNECTED;
  const res = await call(`/repos/${repo}/pulls/${n}/merge`, "PUT", { sha }, a);
  clearGithubCache();
  if (res.status === 401 && a.account) await expireGithubToken(a.token);
  if (!res.ok) throw MERGE_ERRORS[res.status] ?? UNREACHABLE;
}

// --- GitHub Actions : le dernier run de la branche par défaut de chaque dépôt de mes PR.

type RunJson = {
  id: number;
  head_branch: string;
  status: string;
  conclusion?: string | null;
  created_at?: string | null;
  run_started_at?: string | null;
  updated_at?: string | null;
};

const CONCLUSIONS: Record<string, Pipeline> = {
  success: "success",
  neutral: "success",
  failure: "failed",
  timed_out: "failed",
  startup_failure: "failed",
  cancelled: "canceled",
  action_required: "running",
};
/** Run pas terminé (en file, en cours, en attente) : en cours ; terminé : sa conclusion. */
export const runStatusOf = (status: string, conclusion: string | null | undefined): Pipeline =>
  status !== "completed" ? "running" : (CONCLUSIONS[conclusion ?? ""] ?? "none");

async function failedJobs(repo: string, id: number, a: Auth): Promise<string[]> {
  try {
    const res = await call(`/repos/${repo}/actions/runs/${id}/jobs?filter=latest&per_page=50`, "GET", undefined, a);
    if (!res.ok) return [];
    const { jobs } = (await res.json()) as { jobs?: { name?: string; conclusion?: string | null }[] };
    return (jobs ?? []).flatMap((j) => (j.conclusion === "failure" && j.name ? [j.name] : [])).slice(0, MAX_FAILED_JOBS);
  } catch (e) {
    logFailure("Jobs en échec d'un run GitHub", e);
    return [];
  }
}

/** Dernier run de la branche ; null si le dépôt n'en a aucun (ou pas d'Actions : 404). */
async function latestRun(repo: string, branch: string, a: Auth): Promise<PipelineRow | null> {
  const res = await call(
    `/repos/${repo}/actions/runs?branch=${encodeURIComponent(branch)}&per_page=1&exclude_pull_requests=true`,
    "GET",
    undefined,
    a,
  );
  await refuseIfDenied(res, a);
  if (res.status === 404 || res.status === 403) return null;
  if (!res.ok) throw UNREACHABLE;
  let run: RunJson | undefined;
  try {
    run = ((await res.json()) as { workflow_runs?: RunJson[] }).workflow_runs?.[0];
  } catch {
    throw UNREACHABLE;
  }
  if (!run) return null;
  const status = runStatusOf(run.status, run.conclusion);
  const started = Date.parse(run.run_started_at ?? run.created_at ?? "");
  const updated = Date.parse(run.updated_at ?? "");
  const done = run.status === "completed";
  const since = done ? updated : started;
  return {
    project: repo,
    ref: run.head_branch,
    id: run.id,
    pipeline: status,
    durationSec: done && Number.isFinite(started) && Number.isFinite(updated) ? Math.max(0, Math.round((updated - started) / 1000)) : null,
    ageSec: Number.isFinite(since) ? Math.max(0, Math.round((Date.now() - since) / 1000)) : 0,
    failedJobs: status === "failed" ? await failedJobs(repo, run.id, a) : [],
  };
}

async function readRuns(a: Auth, lists: PrLists): Promise<PipelineRow[]> {
  const newest = [...lists.mine, ...lists.review].sort((x, y) => Date.parse(y.updatedAt) - Date.parse(x.updatedAt));
  const repos = [...new Set(newest.map((p) => p.project))].filter((r) => lists.branches[r]).slice(0, MAX_RUNS);
  const rows = await Promise.all(repos.map((r) => latestRun(r, lists.branches[r], a)));
  return sortPipelines(rows.filter((r): r is PipelineRow => r !== null));
}

/** Les runs qui comptent pour l'utilisateur. Même cache (20 s) et mêmes erreurs que les PR ; pas connecté : réponse normale. */
export async function listRuns(): Promise<PipelinesAnswer> {
  const { read: a } = await auth();
  if (!a) return { connected: false, expired: !!(await githubAccount())?.expired, pipelines: [] };
  if (runsCache && Date.now() - runsCache.at < CACHE_MS) return { connected: true, pipelines: runsCache.value };
  if (!runsInflight) {
    const run: Promise<PipelineRow[]> = read(a)
      .then((lists) => readRuns(a, lists))
      .then((value) => {
        if (runsInflight === run) runsCache = { at: Date.now(), value };
        return value;
      })
      .finally(() => {
        if (runsInflight === run) runsInflight = undefined;
      });
    runsInflight = run;
  }
  return { connected: true, pipelines: await runsInflight };
}
