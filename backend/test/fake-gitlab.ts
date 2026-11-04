import http from "node:http";
import type { AddressInfo } from "node:net";

/**
 * Faux GitLab pour les tests et l'émulateur : `/api/v4/user`, `/api/v4/personal_access_tokens/self` (portées du jeton),
 * `/api/graphql` (mes MR, et les planifications d'un projet), la liste REST des projets et de leurs planifications, la fusion.
 * Un jeton personnel (PRIVATE-TOKEN) déclaré dans `tokens` (avec ses portées) ou `personalToken` (secours .env) ouvre l'API ;
 * le reste : 401.
 */

export const FAKE_SHA = "0123456789abcdef0123456789abcdef01234567";

export const fakeMr = (over: Record<string, unknown> = {}) => ({
  iid: "12",
  title: "fix: un titre",
  draft: false,
  detailedMergeStatus: "MERGEABLE",
  diffHeadSha: FAKE_SHA,
  updatedAt: "2026-10-28T10:00:00Z",
  approved: true,
  approvalsRequired: 1,
  approvalsLeft: 0,
  approvedBy: { nodes: [{ name: "Camille" }] },
  author: { name: "Ahmed" },
  project: { fullPath: "grp/proj" },
  headPipeline: { status: "SUCCESS" },
  commitsWithoutMergeCommits: { nodes: [] },
  discussions: { nodes: [] },
  ...over,
});

/** Stages d'un pipeline comme GraphQL les rend (statut en minuscules). */
export const fakeStages = (...stages: [name: string, status: string][]) => ({
  nodes: stages.map(([name, status]) => ({ name, status })),
});

/** Pipeline tel que GraphQL le rend (`headPipeline` d'une MR, `lastPipeline` d'une planification). */
export const fakePipeline = (over: Record<string, unknown> = {}) => ({
  id: "gid://gitlab/Ci::Pipeline/500",
  status: "SUCCESS",
  ref: "main",
  createdAt: "2026-10-29T09:00:00Z",
  startedAt: "2026-10-29T09:00:10Z",
  finishedAt: "2026-10-29T09:05:00Z",
  duration: 290,
  stages: fakeStages(["build", "success"], ["test", "success"]),
  ...over,
});

/** Planification de pipelines comme GraphQL la rend (`id` global, dernier pipeline). */
export const fakeSchedule = (over: Record<string, unknown> = {}) => ({
  id: "gid://gitlab/Ci::PipelineSchedule/7",
  description: "Nightly",
  ref: "main",
  active: true,
  lastPipeline: fakePipeline(),
  ...over,
});

/** Discussion de review : résolue ou non, ses messages (date, auteur, système ou non). */
export const fakeDiscussion = (resolved: boolean, notes: { at: string; by: string; system?: boolean }[], resolvable = true) => ({
  resolvable,
  resolved,
  notes: { nodes: notes.map((n) => ({ createdAt: n.at, system: n.system ?? false, author: { username: n.by } })) },
});

export type Seen = { method: string; url: string; authorization?: string; privateToken?: string; body: any };

export type FakeGitlab = {
  url: string;
  /** Requêtes reçues, dans l'ordre. */
  seen: Seen[];
  /** Jeton personnel accepté en PRIVATE-TOKEN (secours .env), portée `api`. */
  personalToken: string;
  /** Jetons personnels valides (collés dans la page du compagnon) et leurs portées. */
  tokens: Record<string, string[]>;
  /** Code HTTP de `/personal_access_tokens/self` (200 : normal ; 404 : GitLab d'avant la 15.5, portées inconnues). */
  selfStatus: number;
  /** Code HTTP de la fusion (200 : fusionnée). */
  mergeStatus: number;
  /** Tous les jetons personnels déclarés sont révoqués : l'API répond 401. */
  revokeAll: () => void;
  mrs: { assigned: unknown[]; review: unknown[] };
  /** Planifications de pipelines (nœuds GraphQL, voir `fakeSchedule`), par chemin de projet. */
  schedules: Record<string, unknown[]>;
  /** Projets dont le compte est membre (`GET /projects?membership=true`), dans l'ordre rendu. */
  memberProjects: string[];
  /** Projets dont les planifications sont interdites au compte (GraphQL : 403, REST : 403, comme un rôle sans accès à la CI). */
  deniedProjects: string[];
  /** Projets dont les planifications répondent 500 (GraphQL et REST) : un projet en panne, pas GitLab entier. */
  brokenProjects: string[];
  /** Noms des jobs en échec, par id de pipeline. */
  failedJobs: Record<number, string[]>;
  /** Code HTTP forcé pour les requêtes de planifications, GraphQL et REST (200 : normal). */
  schedulesStatus: number;
  stop: () => Promise<void>;
};

export async function startFakeGitlab(port = 0): Promise<FakeGitlab> {
  const fake: FakeGitlab = {
    url: "",
    seen: [],
    personalToken: "",
    tokens: {},
    selfStatus: 200,
    mergeStatus: 200,
    schedules: {},
    memberProjects: [],
    deniedProjects: [],
    brokenProjects: [],
    failedJobs: {},
    schedulesStatus: 200,
    revokeAll: () => {
      fake.tokens = {};
      fake.personalToken = "";
    },
    mrs: {
      assigned: [fakeMr(), fakeMr({ iid: "3", updatedAt: "2026-10-29T10:00:00Z", detailedMergeStatus: "NOT_APPROVED" })],
      review: [fakeMr({ iid: "99", project: { fullPath: "dev/robot-framework" }, headPipeline: null })],
    },
    stop: () => new Promise((r) => server.close(() => r())),
  };

  const authorized = (req: http.IncomingMessage) => {
    const given = req.headers["private-token"];
    if (typeof given !== "string" || given === "") return false;
    return given === fake.personalToken || Object.hasOwn(fake.tokens, given);
  };

  const server = http.createServer((req, res) => {
    let raw = "";
    req.on("data", (c) => (raw += c));
    req.on("end", () => {
      const url = new URL(req.url ?? "/", "http://fake");
      fake.seen.push({
        method: req.method ?? "",
        url: req.url ?? "",
        authorization: req.headers.authorization,
        privateToken: req.headers["private-token"] as string | undefined,
        body: raw ? JSON.parse(raw) : undefined,
      });
      const json = (status: number, body: unknown) => {
        res.statusCode = status;
        res.setHeader("content-type", "application/json");
        res.end(JSON.stringify(body));
      };

      if (!authorized(req)) return json(401, { message: "401 Unauthorized" });
      if (url.pathname === "/api/v4/user") return json(200, { username: "camille", name: "Camille" });
      if (url.pathname === "/api/v4/personal_access_tokens/self") {
        const given = String(req.headers["private-token"]);
        if (fake.selfStatus !== 200) return json(fake.selfStatus, { message: "refus" });
        return json(200, { scopes: Object.hasOwn(fake.tokens, given) ? fake.tokens[given] : ["api"] });
      }
      if (url.pathname === "/api/graphql") {
        const { query = "", variables } = (req.method === "POST" && raw ? JSON.parse(raw) : {}) as {
          query?: string;
          variables?: { path?: string };
        };
        if (query.includes("pipelineSchedules")) {
          const path = variables?.path ?? "";
          if (fake.schedulesStatus !== 200) return json(fake.schedulesStatus, { message: "refus" });
          if (fake.deniedProjects.includes(path)) return json(403, { message: "403 Forbidden" });
          if (fake.brokenProjects.includes(path)) return json(500, { message: "panne" });
          const nodes = fake.schedules[path];
          return json(200, { data: { project: nodes ? { pipelineSchedules: { nodes } } : null } });
        }
        return json(200, {
          data: {
            currentUser: {
              username: "camille",
              assignedMergeRequests: { nodes: fake.mrs.assigned },
              reviewRequestedMergeRequests: { nodes: fake.mrs.review },
            },
          },
        });
      }
      if (url.pathname === "/api/v4/projects" && req.method === "GET") {
        if (fake.schedulesStatus !== 200) return json(fake.schedulesStatus, { message: "refus" });
        const perPage = Number(url.searchParams.get("per_page") ?? 20);
        const page = Number(url.searchParams.get("page") ?? 1);
        return json(
          200,
          fake.memberProjects.slice((page - 1) * perPage, page * perPage).map((path_with_namespace) => ({ path_with_namespace })),
        );
      }
      const scheduleList = /^\/api\/v4\/projects\/([^/]+)\/pipeline_schedules$/.exec(url.pathname);
      if (scheduleList && req.method === "GET") {
        const path = decodeURIComponent(scheduleList[1]);
        if (fake.schedulesStatus !== 200) return json(fake.schedulesStatus, { message: "refus" });
        if (fake.deniedProjects.includes(path)) return json(403, { message: "403 Forbidden" });
        if (fake.brokenProjects.includes(path)) return json(500, { message: "panne" });
        const nodes = (fake.schedules[path] ?? []) as { id: string; description: string; ref: string; active: boolean }[];
        // Comme le vrai : id numérique, branche en `refs/heads/…`, pas de dernier pipeline.
        return json(
          200,
          nodes.map((n) => ({
            id: Number(/(\d+)$/.exec(n.id)?.[1]),
            description: n.description,
            ref: `refs/heads/${n.ref}`,
            active: n.active,
          })),
        );
      }
      const jobs = /^\/api\/v4\/projects\/[^/]+\/pipelines\/(\d+)\/jobs$/.exec(url.pathname);
      if (jobs && req.method === "GET")
        return json(
          200,
          (fake.failedJobs[Number(jobs[1])] ?? []).map((name) => ({ name, status: "failed" })),
        );
      if (/^\/api\/v4\/projects\/[^/]+\/merge_requests\/\d+\/merge$/.test(url.pathname) && req.method === "PUT") {
        return json(fake.mergeStatus, fake.mergeStatus === 200 ? { state: "merged" } : { message: "refus" });
      }
      json(404, { message: "404 Not Found" });
    });
  });
  await new Promise<void>((r) => server.listen(port, "127.0.0.1", r));
  fake.url = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  return fake;
}
