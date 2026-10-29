import assert from "node:assert/strict";
import { afterEach, beforeEach, describe, it, mock } from "node:test";
import { clearGitlabCache, gitlabConfigured, GitLabError, listMrs, mergeMr, mrUrl, pipelineOf, statusLabel } from "../src/gitlab.ts";

const TOKEN = "glpat-secret-de-test";
const SHA = "0123456789abcdef0123456789abcdef01234567";

const node = (over: Record<string, unknown> = {}) => ({
  iid: "12",
  title: "fix: un titre",
  draft: false,
  webUrl: "https://gitlab.test/a/b/-/merge_requests/12",
  detailedMergeStatus: "NOT_APPROVED",
  diffHeadSha: SHA,
  updatedAt: "2026-10-28T10:00:00Z",
  approvalsRequired: 2,
  approvalsLeft: 1,
  approvedBy: { nodes: [{ name: "Camille" }] },
  author: { name: "Ahmed" },
  project: { fullPath: "a/b" },
  headPipeline: { status: "SUCCESS" },
  ...over,
});
const graphql = (assigned: unknown[], review: unknown[]) => ({
  data: { currentUser: { assignedMergeRequests: { nodes: assigned }, reviewRequestedMergeRequests: { nodes: review } } },
});
const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });

type Call = { url: string; init: RequestInit };
let calls: Call[];
/** Remplace `fetch` : `answer` rend la réponse à chaque appel. */
const fakeFetch = (answer: (call: Call) => Response | Promise<Response>) =>
  mock.method(globalThis, "fetch", async (url: string, init: RequestInit) => {
    const call = { url: String(url), init };
    calls.push(call);
    return answer(call);
  });

const env = { ...process.env };
beforeEach(() => {
  calls = [];
  process.env.GITLAB_TOKEN = TOKEN;
  process.env.GITLAB_URL = "https://gitlab.test/";
  clearGitlabCache();
});
afterEach(() => {
  mock.restoreAll();
  mock.timers.reset();
  process.env.GITLAB_TOKEN = env.GITLAB_TOKEN;
  process.env.GITLAB_URL = env.GITLAB_URL;
  if (env.GITLAB_TOKEN === undefined) delete process.env.GITLAB_TOKEN;
  if (env.GITLAB_URL === undefined) delete process.env.GITLAB_URL;
});

const rejectsWith = (p: Promise<unknown>, statusCode: number, message: string) =>
  assert.rejects(p, (e: unknown) => {
    assert.ok(e instanceof GitLabError, `GitLabError attendue, reçu ${String(e)}`);
    assert.equal(e.statusCode, statusCode);
    assert.equal(e.message, message);
    assert.ok(!e.message.includes(TOKEN));
    return true;
  });

describe("gitlab : lecture des MR", () => {
  it("met en forme mine et review, séparées", async () => {
    const mine = node({ iid: "12", title: "Ma MR", draft: true });
    const other = node({
      iid: "7",
      project: { fullPath: "grp/sous/proj" },
      author: { name: "Eugénie" },
      detailedMergeStatus: "MERGEABLE",
      approvalsLeft: 0,
      approvedBy: { nodes: [{ name: "Camille" }, { name: "Ahmed" }] },
      diffHeadSha: "f".repeat(40),
    });
    fakeFetch(() => json(graphql([mine], [other])));
    const { mine: m, review } = await listMrs();
    assert.deepEqual(m, [
      {
        project: "a/b",
        iid: 12,
        title: "Ma MR",
        draft: true,
        author: "Ahmed",
        pipeline: "success",
        approvals: { given: 1, required: 2, by: ["Camille"] },
        status: "not_approved",
        statusLabel: "Approbation manquante",
        mergeable: false,
        sha: SHA,
        updatedAt: "2026-10-28T10:00:00Z",
      },
    ]);
    assert.equal(review.length, 1);
    assert.equal(review[0].project, "grp/sous/proj");
    assert.equal(review[0].iid, 7);
    assert.equal(typeof review[0].iid, "number");
    assert.equal(review[0].mergeable, true);
    assert.equal(review[0].status, "mergeable");
    assert.equal(review[0].statusLabel, "Prête à fusionner");
    assert.deepEqual(review[0].approvals, { given: 2, required: 2, by: ["Camille", "Ahmed"] });
    assert.equal(review[0].sha, "f".repeat(40));
  });

  it("une MR présente dans les deux listes reste dans les deux", async () => {
    fakeFetch(() => json(graphql([node()], [node()])));
    const { mine, review } = await listMrs();
    assert.equal(mine.length, 1);
    assert.equal(review.length, 1);
  });

  it("headPipeline null = none ; champs d'approbation absents = 0", async () => {
    fakeFetch(() => json(graphql([node({ headPipeline: null, approvalsRequired: null, approvalsLeft: null, approvedBy: null })], [])));
    const [mr] = (await listMrs()).mine;
    assert.equal(mr.pipeline, "none");
    assert.deepEqual(mr.approvals, { given: 0, required: 0, by: [] });
  });

  it("approvals.given n'est jamais négatif", async () => {
    fakeFetch(() => json(graphql([node({ approvalsRequired: 1, approvalsLeft: 3 })], [])));
    assert.equal((await listMrs()).mine[0].approvals.given, 0);
  });

  it("mergeable seulement pour MERGEABLE", async () => {
    const statuses = ["MERGEABLE", "CI_STILL_RUNNING", "DRAFT_STATUS", "UNCHECKED"];
    fakeFetch(() =>
      json(
        graphql(
          statuses.map((s, i) => node({ iid: String(i + 1), detailedMergeStatus: s })),
          [],
        ),
      ),
    );
    const got = (await listMrs()).mine.sort((a, b) => a.iid - b.iid).map((m) => m.mergeable);
    assert.deepEqual(got, [true, false, false, false]);
  });

  it("plus récentes en haut", async () => {
    const a = node({ iid: "1", updatedAt: "2026-10-01T00:00:00Z" });
    const b = node({ iid: "2", updatedAt: "2026-10-29T00:00:00Z" });
    const c = node({ iid: "3", updatedAt: "2026-10-15T00:00:00Z" });
    fakeFetch(() => json(graphql([a, b, c], [a, c, b])));
    const { mine, review } = await listMrs();
    assert.deepEqual(
      mine.map((m) => m.iid),
      [2, 3, 1],
    );
    assert.deepEqual(
      review.map((m) => m.iid),
      [2, 3, 1],
    );
  });

  it("listes vides", async () => {
    fakeFetch(() => json(graphql([], [])));
    assert.deepEqual(await listMrs(), { mine: [], review: [] });
  });
});

describe("gitlab : libellés et pipelines", () => {
  // Valeurs de DetailedMergeStatus relevées par introspection sur gitlab.unowhy.dev (18.10.8-ee), le 29/10/2026.
  const known = [
    "UNCHECKED",
    "CHECKING",
    "MERGEABLE",
    "COMMITS_STATUS",
    "CI_MUST_PASS",
    "CI_STILL_RUNNING",
    "DISCUSSIONS_NOT_RESOLVED",
    "DRAFT_STATUS",
    "NOT_OPEN",
    "NOT_APPROVED",
    "BLOCKED_STATUS",
    "EXTERNAL_STATUS_CHECKS",
    "PREPARING",
    "JIRA_ASSOCIATION",
    "CONFLICT",
    "NEED_REBASE",
    "APPROVALS_SYNCING",
    "LOCKED_PATHS",
    "LOCKED_LFS_FILES",
    "MERGE_TIME",
    "SECURITY_POLICIES_VIOLATIONS",
    "TITLE_NOT_MATCHING",
    "REQUESTED_CHANGES",
  ];
  const FALLBACK = "Pas fusionnable pour l'instant";

  it("chaque statut connu a son libellé, en minuscules comme en majuscules", () => {
    for (const s of known) {
      const label = statusLabel(s.toLowerCase());
      assert.notEqual(label, FALLBACK, s);
      assert.equal(statusLabel(s), label, s);
    }
  });

  it("libellés de la spec", () => {
    const expected: Record<string, string> = {
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
      requested_changes: "Modifications demandées",
    };
    for (const [s, label] of Object.entries(expected)) assert.equal(statusLabel(s), label, s);
  });

  it("statut inconnu : repli", () => {
    assert.equal(statusLabel("inventé_demain"), FALLBACK);
    assert.equal(statusLabel(""), FALLBACK);
  });

  it("pipelines", () => {
    const expected: Record<string, string> = {
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
      MANUAL: "none",
      SKIPPED: "none",
      INVENTE: "none",
    };
    for (const [s, p] of Object.entries(expected)) assert.equal(pipelineOf(s), p, s);
    assert.equal(pipelineOf(null), "none");
    assert.equal(pipelineOf(undefined), "none");
  });
});

describe("gitlab : requête GraphQL", () => {
  it("POST sur /api/graphql avec le jeton, state opened, first 50, diffHeadSha", async () => {
    fakeFetch(() => json(graphql([], [])));
    await listMrs();
    assert.equal(calls.length, 1);
    const { url, init } = calls[0];
    assert.equal(url, "https://gitlab.test/api/graphql");
    assert.equal(init.method, "POST");
    const headers = init.headers as Record<string, string>;
    assert.equal(headers["PRIVATE-TOKEN"], TOKEN);
    assert.equal(headers["content-type"], "application/json");
    const { query } = JSON.parse(String(init.body));
    assert.match(query, /assignedMergeRequests\(state: opened, first: 50\)/);
    assert.match(query, /reviewRequestedMergeRequests\(state: opened, first: 50\)/);
    for (const field of ["diffHeadSha", "updatedAt", "detailedMergeStatus", "approvalsLeft", "headPipeline", "fullPath"]) {
      assert.ok(query.includes(field), field);
    }
  });
});

describe("gitlab : cache", () => {
  it("deux lectures à moins de 20 s = un fetch ; à 21 s, un second", async () => {
    mock.timers.enable({ apis: ["Date"], now: 1_000_000 });
    fakeFetch(() => json(graphql([], [])));
    await listMrs();
    mock.timers.tick(19_000);
    await listMrs();
    assert.equal(calls.length, 1);
    mock.timers.tick(2_000);
    await listMrs();
    assert.equal(calls.length, 2);
  });

  it("deux lectures simultanées = un fetch", async () => {
    fakeFetch(async () => {
      await new Promise((r) => setTimeout(r, 20));
      return json(graphql([node()], []));
    });
    const [a, b] = await Promise.all([listMrs(), listMrs()]);
    assert.equal(calls.length, 1);
    assert.deepEqual(a, b);
  });

  it("une erreur n'est pas gardée", async () => {
    let n = 0;
    fakeFetch(() => (n++ === 0 ? json({}, 500) : json(graphql([], []))));
    await assert.rejects(listMrs());
    await listMrs();
    assert.equal(calls.length, 2);
  });

  it("mergeMr vide le cache", async () => {
    fakeFetch((c) => (c.init.method === "PUT" ? json({ state: "merged" }) : json(graphql([], []))));
    await listMrs();
    await mergeMr("a/b", 12, SHA);
    await listMrs();
    assert.equal(calls.filter((c) => c.init.method === "POST").length, 2);
  });

  it("une lecture déjà partie quand la fusion arrive ne remplit pas le cache", async () => {
    let release!: () => void;
    const gate = new Promise<void>((r) => (release = r));
    let reads = 0;
    fakeFetch(async (c) => {
      if (c.init.method === "PUT") return json({});
      reads++;
      if (reads === 1) await gate;
      return json(graphql([], []));
    });
    const stale = listMrs();
    await new Promise((r) => setTimeout(r, 10));
    await mergeMr("a/b", 12, SHA);
    release();
    await stale;
    await listMrs();
    assert.equal(reads, 2, "la lecture d'avant la fusion ne doit pas servir");
  });
});

describe("gitlab : fusion", () => {
  it("PUT sur le projet encodé, corps { sha }, jeton en en-tête", async () => {
    fakeFetch(() => json({ state: "merged" }));
    await mergeMr("grp/sous/proj", 42, SHA);
    const { url, init } = calls[0];
    assert.equal(url, "https://gitlab.test/api/v4/projects/grp%2Fsous%2Fproj/merge_requests/42/merge");
    assert.equal(init.method, "PUT");
    assert.deepEqual(JSON.parse(String(init.body)), { sha: SHA });
    assert.equal((init.headers as Record<string, string>)["PRIVATE-TOKEN"], TOKEN);
  });

  const cases: [string, number, number, string][] = [
    ["405", 405, 409, "Cette MR ne peut pas être fusionnée pour l'instant"],
    ["409 (sha)", 409, 409, "La MR a changé depuis l'affichage : relis la liste"],
    ["401", 401, 502, "Le jeton GitLab n'a pas le droit de fusionner"],
    ["403", 403, 502, "Le jeton GitLab n'a pas le droit de fusionner"],
    ["404", 404, 404, "MR introuvable"],
    ["422", 422, 409, "La branche ne peut pas être fusionnée"],
    ["500", 500, 502, "GitLab ne répond pas"],
  ];
  for (const [name, status, code, message] of cases) {
    it(`refus ${name} → ${code}`, async () => {
      fakeFetch(() => json({ message: `corps de GitLab avec ${TOKEN}` }, status));
      await rejectsWith(mergeMr("a/b", 1, SHA), code, message);
    });
  }

  it("coupure réseau → 502", async () => {
    fakeFetch(() => Promise.reject(new TypeError(`fetch failed ${TOKEN}`)));
    await rejectsWith(mergeMr("a/b", 1, SHA), 502, "GitLab ne répond pas");
  });

  it("un refus vide aussi le cache : la MR a changé, la liste suivante doit la relire", async () => {
    fakeFetch((c) => (c.init.method === "PUT" ? json({}, 409) : json(graphql([], []))));
    await listMrs();
    await assert.rejects(mergeMr("a/b", 1, SHA));
    await listMrs();
    assert.equal(calls.filter((c) => c.init.method === "POST").length, 2);
  });
});

describe("gitlab : erreurs de lecture", () => {
  for (const status of [401, 403]) {
    it(`GitLab ${status} → 502 jeton refusé, sans le jeton`, async () => {
      fakeFetch(() => json({ message: `401 Unauthorized ${TOKEN}` }, status));
      await rejectsWith(listMrs(), 502, "Le jeton GitLab de l'ordinateur est refusé");
    });
  }

  it("utilisateur absent de la réponse (jeton sans compte) → 502 jeton refusé", async () => {
    fakeFetch(() => json({ data: { currentUser: null } }));
    await rejectsWith(listMrs(), 502, "Le jeton GitLab de l'ordinateur est refusé");
  });

  it("HTTP 500 → 502 ne répond pas", async () => {
    fakeFetch(() => json({}, 503));
    await rejectsWith(listMrs(), 502, "GitLab ne répond pas");
  });

  it("errors GraphQL → 502 ne répond pas", async () => {
    fakeFetch(() => json({ errors: [{ message: `Field boom ${TOKEN}` }] }));
    await rejectsWith(listMrs(), 502, "GitLab ne répond pas");
  });

  it("réseau coupé → 502 ne répond pas", async () => {
    fakeFetch(() => Promise.reject(new TypeError("fetch failed")));
    await rejectsWith(listMrs(), 502, "GitLab ne répond pas");
  });

  it("délai dépassé → 502 ne répond pas", async () => {
    fakeFetch(() => Promise.reject(new DOMException("The operation was aborted due to timeout", "TimeoutError")));
    await rejectsWith(listMrs(), 502, "GitLab ne répond pas");
  });

  it("réponse qui n'est pas du JSON → 502 ne répond pas", async () => {
    fakeFetch(() => new Response("<html>proxy</html>", { status: 200 }));
    await rejectsWith(listMrs(), 502, "GitLab ne répond pas");
  });

  it("une demande part avec un délai", async () => {
    fakeFetch(() => json(graphql([], [])));
    await listMrs();
    assert.ok(calls[0].init.signal instanceof AbortSignal);
  });
});

describe("gitlab : jeton absent", () => {
  for (const value of [undefined, "", "   "]) {
    it(`GITLAB_TOKEN = ${JSON.stringify(value)}`, async () => {
      if (value === undefined) delete process.env.GITLAB_TOKEN;
      else process.env.GITLAB_TOKEN = value;
      fakeFetch(() => json({}));
      assert.equal(gitlabConfigured(), false);
      const message = "GitLab n'est pas réglé sur l'ordinateur (GITLAB_TOKEN dans .env)";
      await rejectsWith(listMrs(), 503, message);
      await rejectsWith(mergeMr("a/b", 1, SHA), 503, message);
      assert.equal(calls.length, 0);
    });
  }

  it("jeton réglé", () => {
    assert.equal(gitlabConfigured(), true);
  });
});

describe("gitlab : mrUrl", () => {
  it("GITLAB_URL par défaut", () => {
    delete process.env.GITLAB_URL;
    assert.equal(mrUrl("a/b", 3), "https://gitlab.unowhy.dev/a/b/-/merge_requests/3");
  });

  it("GITLAB_URL vide : défaut", () => {
    process.env.GITLAB_URL = "  ";
    assert.equal(mrUrl("a/b", 3), "https://gitlab.unowhy.dev/a/b/-/merge_requests/3");
  });

  it("« / » final retiré", () => {
    process.env.GITLAB_URL = "https://gitlab.test///";
    assert.equal(mrUrl("a/b", 3), "https://gitlab.test/a/b/-/merge_requests/3");
  });

  it("projet imbriqué", () => {
    assert.equal(mrUrl("a/b/c", 9), "https://gitlab.test/a/b/c/-/merge_requests/9");
  });
});
