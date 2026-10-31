import { randomBytes } from "node:crypto";
import http from "node:http";
import type { AddressInfo } from "node:net";

/**
 * Faux GitHub pour les tests : connexion par device flow (`/login/device/code`, `/login/oauth/access_token` qui répond
 * `authorization_pending` puis le jeton une fois `approve` appelé, ou `access_denied` après `deny`), `/user`, `/graphql`
 * (les trois recherches de src/github.ts), la fusion et GitHub Actions (runs, jobs). Un jeton émis ou `personalToken`
 * (Bearer) ouvre l'API ; le reste : 401. Une seule adresse pour le site et l'API (GITHUB_URL).
 */

export const FAKE_GITHUB_CLIENT_ID = "Iv1.app-de-test";
export const FAKE_SHA = "0123456789abcdef0123456789abcdef01234567";

export const fakePr = (over: Record<string, unknown> = {}) => ({
  number: 12,
  title: "fix: un titre",
  isDraft: false,
  updatedAt: "2026-10-28T10:00:00Z",
  headRefOid: FAKE_SHA,
  mergeStateStatus: "CLEAN",
  reviewDecision: "APPROVED",
  author: { login: "ahmed" },
  repository: { nameWithOwner: "org/app", defaultBranchRef: { name: "main" } },
  latestReviews: { nodes: [{ state: "APPROVED", author: { login: "camille" } }] },
  commits: { nodes: [{ commit: { statusCheckRollup: { state: "SUCCESS" } } }] },
  ...over,
});

/** Run tel que le rend `GET /repos/:o/:r/actions/runs`. */
export const fakeRun = (over: Record<string, unknown> = {}) => ({
  id: 12_345_678_901,
  name: "CI",
  head_branch: "main",
  status: "completed",
  conclusion: "success",
  created_at: "2026-10-29T09:00:00Z",
  run_started_at: "2026-10-29T09:00:10Z",
  updated_at: "2026-10-29T09:05:00Z",
  ...over,
});

export type GithubSeen = { method: string; url: string; authorization?: string; body: any };

export type FakeGithub = {
  url: string;
  seen: GithubSeen[];
  /** Jetons émis : pour vérifier qu'ils ne fuient pas. */
  issued: string[];
  /** Portée annoncée avec le jeton (GitHub : séparée par des virgules). */
  grantScope: string;
  personalToken: string;
  /** Le prochain sondage rend le jeton (ou refuse) ; avant : `authorization_pending`. */
  approve: () => void;
  deny: () => void;
  /** Code à saisir de la dernière demande. */
  userCode: string;
  revokeAll: () => void;
  mergeStatus: number;
  prs: { authored: unknown[]; assigned: unknown[]; review: unknown[] };
  /** Dernier run de la branche par défaut, par dépôt (absent : aucun run). */
  runs: Record<string, unknown>;
  /** Jobs du run, par id. */
  jobs: Record<number, { name: string; conclusion: string }[]>;
  /** Prochains sondages du jeton qui échouent (502, corps HTML) : GitHub en panne passagère. */
  tokenFailures: number;
  /** Délai de `GET /user` (ms) : laisse le temps de se déconnecter pendant la fin de la connexion. */
  userDelayMs: number;
  /** Réponse GraphQL partielle : `errors` (FORBIDDEN) en plus des données, et un nœud null dans `review`. */
  graphqlPartial: boolean;
  /** Réponse forcée des runs, par dépôt (limite de débit, refus SSO…). */
  runsAnswer: Record<string, { status: number; headers?: Record<string, string>; body: unknown }>;
  stop: () => Promise<void>;
};

export async function startFakeGithub(): Promise<FakeGithub> {
  const tokens = new Set<string>();
  let decision: "pending" | "ok" | "denied" = "pending";
  let deviceCode = "";
  const fake: FakeGithub = {
    url: "",
    seen: [],
    issued: [],
    grantScope: "repo",
    personalToken: "",
    approve: () => {
      decision = "ok";
    },
    deny: () => {
      decision = "denied";
    },
    userCode: "",
    revokeAll: () => tokens.clear(),
    mergeStatus: 200,
    prs: {
      authored: [fakePr()],
      assigned: [
        fakePr(),
        fakePr({
          number: 3,
          updatedAt: "2026-10-29T10:00:00Z",
          mergeStateStatus: "BLOCKED",
          reviewDecision: "REVIEW_REQUIRED",
          latestReviews: { nodes: [] },
        }),
      ],
      review: [
        fakePr({ number: 99, repository: { nameWithOwner: "org/lib", defaultBranchRef: { name: "trunk" } }, commits: { nodes: [] } }),
        {},
      ],
    },
    runs: {},
    jobs: {},
    tokenFailures: 0,
    userDelayMs: 0,
    graphqlPartial: false,
    runsAnswer: {},
    stop: () => new Promise((r) => server.close(() => r())),
  };

  const server = http.createServer((req, res) => {
    let raw = "";
    req.on("data", (c) => (raw += c));
    req.on("end", () => {
      const url = new URL(req.url ?? "/", "http://fake");
      const isForm = String(req.headers["content-type"] ?? "").includes("x-www-form-urlencoded");
      fake.seen.push({
        method: req.method ?? "",
        url: req.url ?? "",
        authorization: req.headers.authorization,
        body: raw ? (isForm ? Object.fromEntries(new URLSearchParams(raw)) : JSON.parse(raw)) : undefined,
      });
      const json = (status: number, body: unknown) => {
        res.statusCode = status;
        res.setHeader("content-type", "application/json");
        res.end(JSON.stringify(body));
      };
      const form = new URLSearchParams(raw);

      if (url.pathname === "/login/device/code" && req.method === "POST") {
        if (form.get("client_id") !== FAKE_GITHUB_CLIENT_ID) return json(200, { error: "unauthorized_client" });
        decision = "pending";
        deviceCode = `dc-${randomBytes(8).toString("hex")}`;
        fake.userCode = `${randomBytes(2).toString("hex").toUpperCase()}-${randomBytes(2).toString("hex").toUpperCase()}`;
        return json(200, {
          device_code: deviceCode,
          user_code: fake.userCode,
          verification_uri: `${fake.url}/login/device`,
          expires_in: 900,
          interval: 0,
        });
      }
      if (url.pathname === "/login/oauth/access_token" && req.method === "POST") {
        if (form.get("client_id") !== FAKE_GITHUB_CLIENT_ID || form.has("client_secret"))
          return json(200, { error: "incorrect_client_credentials" });
        if (form.get("device_code") !== deviceCode) return json(200, { error: "bad_verification_code" });
        if (fake.tokenFailures > 0) {
          fake.tokenFailures--;
          res.statusCode = 502;
          res.setHeader("content-type", "text/html");
          return res.end("<html>Bad gateway</html>");
        }
        if (decision === "pending") return json(200, { error: "authorization_pending" });
        if (decision === "denied") return json(200, { error: "access_denied" });
        deviceCode = "";
        const token = `gho_${randomBytes(12).toString("hex")}`;
        tokens.add(token);
        fake.issued.push(token);
        return json(200, { access_token: token, token_type: "bearer", scope: fake.grantScope });
      }
      const bearer = /^Bearer (.+)$/.exec(String(req.headers.authorization ?? ""))?.[1] ?? "";
      if (!tokens.has(bearer) && !(fake.personalToken && bearer === fake.personalToken)) return json(401, { message: "Bad credentials" });
      if (url.pathname === "/user") {
        setTimeout(() => json(200, { login: "camille" }), fake.userDelayMs);
        return;
      }
      if (url.pathname === "/graphql" && req.method === "POST") {
        const nodes = (list: unknown[]) => ({ nodes: list });
        const review = fake.graphqlPartial ? [...fake.prs.review, null] : fake.prs.review;
        return json(200, {
          data: { authored: nodes(fake.prs.authored), assigned: nodes(fake.prs.assigned), review: nodes(review) },
          ...(fake.graphqlPartial && {
            errors: [
              {
                type: "FORBIDDEN",
                message:
                  "Although you appear to have the correct authorization credentials, the organization has enabled OAuth App access restrictions",
              },
            ],
          }),
        });
      }
      if (/^\/repos\/[^/]+\/[^/]+\/pulls\/\d+\/merge$/.test(url.pathname) && req.method === "PUT") {
        return json(fake.mergeStatus, fake.mergeStatus === 200 ? { merged: true } : { message: "refus" });
      }
      const runs = /^\/repos\/([^/]+\/[^/]+)\/actions\/runs$/.exec(url.pathname);
      if (runs && req.method === "GET") {
        const forced = fake.runsAnswer[runs[1]];
        if (forced) {
          for (const [k, v] of Object.entries(forced.headers ?? {})) res.setHeader(k, v);
          return json(forced.status, forced.body);
        }
        const run = fake.runs[runs[1]];
        return json(200, { total_count: run ? 1 : 0, workflow_runs: run ? [run] : [] });
      }
      const jobs = /^\/repos\/[^/]+\/[^/]+\/actions\/runs\/(\d+)\/jobs$/.exec(url.pathname);
      if (jobs && req.method === "GET") return json(200, { jobs: fake.jobs[Number(jobs[1])] ?? [] });
      json(404, { message: "Not Found" });
    });
  });
  await new Promise<void>((r) => server.listen(0, "127.0.0.1", r));
  fake.url = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  return fake;
}
