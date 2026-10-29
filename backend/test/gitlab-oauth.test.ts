import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { after, afterEach, before, describe, it } from "node:test";
import { FAKE_CLIENT_ID, startFakeGitlab, type FakeGitlab } from "./fake-gitlab.ts";
import { startServer, type TestServer } from "./server.ts";

const PERSONAL = "glpat-jeton-personnel-de-test";

// Fichier de config avec la portée `api` (appli OAuth qui l'autorise) ; test/accounts-vide.json n'a pas de portée : défaut read_api.
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "companion-gitlab-scope-"));
const accountsWithScope = (scope: string) => {
  const file = path.join(tmp, `accounts-${scope}.json`);
  fs.writeFileSync(file, JSON.stringify({ gitlab: { clientId: "", scope } }));
  return file;
};
const WRITE_ACCOUNTS = accountsWithScope("api");
const MERGE_BODY = { project: "grp/proj", iid: 12, sha: "0123456789abcdef0123456789abcdef01234567" };

let gitlab: FakeGitlab;
const servers: TestServer[] = [];
/** Compagnon neuf (dossier de données vide) relié au faux GitLab, qui demande la portée `api` sauf mention contraire. */
const boot = async (env: Record<string, string> = { GITLAB_CLIENT_ID: FAKE_CLIENT_ID }) => {
  const server = await startServer({ env: { GITLAB_URL: gitlab.url, ACCOUNTS_FILE: WRITE_ACCOUNTS, ...env } });
  servers.push(server);
  return server;
};
before(async () => {
  gitlab = await startFakeGitlab();
  gitlab.allowedScopes.push("api");
  gitlab.personalToken = PERSONAL;
});
after(async () => {
  await Promise.all(servers.map((s) => s.stop()));
  await gitlab.stop();
  fs.rmSync(tmp, { recursive: true, force: true });
});

const raw = (server: TestServer, route: string, init: RequestInit = {}) => fetch(server.url + route, { redirect: "manual", ...init });
const storedFile = (server: TestServer) => path.join(server.dataDir, "gitlab.json");
const stored = (server: TestServer) => JSON.parse(fs.readFileSync(storedFile(server), "utf8"));
const graphqlCalls = () => gitlab.seen.filter((s) => s.url === "/api/graphql");
const tokenCalls = (grant: string) => gitlab.seen.filter((s) => s.url === "/oauth/token" && s.body.grant_type === grant);
/** Une fusion vide le cache de 20 s du compagnon : la lecture suivante va jusqu'à GitLab. */
const mergeToClearCache = (server: TestServer) => server.call("POST", "/gitlab/mrs/merge", MERGE_BODY);

/** Parcours du navigateur : start → page d'autorisation → Autoriser → retour sur le compagnon. Rend l'adresse finale de la page locale. */
async function login(server: TestServer, { deny = false, fake = gitlab } = {}) {
  const start = await raw(server, "/oauth/gitlab/start");
  assert.equal(start.status, 302);
  const back = await fake.authorize(start.headers.get("location")!, deny);
  const callback = await raw(server, back.replace(server.url, ""));
  assert.equal(callback.status, 302);
  return callback.headers.get("location")!;
}

describe("connexion GitLab : démarrage", () => {
  let server: TestServer;
  before(async () => {
    server = await boot();
  });

  it("sans compte : /gitlab/mrs répond 200 « pas connecté », GitLab n'est pas appelé, l'onglet est proposé", async () => {
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

  it("start : redirige vers la page d'autorisation de GitLab, code + PKCE S256, portée de config/accounts.json, sans secret", async () => {
    const res = await raw(server, "/oauth/gitlab/start");
    assert.equal(res.status, 302);
    const url = new URL(res.headers.get("location")!);
    assert.equal(url.origin + url.pathname, `${gitlab.url}/oauth/authorize`);
    const q = url.searchParams;
    assert.equal(q.get("client_id"), FAKE_CLIENT_ID);
    assert.equal(q.get("response_type"), "code");
    assert.equal(q.get("scope"), "api");
    assert.equal(q.get("code_challenge_method"), "S256");
    assert.match(q.get("code_challenge")!, /^[A-Za-z0-9_-]{43}$/);
    assert.equal(q.get("redirect_uri"), `${server.url}/oauth/gitlab/callback`);
    assert.match(q.get("state")!, /^[A-Za-z0-9_-]{32}$/);
    assert.ok(!q.has("client_secret"));
  });

  it("chaque démarrage a son state et son défi PKCE", async () => {
    const [a, b] = await Promise.all([raw(server, "/oauth/gitlab/start"), raw(server, "/oauth/gitlab/start")]);
    const qa = new URL(a.headers.get("location")!).searchParams;
    const qb = new URL(b.headers.get("location")!).searchParams;
    assert.notEqual(qa.get("state"), qb.get("state"));
    assert.notEqual(qa.get("code_challenge"), qb.get("code_challenge"));
  });

  it("la tablette ouvre le démarrage de la connexion sur l'ordinateur (/local/open)", async () => {
    server.clearOsCalls();
    assert.equal((await server.call("POST", "/local/open", { section: "gitlab" })).status, 200);
    assert.deepEqual(server.osCalls(), [{ fn: "openTarget", args: [`${server.url}/oauth/gitlab/start`] }]);
    assert.equal((await server.call("POST", "/local/open", { section: "autre" })).status, 400);
  });
});

describe("connexion GitLab : retour (callback)", () => {
  let server: TestServer;
  before(async () => {
    server = await boot();
  });

  const result = (location: string) => new URL(location).searchParams.get("gitlab");
  const noAccount = async () => assert.equal((await server.call("GET", "/local/status", undefined, false)).json.gitlab.account, null);

  it("state inconnu, code absent ou erreur : retour sur la page avec un message, rien n'est enregistré", async () => {
    for (const [query, expected] of [
      ["code=abc&state=inconnu", "error"],
      ["state=abc", "error"],
      ["code=abc", "error"],
      ["error=server_error&error_description=boom", "error"],
      ["error=access_denied", "refused"],
    ]) {
      const res = await raw(server, `/oauth/gitlab/callback?${query}`);
      assert.equal(res.status, 302, query);
      assert.equal(res.headers.get("location"), `${server.url}/?section=gitlab&gitlab=${expected}`, query);
    }
    await noAccount();
    assert.equal(fs.existsSync(storedFile(server)), false);
  });

  it("autorisation refusée sur la page de GitLab : « annulée », pas de compte", async () => {
    assert.equal(result(await login(server, { deny: true })), "refused");
    await noAccount();
  });

  it("un code ne sert qu'une fois, un state aussi", async () => {
    const start = await raw(server, "/oauth/gitlab/start");
    const back = (await gitlab.authorize(start.headers.get("location")!)).replace(server.url, "");
    assert.equal(result((await raw(server, back)).headers.get("location")!), "ok");
    assert.equal(result((await raw(server, back)).headers.get("location")!), "error");
  });

  it("sans appli OAuth réglée : start répond 404", async () => {
    const bare = await boot({ GITLAB_TOKEN: "" });
    assert.equal((await raw(bare, "/oauth/gitlab/start")).status, 404);
    const status = (await bare.call("GET", "/local/status", undefined, false)).json.gitlab;
    assert.deepEqual(status, { available: false, token: false, account: null });
    assert.equal((await bare.call("GET", "/deck/features")).json.gitlab, false);
  });
});

describe("connexion GitLab : compte connecté", () => {
  let server: TestServer;
  before(async () => {
    gitlab.expiresIn = 7200;
    server = await boot();
  });

  it("connexion : jetons échangés avec le vérificateur PKCE, sans secret ; la page voit le compte", async () => {
    gitlab.seen.length = 0;
    assert.equal(await login(server), `${server.url}/?section=gitlab&gitlab=ok`);
    const [exchange] = tokenCalls("authorization_code");
    assert.equal(exchange.body.client_id, FAKE_CLIENT_ID);
    assert.equal(exchange.body.redirect_uri, `${server.url}/oauth/gitlab/callback`);
    assert.match(exchange.body.code_verifier, /^[A-Za-z0-9_-]{64}$/);
    assert.ok(!("client_secret" in exchange.body));
    // Le faux GitLab refuse un vérificateur qui ne correspond pas au défi : la réussite le prouve ; on le revérifie ici.
    const challenge = new URL(gitlab.seen.find((s) => s.url.startsWith("/oauth/authorize"))!.url, gitlab.url).searchParams.get(
      "code_challenge",
    );
    assert.equal(createHash("sha256").update(exchange.body.code_verifier).digest("base64url"), challenge);
    const status = (await server.call("GET", "/local/status", undefined, false)).json.gitlab;
    assert.deepEqual(status, { available: true, token: false, account: { username: "camille", expired: false } });
  });

  it("les MR sont lues avec le jeton d'accès en Bearer (jamais en PRIVATE-TOKEN)", async () => {
    const { status, json } = await server.call("GET", "/gitlab/mrs");
    assert.equal(status, 200);
    assert.equal(json.connected, true);
    assert.deepEqual(
      json.mine.map((m: any) => m.iid),
      [3, 12],
    );
    const [call] = graphqlCalls();
    assert.match(call.authorization!, /^Bearer at-/);
    assert.equal(call.privateToken, undefined);
  });

  it("le jeton de renouvellement est dans .data/gitlab.json en 0600, aucun jeton ne sort ni en réponse ni au journal", async () => {
    const file = stored(server);
    assert.ok(gitlab.issued.includes(file.refreshToken));
    assert.equal(fs.statSync(storedFile(server)).mode & 0o777, 0o600);
    const answers = [
      await server.call("GET", "/gitlab/mrs"),
      await server.call("GET", "/deck/features"),
      await server.call("GET", "/local/status", undefined, false),
      await mergeToClearCache(server),
      { json: await (await raw(server, "/")).text() },
    ];
    for (const token of gitlab.issued) {
      for (const a of answers) assert.ok(!JSON.stringify(a.json).includes(token));
      assert.ok(!server.output().includes(token));
    }
  });

  it("déconnexion : le compte disparaît, GitLab n'est plus appelé, le jeton est effacé", async () => {
    const del = await raw(server, "/local/accounts/gitlab", { method: "DELETE" });
    assert.equal(del.status, 200);
    assert.deepEqual(stored(server), {});
    gitlab.seen.length = 0;
    assert.deepEqual((await server.call("GET", "/gitlab/mrs")).json, {
      connected: false,
      expired: false,
      canMerge: false,
      mine: [],
      review: [],
    });
    assert.equal((await server.call("POST", "/gitlab/mrs/merge", { project: "grp/proj", iid: 1, sha: "0".repeat(40) })).status, 503);
    assert.equal(gitlab.seen.length, 0);
    assert.equal((await server.call("GET", "/local/status", undefined, false)).json.gitlab.account, null);
  });
});

describe("connexion GitLab : renouvellement", () => {
  let server: TestServer;
  before(async () => {
    // Jeton d'accès annoncé pour 60 s : déjà périmé pour le compagnon (il garde une marge d'une minute).
    gitlab.expiresIn = 60;
    server = await boot();
    await login(server);
  });
  after(() => {
    gitlab.expiresIn = 7200;
  });

  it("jeton périmé : renouvelé seul, le nouveau jeton de renouvellement (tournant) est gardé, deux fois de suite", async () => {
    gitlab.seen.length = 0;
    const first = stored(server).refreshToken;
    assert.equal((await server.call("GET", "/gitlab/mrs")).json.connected, true);
    const second = stored(server).refreshToken;
    assert.notEqual(second, first);
    await mergeToClearCache(server);
    assert.equal((await server.call("GET", "/gitlab/mrs")).json.connected, true);
    assert.notEqual(stored(server).refreshToken, second);
    const refreshes = tokenCalls("refresh_token");
    assert.ok(refreshes.length >= 2);
    assert.equal(refreshes[0].body.refresh_token, first);
    assert.equal(refreshes[1].body.refresh_token, second);
    assert.equal(refreshes[0].body.client_id, FAKE_CLIENT_ID);
    assert.equal(refreshes[0].body.redirect_uri, `${server.url}/oauth/gitlab/callback`);
    assert.ok(!("client_secret" in refreshes[0].body));
    assert.equal((await server.call("GET", "/local/status", undefined, false)).json.gitlab.account.expired, false);
  });

  it("des lectures simultanées ne renouvellent qu'une fois (le jeton de renouvellement est à usage unique)", async () => {
    await mergeToClearCache(server); // renouvelle (jeton périmé), vide le cache des MR
    gitlab.expiresIn = 7200;
    gitlab.seen.length = 0;
    const answers = await Promise.all([1, 2, 3, 4].map(() => server.call("GET", "/gitlab/mrs")));
    for (const a of answers) assert.equal(a.json.connected, true);
    assert.equal(tokenCalls("refresh_token").length, 1);
  });

  it("jeton révoqué côté GitLab : 502 (jamais 401), puis « à reconnecter » ; reconnecter rétablit", async () => {
    gitlab.revokeAll();
    const refused = await mergeToClearCache(server);
    assert.equal(refused.status, 502);
    const expired = await server.call("GET", "/gitlab/mrs");
    assert.deepEqual(expired.json, { connected: false, expired: true, canMerge: false, mine: [], review: [] });
    const status = (await server.call("GET", "/local/status", undefined, false)).json.gitlab;
    assert.deepEqual(status.account, { username: "camille", expired: true });
    assert.equal((await server.call("GET", "/deck/features")).json.gitlab, true);
    assert.equal(await login(server), `${server.url}/?section=gitlab&gitlab=ok`);
    assert.equal((await server.call("GET", "/gitlab/mrs")).json.connected, true);
    assert.equal((await server.call("GET", "/local/status", undefined, false)).json.gitlab.account.expired, false);
  });

  it("GitLab injoignable au renouvellement : 502, le compte reste connecté", async () => {
    const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), "companion-gitlab-down-"));
    const env = (fake: FakeGitlab) => ({ GITLAB_URL: fake.url, GITLAB_CLIENT_ID: FAKE_CLIENT_ID });
    const fake = await startFakeGitlab();
    fake.expiresIn = 60;
    const online = await startServer({ dataDir, env: env(fake) });
    await login(online, { fake });
    await online.stop();
    await fake.stop();
    const offline = await startServer({ dataDir, env: env(fake) });
    try {
      const answer = await offline.call("GET", "/gitlab/mrs");
      assert.equal(answer.status, 502);
      assert.equal(answer.json.error, "GitLab ne répond pas");
      assert.deepEqual(stored(offline).username, "camille");
      assert.equal((await offline.call("GET", "/local/status", undefined, false)).json.gitlab.account.expired, false);
    } finally {
      await offline.stop();
      fs.rmSync(dataDir, { recursive: true, force: true });
    }
  });
});

describe("connexion GitLab : jeton .env en secours", () => {
  let server: TestServer;
  before(async () => {
    gitlab.expiresIn = 7200;
    server = await boot({ GITLAB_CLIENT_ID: FAKE_CLIENT_ID, GITLAB_TOKEN: PERSONAL });
  });

  it("sans compte connecté : le jeton .env sert (PRIVATE-TOKEN)", async () => {
    gitlab.seen.length = 0;
    assert.equal((await server.call("GET", "/gitlab/mrs")).json.connected, true);
    const [call] = graphqlCalls();
    assert.equal(call.privateToken, PERSONAL);
    assert.equal(call.authorization, undefined);
  });

  it("le compte connecté prime ; après déconnexion, le jeton .env reprend", async () => {
    await login(server);
    await mergeToClearCache(server);
    gitlab.seen.length = 0;
    assert.equal((await server.call("GET", "/gitlab/mrs")).json.connected, true);
    assert.match(graphqlCalls()[0].authorization!, /^Bearer at-/);
    assert.equal(graphqlCalls()[0].privateToken, undefined);
    await raw(server, "/local/accounts/gitlab", { method: "DELETE" });
    gitlab.seen.length = 0;
    assert.equal((await server.call("GET", "/gitlab/mrs")).json.connected, true);
    assert.equal(graphqlCalls()[0].privateToken, PERSONAL);
  });

  it("le jeton .env ne sort pas, la page dit qu'il est utilisé", async () => {
    const status = await server.call("GET", "/local/status", undefined, false);
    assert.equal(status.json.gitlab.token, true);
    assert.ok(!JSON.stringify(status.json).includes(PERSONAL));
    assert.ok(!server.output().includes(PERSONAL));
  });
});

describe("connexion GitLab : portées et droit de fusion", () => {
  const readOnly = "La connexion GitLab est en lecture seule : fusionne sur l'ordinateur";
  const merges = () => gitlab.seen.filter((s) => s.method === "PUT");
  const authorizeScopes = () =>
    gitlab.seen.filter((s) => s.url.startsWith("/oauth/authorize")).map((s) => new URL(s.url, gitlab.url).searchParams.get("scope"));
  /** Compagnon dont l'appli OAuth n'a que read_api et dont la config ne dit rien (défaut), ou la portée donnée. */
  const readOnlyServer = async (env: Record<string, string> = {}) =>
    boot({ GITLAB_CLIENT_ID: FAKE_CLIENT_ID, ACCOUNTS_FILE: path.join(import.meta.dirname, "accounts-vide.json"), ...env });
  afterEach(() => {
    delete gitlab.grantScope;
    gitlab.allowedScopes = ["read_api", "openid", "profile", "email", "api"];
  });

  it("sans `gitlab.scope` dans la config : la portée demandée est read_api, l'autorisation passe sur une appli qui n'a que ça", async () => {
    gitlab.allowedScopes = ["read_api", "openid", "profile", "email"];
    const server = await readOnlyServer();
    gitlab.seen.length = 0;
    assert.equal(await login(server), `${server.url}/?section=gitlab&gitlab=ok`);
    assert.deepEqual(authorizeScopes(), ["read_api"]);
    assert.equal(stored(server).scope, "read_api");
  });

  it("`gitlab.scope: api` mais appli sans api : GitLab refuse la portée, aucun compte", async () => {
    gitlab.allowedScopes = ["read_api", "openid", "profile", "email"];
    const server = await boot();
    const start = await raw(server, "/oauth/gitlab/start");
    const page = await fetch(start.headers.get("location")!);
    assert.equal(page.status, 400);
    assert.match(await page.text(), /requested scope is invalid/);
    assert.equal(fs.existsSync(storedFile(server)), false);
  });

  it("compte en lecture seule : canMerge faux, la liste marche, la fusion répond 409 sans appeler GitLab", async () => {
    const server = await readOnlyServer();
    await login(server);
    const list = await server.call("GET", "/gitlab/mrs");
    assert.equal(list.json.connected, true);
    assert.equal(list.json.canMerge, false);
    gitlab.seen.length = 0;
    const merge = await server.call("POST", "/gitlab/mrs/merge", MERGE_BODY);
    assert.equal(merge.status, 409);
    assert.equal(merge.json.error, readOnly);
    assert.equal(gitlab.seen.length, 0);
  });

  it("compte avec api accordé : canMerge vrai, la fusion part avec le Bearer du compte", async () => {
    const server = await boot();
    await login(server);
    assert.equal(stored(server).scope, "api");
    assert.equal((await server.call("GET", "/gitlab/mrs")).json.canMerge, true);
    gitlab.seen.length = 0;
    assert.equal((await server.call("POST", "/gitlab/mrs/merge", MERGE_BODY)).status, 200);
    const [merge] = merges();
    assert.match(merge.authorization!, /^Bearer at-/);
    assert.equal(merge.privateToken, undefined);
  });

  it("la portée réellement accordée compte, pas celle demandée", async () => {
    gitlab.grantScope = "read_api";
    const server = await boot();
    await login(server);
    assert.equal(stored(server).scope, "read_api");
    assert.equal((await server.call("GET", "/gitlab/mrs")).json.canMerge, false);
    gitlab.seen.length = 0;
    assert.equal((await server.call("POST", "/gitlab/mrs/merge", MERGE_BODY)).status, 409);
    assert.equal(gitlab.seen.length, 0);
  });

  it("la portée accordée est mise à jour à chaque renouvellement", async () => {
    gitlab.expiresIn = 60;
    try {
      const server = await boot();
      await login(server);
      assert.equal(stored(server).scope, "api");
      gitlab.grantScope = "read_api";
      assert.equal((await server.call("GET", "/gitlab/mrs")).json.canMerge, false);
      assert.equal(stored(server).scope, "read_api");
    } finally {
      gitlab.expiresIn = 7200;
    }
  });

  it("compte en lecture seule + GITLAB_TOKEN : lecture avec le compte, fusion avec le jeton .env", async () => {
    const server = await readOnlyServer({ GITLAB_TOKEN: PERSONAL });
    await login(server);
    gitlab.seen.length = 0;
    const list = await server.call("GET", "/gitlab/mrs");
    assert.equal(list.json.canMerge, true);
    assert.match(graphqlCalls()[0].authorization!, /^Bearer at-/);
    assert.equal((await server.call("POST", "/gitlab/mrs/merge", MERGE_BODY)).status, 200);
    assert.equal(merges()[0].privateToken, PERSONAL);
    assert.equal(merges()[0].authorization, undefined);
  });

  it("GITLAB_TOKEN seul : canMerge vrai (supposé api)", async () => {
    const server = await readOnlyServer({ GITLAB_TOKEN: PERSONAL });
    assert.equal((await server.call("GET", "/gitlab/mrs")).json.canMerge, true);
  });

  it("compte api déconnecté + rien d'autre : canMerge faux, fusion 503", async () => {
    const server = await boot();
    await login(server);
    await raw(server, "/local/accounts/gitlab", { method: "DELETE" });
    assert.equal((await server.call("GET", "/gitlab/mrs")).json.canMerge, false);
    assert.equal((await server.call("POST", "/gitlab/mrs/merge", MERGE_BODY)).status, 503);
  });

  it("aucun jeton ni portée ne sort dans les réponses", async () => {
    const server = await boot();
    await login(server);
    const answers = [await server.call("GET", "/gitlab/mrs"), await server.call("GET", "/local/status", undefined, false)];
    for (const token of gitlab.issued) {
      for (const a of answers) assert.ok(!JSON.stringify(a.json).includes(token));
      assert.ok(!server.output().includes(token));
    }
  });
});
