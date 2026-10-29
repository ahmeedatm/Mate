import assert from "node:assert/strict";
import http from "node:http";
import type { AddressInfo } from "node:net";
import { after, before, beforeEach, describe, it } from "node:test";
import { startServer, type TestServer } from "./server.ts";

const GITLAB_TOKEN = "glpat-secret-api-test";
const SHA = "0123456789abcdef0123456789abcdef01234567";

const node = (over: Record<string, unknown> = {}) => ({
  iid: "12",
  title: "fix: un titre",
  draft: false,
  detailedMergeStatus: "MERGEABLE",
  diffHeadSha: SHA,
  updatedAt: "2026-10-28T10:00:00Z",
  approvalsRequired: 1,
  approvalsLeft: 0,
  approvedBy: { nodes: [{ name: "Camille" }] },
  author: { name: "Ahmed" },
  project: { fullPath: "grp/proj" },
  headPipeline: { status: "SUCCESS" },
  ...over,
});

/** Faux GitLab : garde les requêtes reçues ; `graphqlStatus` et `mergeStatus` fixent la réponse. */
type Seen = { method: string; url: string; token: string | undefined; body: any };
let seen: Seen[] = [];
let graphqlStatus = 200;
let mergeStatus = 200;
const gitlab = http.createServer((req, res) => {
  let raw = "";
  req.on("data", (c) => (raw += c));
  req.on("end", () => {
    seen.push({
      method: req.method ?? "",
      url: req.url ?? "",
      token: req.headers["private-token"] as string | undefined,
      body: raw && JSON.parse(raw),
    });
    res.setHeader("content-type", "application/json");
    const failed = req.url === "/api/graphql" ? graphqlStatus : mergeStatus;
    if (failed !== 200) {
      res.statusCode = failed;
      return res.end(JSON.stringify({ message: `refus ${GITLAB_TOKEN}` }));
    }
    if (req.url === "/api/graphql") {
      return res.end(
        JSON.stringify({
          data: {
            currentUser: {
              assignedMergeRequests: {
                nodes: [node(), node({ iid: "3", updatedAt: "2026-10-29T10:00:00Z", detailedMergeStatus: "NOT_APPROVED" })],
              },
              reviewRequestedMergeRequests: {
                nodes: [node({ iid: "99", project: { fullPath: "dev/robot-framework" }, headPipeline: null })],
              },
            },
          },
        }),
      );
    }
    res.end(JSON.stringify({ state: "merged" }));
  });
});

let server: TestServer;
let noToken: TestServer;
let other: TestServer;
let gitlabUrl: string;
before(async () => {
  await new Promise<void>((r) => gitlab.listen(0, "127.0.0.1", r));
  gitlabUrl = `http://127.0.0.1:${(gitlab.address() as AddressInfo).port}`;
  server = await startServer({ env: { GITLAB_URL: gitlabUrl, GITLAB_TOKEN } });
  noToken = await startServer({ env: { GITLAB_URL: gitlabUrl } });
  other = await startServer({ os: "other", env: { GITLAB_URL: gitlabUrl, GITLAB_TOKEN } });
});
after(async () => {
  await Promise.all([server?.stop(), noToken?.stop(), other?.stop()]);
  gitlab.close();
});
beforeEach(() => {
  seen = [];
  graphqlStatus = 200;
  mergeStatus = 200;
  server.clearOsCalls();
});

/** Lecture qui va jusqu'à GitLab : une fusion, même refusée, vide le cache de 20 s du serveur. */
const freshRead = async () => {
  await server.call("POST", "/gitlab/mrs/merge", { project: "grp/proj", iid: 12, sha: SHA });
  return server.call("GET", "/gitlab/mrs");
};

describe("GET /gitlab/mrs", () => {
  it("rend mine et review mis en forme, avec le jeton GitLab en en-tête", async () => {
    // Le cache de 20 s est dans le processus du serveur : un seul test lit avec succès, les autres lisent des erreurs.
    const { status, json } = await server.call("GET", "/gitlab/mrs");
    assert.equal(status, 200);
    assert.deepEqual(
      json.mine.map((m: any) => m.iid),
      [3, 12],
    );
    assert.equal(json.connected, true);
    assert.equal(json.mine[1].project, "grp/proj");
    assert.equal(json.mine[1].mergeable, true);
    assert.equal(json.mine[1].statusLabel, "Prête à fusionner");
    assert.equal(json.mine[1].sha, SHA);
    assert.equal(json.mine[0].mergeable, false);
    assert.equal(json.review[0].iid, 99);
    assert.equal(json.review[0].pipeline, "none");
    assert.equal(seen.length, 1);
    assert.equal(seen[0].url, "/api/graphql");
    assert.equal(seen[0].token, GITLAB_TOKEN);
  });

  it("sans jeton d'appairage : 401", async () => {
    assert.equal((await server.call("GET", "/gitlab/mrs", undefined, false)).status, 401);
    assert.equal(seen.length, 0);
  });

  it("jeton GitLab refusé en lecture → 502 (pas 401)", async () => {
    graphqlStatus = 401;
    const { status, json } = await freshRead();
    assert.equal(status, 502);
    assert.match(json.error, /GitLab refuse l'accès de l'ordinateur/);
  });

  it("sans compte ni GITLAB_TOKEN : 200 « pas connecté » (la tablette le distingue), GitLab n'est pas appelé", async () => {
    const { status, json } = await noToken.call("GET", "/gitlab/mrs");
    assert.equal(status, 200);
    assert.deepEqual(json, { connected: false, expired: false, canMerge: false, mine: [], review: [] });
    assert.equal(seen.length, 0);
  });
});

describe("POST /gitlab/mrs/merge", () => {
  const merge = (body: unknown) => server.call("POST", "/gitlab/mrs/merge", body);

  it("PUT sur le projet encodé avec { sha }", async () => {
    const { status, json } = await merge({ project: "grp/proj", iid: 12, sha: SHA });
    assert.equal(status, 200);
    assert.deepEqual(json, { ok: true });
    assert.equal(seen.length, 1);
    assert.equal(seen[0].method, "PUT");
    assert.equal(seen[0].url, "/api/v4/projects/grp%2Fproj/merge_requests/12/merge");
    assert.deepEqual(seen[0].body, { sha: SHA });
    assert.equal(seen[0].token, GITLAB_TOKEN);
  });

  const refusals: [number, number, RegExp][] = [
    [405, 409, /ne peut pas être fusionnée/],
    [409, 409, /a changé depuis l'affichage/],
    [422, 409, /branche ne peut pas/],
    [404, 404, /introuvable/],
    [401, 502, /pas le droit de fusionner/],
    [403, 502, /pas le droit de fusionner/],
    [500, 502, /ne répond pas/],
  ];
  for (const [gitlabCode, code, message] of refusals) {
    it(`GitLab ${gitlabCode} → ${code} avec une phrase française, jamais 401`, async () => {
      mergeStatus = gitlabCode;
      const { status, json } = await merge({ project: "grp/proj", iid: 12, sha: SHA });
      assert.equal(status, code);
      assert.match(json.error, message);
      assert.ok(!JSON.stringify(json).includes(GITLAB_TOKEN));
    });
  }

  it("sans compte ni GITLAB_TOKEN : 503", async () => {
    const { status } = await noToken.call("POST", "/gitlab/mrs/merge", { project: "grp/proj", iid: 12, sha: SHA });
    assert.equal(status, 503);
    assert.equal(seen.length, 0);
  });

  it("demande invalide → 400, GitLab n'est pas appelé", async () => {
    const bad = [
      { project: "grp/../proj", iid: 12, sha: SHA },
      { project: "../proj", iid: 12, sha: SHA },
      { project: "grp/.cache", iid: 12, sha: SHA },
      { project: "sansslash", iid: 12, sha: SHA },
      { project: "grp/proj/", iid: 12, sha: SHA },
      { project: "grp//proj", iid: 12, sha: SHA },
      { project: "grp/pro j", iid: 12, sha: SHA },
      { project: "grp/proj?x=1", iid: 12, sha: SHA },
      { project: "grp/proj", iid: 0, sha: SHA },
      { project: "grp/proj", iid: "douze", sha: SHA },
      { project: "grp/proj", iid: 1.5, sha: SHA },
      { project: "grp/proj", iid: 12, sha: "abc123" },
      { project: "grp/proj", iid: 12, sha: SHA.toUpperCase() },
      { project: "grp/proj", iid: 12, sha: SHA + "0" },
      { project: "grp/proj", iid: 12 },
      {},
    ];
    for (const body of bad) assert.equal((await merge(body)).status, 400, JSON.stringify(body));
    assert.equal(seen.length, 0);
  });
});

describe("POST /gitlab/mrs/open", () => {
  it("ouvre l'adresse reconstruite côté ordinateur", async () => {
    const { status, json } = await server.call("POST", "/gitlab/mrs/open", { project: "grp/sous/proj", iid: 7 });
    assert.equal(status, 200);
    assert.deepEqual(json, { ok: true });
    assert.deepEqual(server.osCalls(), [{ fn: "openTarget", args: [`${gitlabUrl}/grp/sous/proj/-/merge_requests/7`] }]);
    assert.equal(seen.length, 0, "ouvrir ne parle pas à GitLab");
  });

  it("une URL envoyée par la tablette n'est jamais ouverte", async () => {
    // Fastify retire les clés hors schéma : l'adresse du client est ignorée, l'ordinateur ouvre la sienne.
    for (const extra of [{ url: "https://evil.example/" }, { webUrl: "https://evil.example/" }]) {
      server.clearOsCalls();
      assert.equal((await server.call("POST", "/gitlab/mrs/open", { project: "grp/proj", iid: 1, ...extra })).status, 200);
      assert.deepEqual(server.osCalls(), [{ fn: "openTarget", args: [`${gitlabUrl}/grp/proj/-/merge_requests/1`] }]);
    }
    for (const body of [{ project: "https://evil.example/x", iid: 1 }, { url: "https://evil.example/" }]) {
      server.clearOsCalls();
      assert.equal((await server.call("POST", "/gitlab/mrs/open", body)).status, 400, JSON.stringify(body));
      assert.deepEqual(server.osCalls(), []);
    }
  });

  it("project et iid invalides → 400", async () => {
    for (const body of [
      { project: "grp/../proj", iid: 1 },
      { project: "sansslash", iid: 1 },
      { project: "grp/proj", iid: 0 },
      { project: "grp/proj", iid: "un" },
      {},
    ]) {
      assert.equal((await server.call("POST", "/gitlab/mrs/open", body)).status, 400, JSON.stringify(body));
    }
    assert.deepEqual(server.osCalls(), []);
  });

  it("sans jeton d'appairage : 401", async () => {
    assert.equal((await server.call("POST", "/gitlab/mrs/open", { project: "grp/proj", iid: 1 }, false)).status, 401);
    assert.deepEqual(server.osCalls(), []);
  });
});

describe("/deck/features et système sans pilotage", () => {
  it("gitlab vrai avec jeton, faux sans", async () => {
    assert.equal((await server.call("GET", "/deck/features")).json.gitlab, true);
    assert.equal((await noToken.call("GET", "/deck/features")).json.gitlab, false);
  });

  it("système other : 501 partout, pas de features.gitlab", async () => {
    const features = await other.call("GET", "/deck/features");
    assert.equal(features.status, 501);
    assert.equal(features.json.gitlab, undefined);
    assert.equal((await other.call("GET", "/gitlab/mrs")).status, 501);
    assert.equal((await other.call("POST", "/gitlab/mrs/merge", { project: "grp/proj", iid: 1, sha: SHA })).status, 501);
    assert.equal((await other.call("POST", "/gitlab/mrs/open", { project: "grp/proj", iid: 1 })).status, 501);
    assert.equal(seen.length, 0);
  });
});

describe("le jeton GitLab ne sort pas", () => {
  it("ni dans une réponse, ni dans le journal du serveur", async () => {
    graphqlStatus = 401;
    mergeStatus = 403;
    // La fusion refusée passe en premier : elle vide le cache, la lecture va donc jusqu'à GitLab.
    const answers = [
      await server.call("POST", "/gitlab/mrs/merge", { project: "grp/proj", iid: 12, sha: SHA }),
      await server.call("GET", "/gitlab/mrs"),
      await server.call("GET", "/deck/features"),
      await server.call("POST", "/gitlab/mrs/open", { project: "grp/proj", iid: 12 }),
    ];
    assert.equal(answers[1].status, 502);
    for (const a of answers) assert.ok(!JSON.stringify(a.json).includes(GITLAB_TOKEN));
    assert.ok(!server.output().includes(GITLAB_TOKEN));
  });
});
