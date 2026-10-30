import assert from "node:assert/strict";
import { after, before, beforeEach, describe, it } from "node:test";
import { fakeMr, fakePipeline, startFakeGitlab, type FakeGitlab } from "./fake-gitlab.ts";
import { startServer, type TestServer } from "./server.ts";

const PERSONAL = "glpat-jeton-de-test-pipelines";
const SHA = "0123456789abcdef0123456789abcdef01234567";

let gitlab: FakeGitlab;
let server: TestServer;
let noToken: TestServer;
let other: TestServer;
before(async () => {
  gitlab = await startFakeGitlab();
  gitlab.personalToken = PERSONAL;
  server = await startServer({ env: { GITLAB_URL: gitlab.url, GITLAB_TOKEN: PERSONAL } });
  noToken = await startServer({ env: { GITLAB_URL: gitlab.url } });
  other = await startServer({ os: "other", env: { GITLAB_URL: gitlab.url, GITLAB_TOKEN: PERSONAL } });
});
after(async () => {
  await Promise.all([server.stop(), noToken.stop(), other.stop()]);
  await gitlab.stop();
});

const ago = (seconds: number) => new Date(Date.now() - seconds * 1000).toISOString();
const project = (fullPath: string, minutes = 0) =>
  fakeMr({ iid: String(minutes + 1), project: { fullPath }, updatedAt: ago(minutes * 60) });

beforeEach(() => {
  gitlab.seen.length = 0;
  gitlab.pipelines = {};
  gitlab.failedJobs = {};
  gitlab.pipelinesStatus = 200;
  gitlab.mrs = { assigned: [project("grp/proj")], review: [] };
  server.clearOsCalls();
});

/** Lecture qui va jusqu'à GitLab : une fusion vide le cache de 20 s du compagnon. */
const fresh = async () => {
  await server.call("POST", "/gitlab/mrs/merge", { project: "grp/proj", iid: 12, sha: SHA });
  gitlab.seen.length = 0;
  return server.call("GET", "/gitlab/pipelines");
};
const rest = () => gitlab.seen.filter((s) => s.url.startsWith("/api/v4/"));

describe("GET /gitlab/pipelines", () => {
  it("un pipeline par projet de mes MR (assignées et à relire), triés : en cours, échecs, puis le reste du plus récent au plus ancien", async () => {
    gitlab.mrs = {
      assigned: [project("grp/vieux", 1), project("grp/echec", 2), project("grp/sous/en-cours", 3)],
      review: [project("grp/recent", 4), project("grp/echec", 5)],
    };
    gitlab.pipelines = {
      "grp/vieux": fakePipeline({ id: 1, finished_at: ago(7200), duration: 100 }),
      "grp/recent": fakePipeline({ id: 2, finished_at: ago(600), duration: 60 }),
      "grp/echec": fakePipeline({ id: 3, status: "failed", ref: "main", finished_at: ago(300), duration: 148 }),
      "grp/sous/en-cours": fakePipeline({ id: 4, status: "running", ref: "main", started_at: ago(240), finished_at: null, duration: null }),
    };
    gitlab.failedJobs = { 3: ["tests-unitaires", "tests-e2e"] };
    const { status, json } = await fresh();
    assert.equal(status, 200);
    assert.equal(json.connected, true);
    assert.deepEqual(
      json.pipelines.map((p: any) => [p.project, p.pipeline]),
      [
        ["grp/sous/en-cours", "running"],
        ["grp/echec", "failed"],
        ["grp/recent", "success"],
        ["grp/vieux", "success"],
      ],
    );
    const [running, failed, recent] = json.pipelines;
    assert.deepEqual([running.id, running.ref, running.durationSec], [4, "main", null]);
    assert.ok(Math.abs(running.ageSec - 240) <= 3, `âge ${running.ageSec}`);
    assert.deepEqual(failed.failedJobs, ["tests-unitaires", "tests-e2e"]);
    assert.equal(failed.durationSec, 148);
    assert.deepEqual(recent.failedJobs, []);
    assert.ok(Math.abs(recent.ageSec - 600) <= 3);
    // Le jeton part en en-tête vers GitLab, jamais dans la réponse.
    assert.ok(rest().every((s) => s.privateToken === PERSONAL));
    assert.ok(!JSON.stringify(json).includes(PERSONAL));
  });

  it("réduit les statuts de GitLab comme pour les MR", async () => {
    gitlab.mrs = { assigned: ["a/p", "a/c", "a/s", "a/w", "a/m"].map((p, i) => project(p, i)), review: [] };
    gitlab.pipelines = {
      "a/p": fakePipeline({ id: 1, status: "pending", finished_at: null }),
      "a/c": fakePipeline({ id: 2, status: "canceled" }),
      "a/s": fakePipeline({ id: 3, status: "skipped" }),
      "a/w": fakePipeline({ id: 4, status: "success_with_warnings" }),
      "a/m": fakePipeline({ id: 5, status: "manual" }),
    };
    const { json } = await fresh();
    const byProject = Object.fromEntries(json.pipelines.map((p: any) => [p.project, p.pipeline]));
    assert.deepEqual(byProject, { "a/p": "running", "a/c": "canceled", "a/s": "none", "a/w": "success", "a/m": "none" });
  });

  it("un projet sans pipeline (404) n'a pas de ligne ; un échec garde sa ligne si les jobs ne se lisent pas", async () => {
    gitlab.mrs = { assigned: [project("grp/vide", 1), project("grp/proj", 2)], review: [] };
    gitlab.pipelines = { "grp/proj": fakePipeline({ id: 9, status: "failed" }) };
    const { status, json } = await fresh();
    assert.equal(status, 200);
    assert.deepEqual(
      json.pipelines.map((p: any) => [p.project, p.pipeline, p.failedJobs]),
      [["grp/proj", "failed", []]],
    );
  });

  it("au plus 8 lignes, les projets des MR les plus récentes d'abord", async () => {
    const names = Array.from({ length: 10 }, (_, i) => `grp/p${i}`);
    gitlab.mrs = { assigned: names.map((n, i) => project(n, i)), review: [] };
    gitlab.pipelines = Object.fromEntries(names.map((n, i) => [n, fakePipeline({ id: i + 1 })]));
    const { json } = await fresh();
    assert.equal(json.pipelines.length, 8);
    assert.ok(!json.pipelines.some((p: any) => p.project === "grp/p8" || p.project === "grp/p9"));
  });

  it("sans MR : liste vide, 200", async () => {
    gitlab.mrs = { assigned: [], review: [] };
    const { status, json } = await fresh();
    assert.equal(status, 200);
    assert.deepEqual(json, { connected: true, pipelines: [] });
  });

  it("résultat gardé 20 s : la lecture suivante n'appelle pas GitLab", async () => {
    gitlab.pipelines = { "grp/proj": fakePipeline() };
    await fresh();
    gitlab.seen.length = 0;
    const again = await server.call("GET", "/gitlab/pipelines");
    assert.equal(again.json.pipelines.length, 1);
    assert.equal(gitlab.seen.length, 0);
  });

  it("sans compte ni GITLAB_TOKEN : 200 « pas connecté », GitLab n'est pas appelé", async () => {
    gitlab.seen.length = 0;
    const { status, json } = await noToken.call("GET", "/gitlab/pipelines");
    assert.equal(status, 200);
    assert.deepEqual(json, { connected: false, expired: false, pipelines: [] });
    assert.equal(gitlab.seen.length, 0);
  });

  it("GitLab refuse l'accès ou répond mal : 502 avec une phrase, jamais 401 ni le jeton", async () => {
    gitlab.pipelines = { "grp/proj": fakePipeline() };
    for (const code of [401, 403]) {
      gitlab.pipelinesStatus = code;
      const denied = await fresh();
      assert.equal(denied.status, 502, String(code));
      assert.match(denied.json.error, /GitLab refuse l'accès de l'ordinateur/);
    }
    gitlab.pipelinesStatus = 500;
    const broken = await fresh();
    assert.equal(broken.status, 502);
    assert.equal(broken.json.error, "GitLab ne répond pas");
    assert.ok(!JSON.stringify(broken.json).includes(PERSONAL));
    // Une erreur n'est pas gardée : GitLab revenu, la lecture suivante marche.
    gitlab.pipelinesStatus = 200;
    assert.equal((await server.call("GET", "/gitlab/pipelines")).status, 200);
  });

  it("sans jeton d'appairage : 401 ; hors Mac et Windows : 501", async () => {
    assert.equal((await server.call("GET", "/gitlab/pipelines", undefined, false)).status, 401);
    assert.equal((await other.call("GET", "/gitlab/pipelines")).status, 501);
  });
});

describe("POST /gitlab/pipelines/open", () => {
  it("ouvre la page du pipeline : l'adresse est reconstruite par le compagnon", async () => {
    const { status, json } = await server.call("POST", "/gitlab/pipelines/open", { project: "grp/sous/proj", id: 8841 });
    assert.equal(status, 200);
    assert.deepEqual(json, { ok: true });
    assert.deepEqual(server.osCalls(), [{ fn: "openTarget", args: [`${gitlab.url}/grp/sous/proj/-/pipelines/8841`] }]);
  });

  it("ignore une adresse fournie", async () => {
    assert.equal(
      (await server.call("POST", "/gitlab/pipelines/open", { project: "grp/proj", id: 1, url: "https://evil.example" })).status,
      200,
    );
    assert.deepEqual(server.osCalls(), [{ fn: "openTarget", args: [`${gitlab.url}/grp/proj/-/pipelines/1`] }]);
  });

  it("refuse un projet piégé, un id invalide, et sans jeton d'appairage", async () => {
    server.clearOsCalls();
    for (const body of [{ project: "../etc/passwd", id: 1 }, { project: "grp/proj", id: 0 }, { project: "grp/proj" }]) {
      assert.equal((await server.call("POST", "/gitlab/pipelines/open", body)).status, 400, JSON.stringify(body));
    }
    assert.equal((await server.call("POST", "/gitlab/pipelines/open", { project: "grp/proj", id: 1 }, false)).status, 401);
    assert.deepEqual(server.osCalls(), []);
  });
});
