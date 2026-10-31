import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { after, before, beforeEach, describe, it } from "node:test";
import { FAKE_GITHUB_CLIENT_ID, FAKE_SHA, fakeRun, startFakeGithub, type FakeGithub } from "./fake-github.ts";
import { startServer, type TestServer } from "./server.ts";

const PERSONAL = "ghp_jeton-personnel-de-test";
const MERGE_BODY = { project: "org/app", iid: 12, sha: FAKE_SHA };

let github: FakeGithub;
const servers: TestServer[] = [];
const boot = async (env: Record<string, string> = {}, os?: "mac" | "other") => {
  const server = await startServer({ os, env: { GITHUB_URL: github.url, ...env } });
  servers.push(server);
  return server;
};
before(async () => {
  github = await startFakeGithub();
  github.personalToken = PERSONAL;
});
after(async () => {
  await Promise.all(servers.map((s) => s.stop()));
  await github.stop();
});

/** Attend qu'une condition devienne vraie (sondage du device flow en arrière-plan) ; 30 s : la suite complète charge la machine. */
async function until<T>(read: () => Promise<T>, ok: (v: T) => boolean): Promise<T> {
  for (const end = Date.now() + 30_000; Date.now() < end;) {
    const v = await read();
    if (ok(v)) return v;
    await new Promise((r) => setTimeout(r, 50));
  }
  throw new Error(`délai dépassé : ${JSON.stringify(await read())}`);
}
const status = async (server: TestServer) => (await server.call("GET", "/local/status")).json.github;
/** Aucun jeton émis ou personnel dans une réponse ni dans le journal du compagnon. */
const assertNoLeak = (server: TestServer, ...texts: string[]) => {
  for (const secret of [PERSONAL, ...github.issued]) {
    for (const t of [...texts, server.output()]) assert.ok(!t.includes(secret), "jeton GitHub divulgué");
  }
};

describe("GitHub sans connexion", () => {
  it("rien de réglé : pas de widget, « pas connecté » en 200, fusion refusée sans appeler GitHub", async () => {
    const server = await boot();
    assert.equal((await server.call("GET", "/deck/features")).json.github, false);
    const prs = await server.call("GET", "/github/prs");
    assert.equal(prs.status, 200);
    assert.deepEqual(prs.json, { connected: false, expired: false, canMerge: false, mine: [], review: [] });
    assert.deepEqual((await server.call("GET", "/github/actions")).json, { connected: false, expired: false, pipelines: [] });
    const before = github.seen.length;
    assert.equal((await server.call("POST", "/github/prs/merge", MERGE_BODY)).status, 503);
    assert.equal(github.seen.length, before);
    // Page locale : tuile grisée, pas de bouton de connexion.
    assert.deepEqual(await status(server), { available: false, token: false, account: null, login: null });
    assert.equal((await fetch(server.url + "/oauth/github/start", { redirect: "manual" })).status, 404);
  });

  it("ordinateur non piloté : 501", async () => {
    const server = await boot({ GITHUB_TOKEN: PERSONAL }, "other");
    assert.equal((await server.call("GET", "/github/prs")).status, 501);
  });
});

describe("GitHub par GITHUB_TOKEN", () => {
  let server: TestServer;
  before(async () => {
    server = await boot({ GITHUB_TOKEN: PERSONAL });
  });
  beforeEach(() => {
    github.mergeStatus = 200;
    github.seen.length = 0;
  });

  it("mes PR (écrites ou assignées, sans doublon) et à relire, plus récentes d'abord", async () => {
    assert.equal((await server.call("GET", "/deck/features")).json.github, true);
    const res = await server.call("GET", "/github/prs");
    assert.equal(res.status, 200);
    assert.equal(res.json.connected, true);
    assert.equal(res.json.canMerge, true);
    assert.deepEqual(
      res.json.mine.map((p: { iid: number }) => p.iid),
      [3, 12],
    );
    const [blocked, ready] = res.json.mine;
    assert.deepEqual(ready, {
      project: "org/app",
      iid: 12,
      title: "fix: un titre",
      draft: false,
      author: "ahmed",
      pipeline: "success",
      approvals: { given: 1, required: 1, by: ["camille"] },
      status: "clean",
      statusLabel: "Prête à fusionner",
      mergeable: true,
      sha: FAKE_SHA,
      updatedAt: "2026-10-28T10:00:00Z",
    });
    assert.equal(blocked.statusLabel, "Approbation manquante");
    assert.equal(blocked.mergeable, false);
    assert.deepEqual(blocked.approvals, { given: 0, required: 1, by: [] });
    // Résultat de recherche qui n'est pas une PR : ignoré ; PR sans commit lu : pas de contrôles.
    assert.equal(res.json.review.length, 1);
    assert.equal(res.json.review[0].pipeline, "none");
    const sent = github.seen.find((s) => s.url === "/graphql")!;
    assert.equal(sent.authorization, `Bearer ${PERSONAL}`);
    assert.match(sent.body.query, /author:@me/);
    assert.match(sent.body.query, /review-requested:@me/);
    assertNoLeak(server, JSON.stringify(res.json));
  });

  it("gardées 20 s : une seule lecture de GitHub", async () => {
    await server.call("POST", "/github/prs/merge", MERGE_BODY); // vide le cache
    await server.call("GET", "/github/prs");
    await server.call("GET", "/github/prs");
    assert.equal(github.seen.filter((s) => s.url === "/graphql").length, 1);
  });

  it("fusion : renvoie le sha affiché ; refus de GitHub traduits", async () => {
    const ok = await server.call("POST", "/github/prs/merge", MERGE_BODY);
    assert.equal(ok.status, 200);
    const put = github.seen.find((s) => s.method === "PUT")!;
    assert.equal(put.url, "/repos/org/app/pulls/12/merge");
    assert.deepEqual(put.body, { sha: FAKE_SHA });
    github.mergeStatus = 409;
    const moved = await server.call("POST", "/github/prs/merge", MERGE_BODY);
    assert.deepEqual([moved.status, moved.json.error], [409, "La PR a changé depuis l'affichage : relis la liste"]);
    github.mergeStatus = 405;
    assert.equal((await server.call("POST", "/github/prs/merge", MERGE_BODY)).status, 409);
    github.mergeStatus = 403;
    assert.equal((await server.call("POST", "/github/prs/merge", MERGE_BODY)).status, 502);
    assertNoLeak(server);
  });

  it("ouvrir : adresse reconstruite par le compagnon ; dépôt mal formé refusé", async () => {
    server.clearOsCalls();
    assert.equal((await server.call("POST", "/github/prs/open", { project: "org/app", iid: 7 })).status, 200);
    assert.equal((await server.call("POST", "/github/actions/open", { project: "org/app", id: 12_345_678_901 })).status, 200);
    assert.deepEqual(server.osCalls(), [
      { fn: "openTarget", args: [`${github.url}/org/app/pull/7`] },
      { fn: "openTarget", args: [`${github.url}/org/app/actions/runs/12345678901`] },
    ]);
    for (const project of ["org/..", "../x", "org/app/extra", "org", "-org/app"]) {
      assert.equal((await server.call("POST", "/github/prs/open", { project, iid: 1 })).status, 400, project);
    }
  });

  it("Actions : dernier run de la branche par défaut de chaque dépôt, en cours puis échecs, jobs en échec", async () => {
    await server.call("POST", "/github/prs/merge", MERGE_BODY); // vide le cache
    github.runs = {
      "org/app": fakeRun({ conclusion: "failure", id: 777 }),
      "org/lib": fakeRun({ id: 888, head_branch: "trunk", status: "in_progress", conclusion: null }),
    };
    github.jobs = {
      777: [
        { name: "tests", conclusion: "failure" },
        { name: "lint", conclusion: "success" },
      ],
    };
    const res = await server.call("GET", "/github/actions");
    assert.equal(res.status, 200);
    assert.equal(res.json.connected, true);
    const [running, failed] = res.json.pipelines;
    assert.deepEqual([running.project, running.ref, running.pipeline, running.durationSec], ["org/lib", "trunk", "running", null]);
    assert.deepEqual([failed.project, failed.pipeline, failed.durationSec, failed.failedJobs], ["org/app", "failed", 290, ["tests"]]);
    assert.ok(github.seen.some((s) => s.url.startsWith("/repos/org/lib/actions/runs?branch=trunk&")));
  });

  it("Actions : limite de débit = erreur visible ; dépôt refusé (403 SSO) = pas de ligne", async () => {
    github.runs = { "org/app": fakeRun(), "org/lib": fakeRun({ id: 888, head_branch: "trunk" }) };
    try {
      github.runsAnswer = { "org/lib": { status: 403, body: { message: "Resource protected by organization SAML enforcement" } } };
      await server.call("POST", "/github/prs/merge", MERGE_BODY); // vide le cache
      const sso = await server.call("GET", "/github/actions");
      assert.equal(sso.status, 200);
      assert.deepEqual(
        sso.json.pipelines.map((p: { project: string }) => p.project),
        ["org/app"],
      );
      for (const limit of [
        { status: 403, headers: { "x-ratelimit-remaining": "0" }, body: { message: "API rate limit exceeded" } },
        { status: 403, body: { message: "You have exceeded a secondary rate limit" } },
      ]) {
        github.runsAnswer = { "org/lib": limit };
        await server.call("POST", "/github/prs/merge", MERGE_BODY);
        const res = await server.call("GET", "/github/actions");
        assert.deepEqual(
          [res.status, res.json.error],
          [502, "GitHub limite les appels pour l'instant : la liste revient dans quelques minutes"],
        );
      }
    } finally {
      github.runsAnswer = {};
    }
  });

  it("réponse GraphQL partielle (organisation à accès restreint) : les PR lisibles s'affichent", async () => {
    github.graphqlPartial = true;
    try {
      await server.call("POST", "/github/prs/merge", MERGE_BODY); // vide le cache
      const res = await server.call("GET", "/github/prs");
      assert.equal(res.status, 200);
      assert.deepEqual([res.json.mine.length, res.json.review.length], [2, 1]);
    } finally {
      github.graphqlPartial = false;
    }
  });
});

describe("connexion GitHub (device flow)", () => {
  let server: TestServer;
  before(async () => {
    server = await boot({ GITHUB_CLIENT_ID: FAKE_GITHUB_CLIENT_ID });
  });

  it("code affiché, autorisé sur GitHub, jeton gardé sur l'ordinateur seulement ; déconnexion", async () => {
    assert.equal((await server.call("GET", "/deck/features")).json.github, true);
    assert.equal((await server.call("GET", "/github/prs")).json.connected, false);
    const start = await fetch(server.url + "/oauth/github/start", { redirect: "manual" });
    assert.equal(start.status, 302);
    assert.match(start.headers.get("location")!, /\/\?section=github$/);
    const waiting = await status(server);
    assert.deepEqual(waiting.login, { state: "waiting", userCode: github.userCode, verificationUri: `${github.url}/login/device` });
    assert.equal(waiting.account, null);
    // Le code d'appareil (secret) ne sort jamais.
    assert.ok(!JSON.stringify(waiting).includes("dc-"));

    github.approve();
    const done = await until(
      () => status(server),
      (g) => g.account !== null,
    );
    assert.deepEqual(done.account, { username: "camille", expired: false });
    assert.deepEqual(done.login, { state: "ok" });
    const file = path.join(server.dataDir, "github.json");
    if (process.platform !== "win32") assert.equal(fs.statSync(file).mode & 0o777, 0o600);
    // Sans secret d'appli : seul l'identifiant public part.
    for (const s of github.seen.filter((x) => x.url.startsWith("/login/"))) assert.ok(!("client_secret" in s.body));

    const prs = await server.call("GET", "/github/prs");
    assert.equal(prs.json.connected, true);
    assert.equal(prs.json.canMerge, true);
    assert.equal(github.seen.at(-1)!.authorization, `Bearer ${github.issued.at(-1)}`);
    assertNoLeak(server, JSON.stringify(prs.json), JSON.stringify(done));

    assert.equal((await server.call("DELETE", "/local/accounts/github", undefined, false)).status, 200);
    assert.equal((await status(server)).account, null);
    assert.equal((await server.call("GET", "/github/prs")).json.connected, false);
  });

  it("refusé sur GitHub : pas de compte, résultat « refused »", async () => {
    await fetch(server.url + "/oauth/github/start", { redirect: "manual" });
    github.deny();
    const g = await until(
      () => status(server),
      (x) => x.login?.state !== "waiting",
    );
    assert.deepEqual([g.login, g.account], [{ state: "refused" }, null]);
  });

  it("lecture seule : « Fusionner sur l'ordi », fusion refusée sans appeler GitHub", async () => {
    github.grantScope = "read:user";
    try {
      await fetch(server.url + "/oauth/github/start", { redirect: "manual" });
      github.approve();
      await until(
        () => status(server),
        (g) => g.account !== null,
      );
      assert.equal((await server.call("GET", "/github/prs")).json.canMerge, false);
      const before = github.seen.length;
      const res = await server.call("POST", "/github/prs/merge", MERGE_BODY);
      assert.deepEqual([res.status, res.json.error], [409, "La connexion GitHub est en lecture seule : fusionne sur l'ordinateur"]);
      assert.equal(github.seen.length, before);
    } finally {
      github.grantScope = "repo";
    }
  });

  it("jeton révoqué sur GitHub : jamais de 401 vers la tablette, compte « à reconnecter »", async () => {
    await server.call("DELETE", "/local/accounts/github", undefined, false);
    await fetch(server.url + "/oauth/github/start", { redirect: "manual" });
    github.approve();
    await until(
      () => status(server),
      (g) => g.account !== null,
    );
    github.revokeAll();
    const refused = await server.call("GET", "/github/prs");
    assert.equal(refused.status, 502);
    assert.match(refused.json.error, /reconnecte-le/);
    assert.equal((await status(server)).account.expired, true);
    assert.deepEqual((await server.call("GET", "/github/prs")).json, {
      connected: false,
      expired: true,
      canMerge: false,
      mine: [],
      review: [],
    });
  });

  it("depuis la tablette : la connexion s'ouvre sur l'ordinateur", async () => {
    server.clearOsCalls();
    assert.equal((await server.call("POST", "/local/open", { section: "github" })).status, 200);
    assert.deepEqual(server.osCalls(), [{ fn: "openTarget", args: [`http://127.0.0.1:${new URL(server.url).port}/oauth/github/start`] }]);
  });

  it("démarrage refusé depuis une autre page web ; accepté depuis la page du compagnon ou une navigation directe", async () => {
    const start = (site?: string) =>
      fetch(server.url + "/oauth/github/start", { redirect: "manual", headers: site ? { "sec-fetch-site": site } : {} });
    for (const site of ["cross-site", "same-site"]) assert.equal((await start(site)).status, 403, site);
    for (const site of ["none", "same-origin", undefined]) assert.equal((await start(site)).status, 302, String(site));
  });

  it("GitHub en panne pendant l'attente : on continue d'interroger, le jeton arrive", async () => {
    await server.call("DELETE", "/local/accounts/github", undefined, false);
    await fetch(server.url + "/oauth/github/start", { redirect: "manual" });
    github.tokenFailures = 3;
    github.approve();
    const g = await until(
      () => status(server),
      (x) => x.login?.state !== "waiting",
    );
    assert.deepEqual([g.login, g.account?.username], [{ state: "ok" }, "camille"]);
    assert.equal(github.tokenFailures, 0);
  });

  it("déconnecté pendant la fin de la connexion : le jeton ne revient pas", async () => {
    await server.call("DELETE", "/local/accounts/github", undefined, false);
    await fetch(server.url + "/oauth/github/start", { redirect: "manual" });
    github.userDelayMs = 1500;
    const from = github.seen.length;
    try {
      github.approve();
      // Le compagnon a reçu le jeton et lit le nom (lent) : la déconnexion arrive à ce moment-là.
      await until(
        async () => github.seen.slice(from).some((s) => s.url === "/user"),
        (seen) => seen,
      );
      await server.call("DELETE", "/local/accounts/github", undefined, false);
      await new Promise((r) => setTimeout(r, 2000));
      assert.equal((await status(server)).account, null);
      assert.equal((await server.call("GET", "/github/prs")).json.connected, false);
    } finally {
      github.userDelayMs = 0;
    }
  });
});

describe("GitHub : changement de compte", () => {
  it("relier un compte alors que GITHUB_TOKEN sert : la liste suivante est lue avec le compte, pas le cache", async () => {
    const server = await boot({ GITHUB_TOKEN: PERSONAL, GITHUB_CLIENT_ID: FAKE_GITHUB_CLIENT_ID });
    await server.call("GET", "/github/prs");
    await fetch(server.url + "/oauth/github/start", { redirect: "manual" });
    github.approve();
    await until(
      () => status(server),
      (g) => g.account !== null,
    );
    const before = github.seen.length;
    await server.call("GET", "/github/prs");
    const read = github.seen.slice(before).find((s) => s.url === "/graphql");
    assert.ok(read, "pas de nouvelle lecture : le cache de l'ancienne identité a servi");
    assert.equal(read.authorization, `Bearer ${github.issued.at(-1)}`);
  });
});
