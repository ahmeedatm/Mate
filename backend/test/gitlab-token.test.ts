import assert from "node:assert/strict";
import fs from "node:fs";
import http from "node:http";
import os from "node:os";
import path from "node:path";
import { after, before, describe, it } from "node:test";
import { startFakeGitlab, type FakeGitlab } from "./fake-gitlab.ts";
import { startServer, type TestServer } from "./server.ts";

const TOKEN = "glpat-jeton-colle-de-test";
const ENV_TOKEN = "glpat-jeton-env-de-test";
const MERGE_BODY = { project: "grp/proj", iid: 12, sha: "0123456789abcdef0123456789abcdef01234567" };

let gitlab: FakeGitlab;
const servers: TestServer[] = [];
/** Compagnon neuf (dossier de données vide) relié au faux GitLab. */
const boot = async (env: Record<string, string> = {}, dataDir?: string) => {
  const server = await startServer({ dataDir, env: { GITLAB_URL: gitlab.url, ...env } });
  servers.push(server);
  return server;
};
before(async () => {
  gitlab = await startFakeGitlab();
  gitlab.tokens[TOKEN] = ["api"];
  gitlab.personalToken = ENV_TOKEN;
});
after(async () => {
  await Promise.all(servers.map((s) => s.stop()));
  await gitlab.stop();
});

const raw = (server: TestServer, route: string, init: RequestInit = {}) => fetch(server.url + route, { redirect: "manual", ...init });
/** Ce que fait la page locale : le jeton collé part en PUT, sans jeton d'appairage. */
const paste = (server: TestServer, token: unknown) =>
  raw(server, "/local/accounts/gitlab", {
    method: "PUT",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ token }),
  });
const disconnect = (server: TestServer) => raw(server, "/local/accounts/gitlab", { method: "DELETE" });
const storedFile = (server: TestServer) => path.join(server.dataDir, "gitlab.json");
const stored = (server: TestServer) => JSON.parse(fs.readFileSync(storedFile(server), "utf8"));
const graphqlCalls = () => gitlab.seen.filter((s) => s.url === "/api/graphql");
const status = async (server: TestServer) => (await server.call("GET", "/local/status", undefined, false)).json.gitlab;
/** Une fusion vide le cache de 20 s du compagnon : la lecture suivante va jusqu'à GitLab. */
const mergeToClearCache = (server: TestServer) => server.call("POST", "/gitlab/mrs/merge", MERGE_BODY);

describe("jeton GitLab : sans jeton", () => {
  let server: TestServer;
  before(async () => {
    server = await boot();
  });

  it("/gitlab/mrs répond 200 « pas connecté », GitLab n'est pas appelé, les widgets sont proposés", async () => {
    gitlab.seen.length = 0;
    const mrs = await server.call("GET", "/gitlab/mrs");
    assert.equal(mrs.status, 200);
    assert.deepEqual(mrs.json, { connected: false, expired: false, canMerge: false, mine: [], review: [] });
    assert.equal((await server.call("GET", "/deck/features")).json.gitlab, true);
    assert.equal(gitlab.seen.length, 0);
  });

  it("/gitlab/mrs reste protégé par le jeton d'appairage", async () => {
    assert.equal((await server.call("GET", "/gitlab/mrs", undefined, false)).status, 401);
  });

  it("la page voit l'adresse de GitLab, pas de compte", async () => {
    assert.deepEqual(await status(server), { url: gitlab.url, urlError: null, token: false, account: null });
  });

  it("la tablette ouvre la carte GitLab de la page de l'ordinateur (/local/open)", async () => {
    server.clearOsCalls();
    assert.equal((await server.call("POST", "/local/open", { section: "gitlab" })).status, 200);
    assert.deepEqual(server.osCalls(), [{ fn: "openTarget", args: [`${server.url}/?section=gitlab`] }]);
    assert.equal((await server.call("POST", "/local/open", { section: "autre" })).status, 400);
  });

  it("l'ancien parcours OAuth n'existe plus", async () => {
    for (const route of ["/oauth/gitlab/start", "/oauth/gitlab/callback?code=a&state=b"]) {
      const res = await raw(server, route);
      assert.ok(res.status >= 400, `${route} : ${res.status}`);
    }
  });
});

describe("jeton GitLab : coller un jeton", () => {
  let server: TestServer;
  before(async () => {
    server = await boot();
  });
  const noAccount = async () => {
    assert.equal((await status(server)).account, null);
    assert.equal(fs.existsSync(storedFile(server)), false);
  };

  it("jeton inconnu de GitLab : phrase claire, rien d'enregistré, le jeton n'est pas répété", async () => {
    const res = await paste(server, "glpat-jeton-inconnu-123");
    assert.equal(res.status, 400);
    const { error } = await res.json();
    assert.match(error, /^GitLab refuse ce jeton/);
    assert.ok(!error.includes("glpat-jeton-inconnu-123"));
    assert.ok(!server.output().includes("glpat-jeton-inconnu-123"));
    await noAccount();
  });

  it("forme impossible (vide, espaces, trop court) : refusé sans appeler GitLab", async () => {
    gitlab.seen.length = 0;
    for (const token of ["", "   ", "court", "avec des espaces dedans", "x".repeat(300)]) {
      assert.equal((await paste(server, token)).status, 400, JSON.stringify(token));
    }
    assert.equal(gitlab.seen.length, 0);
    await noAccount();
  });

  it("corps invalide : 400", async () => {
    assert.equal(
      (await raw(server, "/local/accounts/gitlab", { method: "PUT", headers: { "content-type": "application/json" }, body: "{}" })).status,
      400,
    );
    assert.equal((await paste(server, 12345678)).status, 400);
    await noAccount();
  });

  it("jeton sans la portée api ni read_api : refusé, rien d'enregistré", async () => {
    gitlab.tokens["glpat-lecture-profil-seule"] = ["read_user"];
    const res = await paste(server, "glpat-lecture-profil-seule");
    assert.equal(res.status, 400);
    assert.match((await res.json()).error, /ni la portée api ni read_api/);
    await noAccount();
  });

  it("GitLab injoignable : 502 « ne répond pas », rien d'enregistré", async () => {
    const fake = await startFakeGitlab();
    const url = fake.url;
    await fake.stop();
    const offline = await boot({ GITLAB_URL: url });
    const res = await paste(offline, TOKEN);
    assert.equal(res.status, 502);
    assert.equal((await res.json()).error, "GitLab ne répond pas");
    assert.equal(fs.existsSync(storedFile(offline)), false);
  });

  it("jeton valide : le compte s'affiche, vérifié par GET /api/v4/user en PRIVATE-TOKEN, espaces autour retirés", async () => {
    gitlab.seen.length = 0;
    const res = await paste(server, `  ${TOKEN}\n`);
    assert.equal(res.status, 200);
    assert.deepEqual(await res.json(), { ok: true, username: "camille" });
    const user = gitlab.seen.find((s) => s.url === "/api/v4/user")!;
    assert.equal(user.privateToken, TOKEN);
    assert.equal(user.authorization, undefined);
    assert.deepEqual(await status(server), {
      url: gitlab.url,
      urlError: null,
      token: false,
      account: { username: "camille", expired: false, canWrite: true },
    });
  });

  it("pris en compte tout de suite, sans redémarrage : les MR sont lues avec le jeton collé (jamais en Bearer)", async () => {
    gitlab.seen.length = 0;
    const { status: code, json } = await server.call("GET", "/gitlab/mrs");
    assert.equal(code, 200);
    assert.equal(json.connected, true);
    assert.deepEqual(
      json.mine.map((m: any) => m.iid),
      [3, 12],
    );
    const [call] = graphqlCalls();
    assert.equal(call.privateToken, TOKEN);
    assert.equal(call.authorization, undefined);
  });

  it("le jeton est dans .data/gitlab.json en 0600 ; il ne sort ni en réponse, ni dans la page, ni au journal", async () => {
    assert.deepEqual(stored(server), { token: TOKEN, username: "camille", canWrite: true });
    assert.equal(fs.statSync(storedFile(server)).mode & 0o777, 0o600);
    const answers = [
      await server.call("GET", "/gitlab/mrs"),
      await server.call("GET", "/deck/features"),
      await server.call("GET", "/local/status", undefined, false),
      await mergeToClearCache(server),
      { json: await (await raw(server, "/")).text() },
    ];
    for (const a of answers) assert.ok(!JSON.stringify(a.json).includes(TOKEN));
    assert.ok(!server.output().includes(TOKEN));
  });

  it("un autre jeton remplace le premier", async () => {
    gitlab.tokens["glpat-deuxieme-jeton-de-test"] = ["api"];
    assert.equal((await paste(server, "glpat-deuxieme-jeton-de-test")).status, 200);
    assert.equal(stored(server).token, "glpat-deuxieme-jeton-de-test");
    // Un jeton refusé ne casse pas le compte en place.
    assert.equal((await paste(server, "glpat-jeton-inconnu-123")).status, 400);
    assert.equal(stored(server).token, "glpat-deuxieme-jeton-de-test");
  });

  it("déconnexion : le compte disparaît, GitLab n'est plus appelé, le jeton est effacé", async () => {
    assert.equal((await disconnect(server)).status, 200);
    assert.deepEqual(stored(server), {});
    gitlab.seen.length = 0;
    assert.deepEqual((await server.call("GET", "/gitlab/mrs")).json, {
      connected: false,
      expired: false,
      canMerge: false,
      mine: [],
      review: [],
    });
    assert.equal((await mergeToClearCache(server)).status, 503);
    assert.equal(gitlab.seen.length, 0);
    assert.equal((await status(server)).account, null);
  });
});

describe("jeton GitLab : ancien compte OAuth", () => {
  it("un compte OAuth enregistré (jeton de renouvellement, pas de jeton collé) est ignoré : il faut coller un jeton", async () => {
    const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), "companion-gitlab-oauth-"));
    fs.writeFileSync(
      path.join(dataDir, "gitlab.json"),
      JSON.stringify({
        refreshToken: "rt-ancien",
        redirectUri: "http://127.0.0.1:3000/oauth/gitlab/callback",
        username: "camille",
        scope: "read_api",
      }),
    );
    const server = await boot({}, dataDir);
    gitlab.seen.length = 0;
    assert.equal((await status(server)).account, null);
    // L'ancien compte est effacé du disque dès la première lecture : aucun jeton de renouvellement ne traîne.
    assert.equal(fs.readFileSync(path.join(dataDir, "gitlab.json"), "utf8").includes("rt-ancien"), false);
    assert.deepEqual((await server.call("GET", "/gitlab/mrs")).json, {
      connected: false,
      expired: false,
      canMerge: false,
      mine: [],
      review: [],
    });
    assert.equal(gitlab.seen.length, 0);
    assert.equal((await paste(server, TOKEN)).status, 200);
    assert.deepEqual(stored(server), { token: TOKEN, username: "camille", canWrite: true });
    fs.rmSync(dataDir, { recursive: true, force: true });
  });
});

describe("jeton GitLab : refusé par GitLab après coup", () => {
  it("jeton révoqué ou expiré : 502 (jamais 401), puis « jeton refusé » ; en coller un nouveau rétablit", async () => {
    const server = await boot();
    const mine = "glpat-jeton-a-revoquer-123";
    gitlab.tokens[mine] = ["api"];
    await paste(server, mine);
    delete gitlab.tokens[mine];
    const refused = await server.call("GET", "/gitlab/mrs");
    assert.equal(refused.status, 502);
    assert.match(refused.json.error, /colle un nouveau jeton/);
    assert.deepEqual((await server.call("GET", "/gitlab/mrs")).json, {
      connected: false,
      expired: true,
      canMerge: false,
      mine: [],
      review: [],
    });
    assert.deepEqual((await status(server)).account, { username: "camille", expired: true, canWrite: true });
    assert.equal((await server.call("GET", "/deck/features")).json.gitlab, true);
    assert.equal((await paste(server, TOKEN)).status, 200);
    assert.equal((await server.call("GET", "/gitlab/mrs")).json.connected, true);
    assert.deepEqual((await status(server)).account, { username: "camille", expired: false, canWrite: true });
  });

  it("refus constaté à la fusion : même état", async () => {
    const server = await boot();
    const mine = "glpat-jeton-a-revoquer-456";
    gitlab.tokens[mine] = ["api"];
    await paste(server, mine);
    delete gitlab.tokens[mine];
    assert.equal((await mergeToClearCache(server)).status, 502);
    assert.equal((await server.call("GET", "/gitlab/mrs")).json.expired, true);
  });
});

describe("jeton GitLab : GITLAB_TOKEN du .env en secours", () => {
  let server: TestServer;
  before(async () => {
    server = await boot({ GITLAB_TOKEN: ENV_TOKEN });
  });

  it("sans jeton collé : le jeton .env sert (PRIVATE-TOKEN)", async () => {
    gitlab.seen.length = 0;
    assert.equal((await server.call("GET", "/gitlab/mrs")).json.connected, true);
    assert.equal(graphqlCalls()[0].privateToken, ENV_TOKEN);
  });

  it("le jeton collé prime ; après déconnexion, le jeton .env reprend", async () => {
    await paste(server, TOKEN);
    await mergeToClearCache(server);
    gitlab.seen.length = 0;
    assert.equal((await server.call("GET", "/gitlab/mrs")).json.connected, true);
    assert.equal(graphqlCalls()[0].privateToken, TOKEN);
    await disconnect(server);
    gitlab.seen.length = 0;
    assert.equal((await server.call("GET", "/gitlab/mrs")).json.connected, true);
    assert.equal(graphqlCalls()[0].privateToken, ENV_TOKEN);
  });

  it("le jeton .env ne sort pas, la page dit qu'il est utilisé", async () => {
    const page = await status(server);
    assert.equal(page.token, true);
    assert.ok(!JSON.stringify(page).includes(ENV_TOKEN));
    assert.ok(!server.output().includes(ENV_TOKEN));
  });
});

describe("jeton GitLab : portées et droit de fusion", () => {
  const readOnly = "La connexion GitLab est en lecture seule : fusionne sur l'ordinateur";
  const merges = () => gitlab.seen.filter((s) => s.method === "PUT");
  const READ_ONLY_TOKEN = "glpat-lecture-seule-de-test";

  it("jeton api : canMerge vrai, la fusion part avec le jeton collé", async () => {
    const server = await boot();
    await paste(server, TOKEN);
    assert.equal((await server.call("GET", "/gitlab/mrs")).json.canMerge, true);
    gitlab.seen.length = 0;
    assert.equal((await server.call("POST", "/gitlab/mrs/merge", MERGE_BODY)).status, 200);
    assert.equal(merges()[0].privateToken, TOKEN);
    assert.equal(merges()[0].authorization, undefined);
  });

  it("jeton read_api seul : canMerge faux, la liste marche, la fusion répond 409 sans appeler GitLab", async () => {
    gitlab.tokens[READ_ONLY_TOKEN] = ["read_api"];
    const server = await boot();
    assert.equal((await paste(server, READ_ONLY_TOKEN)).status, 200);
    assert.equal(stored(server).canWrite, false);
    assert.equal((await status(server)).account.canWrite, false);
    const list = await server.call("GET", "/gitlab/mrs");
    assert.equal(list.json.connected, true);
    assert.equal(list.json.canMerge, false);
    gitlab.seen.length = 0;
    const merge = await server.call("POST", "/gitlab/mrs/merge", MERGE_BODY);
    assert.equal(merge.status, 409);
    assert.equal(merge.json.error, readOnly);
    assert.equal(gitlab.seen.length, 0);
  });

  it("GitLab qui ne dit pas les portées du jeton : supposé api", async () => {
    gitlab.selfStatus = 404;
    try {
      const server = await boot();
      assert.equal((await paste(server, TOKEN)).status, 200);
      assert.equal(stored(server).canWrite, true);
      assert.equal((await server.call("GET", "/gitlab/mrs")).json.canMerge, true);
    } finally {
      gitlab.selfStatus = 200;
    }
  });

  it("jeton read_api + GITLAB_TOKEN : lecture avec le jeton collé, fusion avec le jeton .env", async () => {
    gitlab.tokens[READ_ONLY_TOKEN] = ["read_api"];
    const server = await boot({ GITLAB_TOKEN: ENV_TOKEN });
    await paste(server, READ_ONLY_TOKEN);
    gitlab.seen.length = 0;
    assert.equal((await server.call("GET", "/gitlab/mrs")).json.canMerge, true);
    assert.equal(graphqlCalls()[0].privateToken, READ_ONLY_TOKEN);
    assert.equal((await server.call("POST", "/gitlab/mrs/merge", MERGE_BODY)).status, 200);
    assert.equal(merges()[0].privateToken, ENV_TOKEN);
  });

  it("GITLAB_TOKEN seul : canMerge vrai (supposé api)", async () => {
    const server = await boot({ GITLAB_TOKEN: ENV_TOKEN });
    assert.equal((await server.call("GET", "/gitlab/mrs")).json.canMerge, true);
  });

  it("jeton api déconnecté + rien d'autre : canMerge faux, fusion 503", async () => {
    const server = await boot();
    await paste(server, TOKEN);
    await disconnect(server);
    assert.equal((await server.call("GET", "/gitlab/mrs")).json.canMerge, false);
    assert.equal((await server.call("POST", "/gitlab/mrs/merge", MERGE_BODY)).status, 503);
  });
});

describe("jeton GitLab : seul le jeton enregistré passe à « refusé »", () => {
  it("refus du jeton .env à la fusion (jeton collé en lecture seule) : le jeton collé reste valable", async () => {
    const readOnly = "glpat-lecture-seule-refus-env";
    gitlab.tokens[readOnly] = ["read_api"];
    const server = await boot({ GITLAB_TOKEN: "glpat-env-revoque-de-test" });
    assert.equal((await paste(server, readOnly)).status, 200);
    assert.equal((await mergeToClearCache(server)).status, 502);
    assert.equal((await status(server)).account.expired, false);
    assert.equal((await server.call("GET", "/gitlab/mrs")).json.connected, true);
  });

  it("GraphQL répond 403 : le compte passe aussi à « jeton refusé »", async () => {
    const server = await boot();
    const mine = "glpat-jeton-403-de-test";
    gitlab.tokens[mine] = ["api"];
    await paste(server, mine);
    gitlab.graphqlStatus = 403;
    try {
      assert.equal((await server.call("GET", "/gitlab/mrs")).status, 502);
    } finally {
      gitlab.graphqlStatus = 200;
    }
    assert.equal((await status(server)).account.expired, true);
  });
});

describe("jeton GitLab : adresse GITLAB_URL", () => {
  it("http:// hors boucle locale, autre schéma ou identifiants dans l'adresse : refusé avec une phrase, GitLab n'est pas appelé", async () => {
    for (const url of [
      "http://gitlab.exemple.com",
      "ftp://gitlab.exemple.com",
      "https://camille:secret@gitlab.exemple.com",
      "gitlab.exemple.com",
    ]) {
      const server = await boot({ GITLAB_URL: url, GITLAB_TOKEN: ENV_TOKEN });
      gitlab.seen.length = 0;
      const res = await paste(server, TOKEN);
      assert.equal(res.status, 400, url);
      assert.match((await res.json()).error, /GITLAB_URL/, url);
      assert.equal(fs.existsSync(storedFile(server)), false, url);
      assert.equal(gitlab.seen.length, 0, url);
      // La lecture avec le jeton .env est refusée aussi : le jeton ne part pas vers cette adresse.
      const mrs = await server.call("GET", "/gitlab/mrs");
      assert.equal(mrs.status, 502, url);
      assert.match(mrs.json.error, /GITLAB_URL/, url);
      assert.equal(gitlab.seen.length, 0, url);
      const shown = await status(server);
      assert.equal(shown.url, "", url);
      assert.match(shown.urlError, /GITLAB_URL/, url);
    }
  });

  it("http:// sur 127.0.0.1, localhost ou [::1] : accepté (l'URL du faux GitLab est en 127.0.0.1)", async () => {
    const server = await boot();
    assert.equal((await paste(server, TOKEN)).status, 200);
    assert.equal((await status(server)).url, gitlab.url);
  });
});

describe("jeton GitLab : redirections", () => {
  it("une redirection de GitLab n'est pas suivie : le jeton ne part pas ailleurs", async () => {
    let hits = 0;
    let seenToken: string | undefined;
    const target = http.createServer((req, res) => {
      hits++;
      seenToken = req.headers["private-token"] as string | undefined;
      res.end("{}");
    });
    await new Promise<void>((r) => target.listen(0, "127.0.0.1", r));
    const targetUrl = `http://127.0.0.1:${(target.address() as { port: number }).port}/vole`;
    const redirector = http.createServer((_req, res) => {
      res.statusCode = 302;
      res.setHeader("location", targetUrl);
      res.end();
    });
    await new Promise<void>((r) => redirector.listen(0, "127.0.0.1", r));
    try {
      const server = await boot({ GITLAB_URL: `http://127.0.0.1:${(redirector.address() as { port: number }).port}` });
      assert.equal((await paste(server, TOKEN)).status, 502);
      assert.equal(hits, 0);
      assert.equal(seenToken, undefined);
      // Même garde pour la lecture des MR avec le jeton .env.
      const env = await boot({
        GITLAB_URL: `http://127.0.0.1:${(redirector.address() as { port: number }).port}`,
        GITLAB_TOKEN: ENV_TOKEN,
      });
      assert.equal((await env.call("GET", "/gitlab/mrs")).status, 502);
      assert.equal(hits, 0);
    } finally {
      await new Promise((r) => target.close(r));
      await new Promise((r) => redirector.close(r));
    }
  });
});
