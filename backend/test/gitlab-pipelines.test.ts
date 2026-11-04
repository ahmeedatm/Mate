import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { after, before, beforeEach, describe, it } from "node:test";
import { fakeMr, fakePipeline, fakeSchedule, fakeStages, startFakeGitlab, type FakeGitlab } from "./fake-gitlab.ts";
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
const pid = (n: number) => `gid://gitlab/Ci::Pipeline/${n}`;
const sid = (n: number) => `gid://gitlab/Ci::PipelineSchedule/${n}`;
/** MR assignée ; son numéro donne son âge (la 1 est la plus récente). */
const mr = (iid: number, pipeline: Record<string, unknown> | null, project = "grp/proj") =>
  fakeMr({ iid: String(iid), title: `MR ${iid}`, project: { fullPath: project }, updatedAt: ago(iid * 60), headPipeline: pipeline });
const schedule = (n: number, description: string, lastPipeline: Record<string, unknown> | null) =>
  fakeSchedule({ id: sid(n), description, lastPipeline });
const choose = (...refs: [string, number][]) =>
  server.call("POST", "/gitlab/schedules", { schedules: refs.map(([project, id]) => ({ project, id })) });

beforeEach(async () => {
  gitlab.schedules = {};
  gitlab.memberProjects = [];
  gitlab.failedJobs = {};
  gitlab.schedulesStatus = 200;
  gitlab.deniedProjects = [];
  gitlab.mrs = { assigned: [], review: [] };
  server.clearOsCalls();
  await choose();
  gitlab.seen.length = 0;
});

/** Lecture qui va jusqu'à GitLab : une fusion vide les caches du compagnon. */
const clear = () => server.call("POST", "/gitlab/mrs/merge", { project: "grp/proj", iid: 12, sha: SHA });
const fresh = async () => {
  await clear();
  gitlab.seen.length = 0;
  return server.call("GET", "/gitlab/pipelines");
};
const graphql = () => gitlab.seen.filter((s) => s.url === "/api/graphql");
const scheduleQueries = () => graphql().filter((s) => String(s.body.query).includes("pipelineSchedules"));
const rest = () => gitlab.seen.filter((s) => s.url.startsWith("/api/v4/"));

describe("GET /gitlab/pipelines : pipelines de mes MR", () => {
  it("une ligne par MR qui m'est assignée et qui a un pipeline, triées : en cours, échecs, puis le reste ; stages et jobs en échec", async () => {
    gitlab.mrs = {
      assigned: [
        mr(
          1,
          fakePipeline({
            id: pid(101),
            status: "RUNNING",
            startedAt: ago(240),
            finishedAt: null,
            duration: null,
            stages: fakeStages(["build", "success"], ["test", "running"], ["deploy", "manual"]),
          }),
        ),
        mr(
          2,
          fakePipeline({
            id: pid(102),
            status: "FAILED",
            finishedAt: ago(300),
            duration: 148,
            stages: fakeStages(["build", "success"], ["test", "failed"]),
          }),
        ),
        mr(3, fakePipeline({ id: pid(103), finishedAt: ago(600) })),
        mr(4, null),
      ],
      review: [mr(5, fakePipeline({ id: pid(105) }), "grp/autre")],
    };
    gitlab.failedJobs = { 102: ["tests-unitaires", "tests-e2e"] };
    const { status, json } = await fresh();
    assert.equal(status, 200);
    assert.equal(json.connected, true);
    assert.deepEqual(json.schedules, []);
    assert.deepEqual(
      json.mrs.map((p: any) => [p.mrIid, p.pipeline]),
      [
        [1, "running"],
        [2, "failed"],
        [3, "success"],
      ],
    );
    const [running, failed, done] = json.mrs;
    assert.deepEqual([running.id, running.project, running.title, running.durationSec], [101, "grp/proj", "MR 1", null]);
    assert.ok(Math.abs(running.ageSec - 240) <= 3, `âge ${running.ageSec}`);
    assert.deepEqual(running.stages, [
      { name: "build", status: "success" },
      { name: "test", status: "running" },
      { name: "deploy", status: "none" },
    ]);
    assert.deepEqual(failed.failedJobs, ["tests-unitaires", "tests-e2e"]);
    assert.equal(failed.durationSec, 148);
    assert.deepEqual(failed.stages, [
      { name: "build", status: "success" },
      { name: "test", status: "failed" },
    ]);
    assert.deepEqual(done.failedJobs, []);
    assert.ok(Math.abs(done.ageSec - 600) <= 3);
    // Le jeton part en en-tête vers GitLab, jamais dans la réponse.
    assert.ok([...rest(), ...graphql()].every((s) => s.privateToken === PERSONAL));
    assert.ok(!JSON.stringify(json).includes(PERSONAL));
  });

  it("réduit le statut de chaque stage comme celui d'un pipeline", async () => {
    const statuses = ["success", "success_with_warnings", "failed", "running", "pending", "created", "canceled", "skipped", "manual"];
    gitlab.mrs = {
      assigned: [mr(1, fakePipeline({ stages: fakeStages(...statuses.map((st, i): [string, string] => [`s${i}`, st])) }))],
      review: [],
    };
    const { json } = await fresh();
    assert.deepEqual(
      json.mrs[0].stages.map((st: any) => st.status),
      ["success", "success", "failed", "running", "running", "running", "canceled", "none", "none"],
    );
  });

  it("au plus 12 lignes, les MR les plus récentes d'abord", async () => {
    gitlab.mrs = { assigned: Array.from({ length: 14 }, (_, i) => mr(i + 1, fakePipeline({ id: pid(200 + i) }))), review: [] };
    const { json } = await fresh();
    assert.equal(json.mrs.length, 12);
    assert.ok(!json.mrs.some((p: any) => p.mrIid === 13 || p.mrIid === 14));
  });

  it("sans MR ni planification choisie : listes vides, 200, aucune requête de planification", async () => {
    const { status, json } = await fresh();
    assert.equal(status, 200);
    assert.deepEqual(json, { connected: true, schedules: [], mrs: [] });
    assert.deepEqual(scheduleQueries(), []);
  });

  it("résultat gardé 20 s : la lecture suivante n'appelle pas GitLab", async () => {
    gitlab.mrs = { assigned: [mr(1, fakePipeline())], review: [] };
    await fresh();
    gitlab.seen.length = 0;
    const again = await server.call("GET", "/gitlab/pipelines");
    assert.equal(again.json.mrs.length, 1);
    assert.equal(gitlab.seen.length, 0);
  });
});

describe("GET /gitlab/pipelines : planifications choisies", () => {
  const project = () => ({
    "grp/proj": [
      schedule(7, "Nightly", fakePipeline({ id: pid(900), finishedAt: ago(3600), duration: 600 })),
      schedule(
        8,
        "Weekly",
        fakePipeline({ id: pid(901), status: "FAILED", finishedAt: ago(60), duration: 30, stages: fakeStages(["tests", "failed"]) }),
      ),
      schedule(9, "Pas choisie", fakePipeline({ id: pid(909) })),
      schedule(10, "Jamais lancée", null),
    ],
    "grp/autre": [
      schedule(3, "Autre projet", fakePipeline({ id: pid(902), status: "RUNNING", startedAt: ago(120), finishedAt: null, duration: null })),
    ],
  });

  it("dernier run de chaque planification choisie, trié : en cours, échecs, puis le reste ; une planification jamais lancée n'a pas de ligne", async () => {
    gitlab.schedules = project();
    gitlab.failedJobs = { 901: ["tests-e2e"] };
    assert.equal((await choose(["grp/proj", 7], ["grp/proj", 8], ["grp/proj", 10], ["grp/autre", 3])).status, 200);
    const { status, json } = await fresh();
    assert.equal(status, 200);
    assert.deepEqual(
      json.schedules.map((p: any) => [p.title, p.pipeline, p.mrIid]),
      [
        ["Autre projet", "running", null],
        ["Weekly", "failed", null],
        ["Nightly", "success", null],
      ],
    );
    const [running, failed, ok] = json.schedules;
    assert.deepEqual([running.project, running.id, running.durationSec], ["grp/autre", 902, null]);
    assert.ok(Math.abs(running.ageSec - 120) <= 3);
    assert.deepEqual([failed.id, failed.ref, failed.durationSec, failed.failedJobs], [901, "main", 30, ["tests-e2e"]]);
    assert.deepEqual(failed.stages, [{ name: "tests", status: "failed" }]);
    assert.deepEqual([ok.id, ok.durationSec], [900, 600]);
    assert.deepEqual(json.mrs, []);
    // Une requête par projet choisi, avec son chemin en variable.
    assert.deepEqual(
      scheduleQueries()
        .map((q) => q.body.variables.path)
        .sort(),
      ["grp/autre", "grp/proj"],
    );
  });

  it("une planification choisie puis supprimée dans GitLab n'a pas de ligne, les autres restent", async () => {
    gitlab.schedules = project();
    await choose(["grp/proj", 7], ["grp/proj", 404], ["grp/introuvable", 1]);
    const { status, json } = await fresh();
    assert.equal(status, 200);
    assert.deepEqual(
      json.schedules.map((p: any) => p.title),
      ["Nightly"],
    );
  });

  it("un projet dont la CI est interdite au compte (403) n'a pas de ligne, les autres restent", async () => {
    gitlab.schedules = project();
    gitlab.deniedProjects = ["grp/autre"];
    await choose(["grp/proj", 7], ["grp/autre", 3]);
    const { status, json } = await fresh();
    assert.equal(status, 200);
    assert.deepEqual(
      json.schedules.map((p: any) => p.title),
      ["Nightly"],
    );
  });

  it("choisir relit tout de suite, sans attendre les 20 s du cache", async () => {
    gitlab.schedules = project();
    await choose(["grp/proj", 7]);
    assert.equal((await fresh()).json.schedules.length, 1);
    await choose(["grp/proj", 7], ["grp/proj", 8]);
    assert.equal((await server.call("GET", "/gitlab/pipelines")).json.schedules.length, 2);
  });

  it("GitLab refuse le jeton ou répond mal : 502 avec une phrase, jamais 401 ni le jeton", async () => {
    gitlab.schedules = project();
    await choose(["grp/proj", 7]);
    gitlab.schedulesStatus = 401;
    const denied = await fresh();
    assert.equal(denied.status, 502);
    assert.match(denied.json.error, /GitLab refuse l'accès de l'ordinateur/);
    gitlab.schedulesStatus = 500;
    const broken = await fresh();
    assert.equal(broken.status, 502);
    assert.equal(broken.json.error, "GitLab ne répond pas");
    assert.ok(!JSON.stringify(broken.json).includes(PERSONAL));
    // Une erreur n'est pas gardée : GitLab revenu, la lecture suivante marche.
    gitlab.schedulesStatus = 200;
    assert.equal((await server.call("GET", "/gitlab/pipelines")).status, 200);
  });

  it("sans compte ni GITLAB_TOKEN : 200 « pas connecté », GitLab n'est pas appelé", async () => {
    const { status, json } = await noToken.call("GET", "/gitlab/pipelines");
    assert.equal(status, 200);
    assert.deepEqual(json, { connected: false, expired: false, schedules: [], mrs: [] });
    assert.equal(gitlab.seen.length, 0);
  });

  it("sans jeton d'appairage : 401 ; hors Mac et Windows : 501", async () => {
    assert.equal((await server.call("GET", "/gitlab/pipelines", undefined, false)).status, 401);
    assert.equal((await other.call("GET", "/gitlab/pipelines")).status, 501);
  });
});

describe("planifications : choix", () => {
  it("POST /gitlab/schedules garde le choix dans .data/gitlab-schedules.json, sans doublon, et GET /gitlab/schedules le rend", async () => {
    assert.equal((await choose(["grp/proj", 7], ["grp/autre", 3], ["grp/proj", 7])).status, 200);
    assert.deepEqual(JSON.parse(fs.readFileSync(path.join(server.dataDir, "gitlab-schedules.json"), "utf8")), {
      schedules: [
        { project: "grp/proj", id: 7 },
        { project: "grp/autre", id: 3 },
      ],
    });
    await clear();
    assert.deepEqual((await server.call("GET", "/gitlab/schedules")).json.selected, [
      { project: "grp/proj", id: 7 },
      { project: "grp/autre", id: 3 },
    ]);
  });

  it("POST /gitlab/schedules : 400 sur un corps invalide, le choix reste", async () => {
    await choose(["grp/proj", 7]);
    for (const body of [
      {},
      { schedules: "grp/proj" },
      { schedules: [{ project: "../etc/passwd", id: 1 }] },
      { schedules: [{ project: "grp/proj", id: 0 }] },
      { schedules: [{ project: "grp/proj" }] },
      { schedules: Array.from({ length: 21 }, (_, i) => ({ project: "grp/proj", id: i + 1 })) },
    ]) {
      assert.equal((await server.call("POST", "/gitlab/schedules", body)).status, 400, JSON.stringify(body));
    }
    assert.equal((await server.call("POST", "/gitlab/schedules", { schedules: [] }, false)).status, 401);
    await clear();
    assert.deepEqual((await server.call("GET", "/gitlab/schedules")).json.selected, [{ project: "grp/proj", id: 7 }]);
  });

  it("GET /gitlab/schedules : planifications des projets dont je suis membre, par projet, sans ceux qui n'en ont pas ni ceux dont la CI est interdite", async () => {
    gitlab.memberProjects = ["grp/proj", "grp/vide", "grp/interdit", "grp/autre"];
    gitlab.schedules = {
      "grp/proj": [schedule(7, "Nightly", null), fakeSchedule({ id: sid(8), description: "Weekly", ref: "release", active: false })],
      "grp/interdit": [schedule(1, "Secrète", null)],
      "grp/autre": [schedule(3, "Autre projet", null)],
    };
    gitlab.deniedProjects = ["grp/interdit"];
    await clear();
    const { status, json } = await server.call("GET", "/gitlab/schedules");
    assert.equal(status, 200);
    assert.deepEqual(json, {
      connected: true,
      projects: [
        { project: "grp/autre", schedules: [{ id: 3, description: "Autre projet", ref: "main", active: true }] },
        {
          project: "grp/proj",
          schedules: [
            { id: 7, description: "Nightly", ref: "main", active: true },
            { id: 8, description: "Weekly", ref: "release", active: false },
          ],
        },
      ],
      selected: [],
    });
    assert.ok(rest().every((s) => s.privateToken === PERSONAL));
  });

  it("GET /gitlab/schedules : lit les pages suivantes de projets", async () => {
    gitlab.memberProjects = Array.from({ length: 150 }, (_, i) => `grp/p${i}`);
    gitlab.schedules = { "grp/p120": [schedule(5, "Loin dans la liste", null)] };
    await clear();
    const { json } = await server.call("GET", "/gitlab/schedules");
    assert.deepEqual(
      json.projects.map((p: any) => p.project),
      ["grp/p120"],
    );
    assert.ok(rest().some((s) => s.url.includes("/api/v4/projects?") && s.url.includes("page=2")));
  });

  it("GET /gitlab/schedules : gardé 5 min, la lecture suivante n'appelle pas GitLab", async () => {
    gitlab.memberProjects = ["grp/proj"];
    gitlab.schedules = { "grp/proj": [schedule(7, "Nightly", null)] };
    await clear();
    await server.call("GET", "/gitlab/schedules");
    gitlab.seen.length = 0;
    assert.equal((await server.call("GET", "/gitlab/schedules")).json.projects.length, 1);
    assert.equal(gitlab.seen.length, 0);
  });

  it("GET /gitlab/schedules : pas connecté 200, refus ou panne 502, sans jeton d'appairage 401", async () => {
    assert.deepEqual((await noToken.call("GET", "/gitlab/schedules")).json, {
      connected: false,
      expired: false,
      projects: [],
      selected: [],
    });
    gitlab.memberProjects = ["grp/proj"];
    await clear();
    gitlab.schedulesStatus = 401;
    assert.equal((await server.call("GET", "/gitlab/schedules")).status, 502);
    gitlab.schedulesStatus = 500;
    assert.equal((await server.call("GET", "/gitlab/schedules")).status, 502);
    gitlab.schedulesStatus = 200;
    assert.equal((await server.call("GET", "/gitlab/schedules", undefined, false)).status, 401);
    assert.equal((await other.call("GET", "/gitlab/schedules")).status, 501);
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
