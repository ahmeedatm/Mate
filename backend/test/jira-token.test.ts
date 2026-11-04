import assert from "node:assert/strict";
import fs from "node:fs";
import http from "node:http";
import type { AddressInfo } from "node:net";
import os from "node:os";
import path from "node:path";
import { after, before, describe, it } from "node:test";
import { normalizeJiraSite } from "../src/jira-auth.ts";
import { startServer, type TestServer } from "./server.ts";

const EMAIL = "camille@exemple.test";
const TOKEN = "ATATT3xFfGF0-jeton-colle-de-test";
const ENV_EMAIL = "env@exemple.test";
const ENV_TOKEN = "jeton-jira-du-env";

/** Faux Jira : accepte les comptes de `accounts` (e-mail → jeton), répond à `myself` et à la recherche de tickets. */
type Seen = { method: string; url: string; authorization?: string };
const seen: Seen[] = [];
const accounts: Record<string, string> = { [EMAIL]: TOKEN, [ENV_EMAIL]: ENV_TOKEN };
let answer: "jira" | "forbidden" | "page" = "jira";
const jiraServer = http.createServer((req, res) => {
  req.resume();
  req.on("end", () => {
    seen.push({ method: req.method ?? "", url: req.url ?? "", authorization: req.headers.authorization });
    const send = (status: number, body?: unknown, type = "application/json") => {
      res.writeHead(status, { "content-type": type });
      res.end(typeof body === "string" ? body : body === undefined ? undefined : JSON.stringify(body));
    };
    if (answer === "page") return send(200, "<html>Connexion Atlassian</html>", "text/html");
    if (answer === "forbidden") return send(403, {});
    const [email, token] = Buffer.from((req.headers.authorization ?? "").replace(/^Basic /, ""), "base64")
      .toString()
      .split(/:(.*)/s);
    if (accounts[email] !== token) return send(401, {});
    if (req.url === "/rest/api/3/myself") return send(200, { accountId: `acc-${email}` });
    if (req.url === "/rest/api/3/search/jql") {
      const fields = {
        summary: "Titre",
        status: { id: "10", name: "À faire", statusCategory: { key: "new" } },
        issuetype: { name: "Tâche" },
      };
      return send(200, { issues: [{ key: "SQOOL-1", fields }] });
    }
    send(404, {});
  });
});

let site = "";
let host = "";
const servers: TestServer[] = [];
const dirs: string[] = [];
const boot = async (env: Record<string, string> = {}, dataDir?: string) => {
  const server = await startServer({ dataDir, env });
  servers.push(server);
  return server;
};
const tempDir = () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "jira-auth-"));
  dirs.push(dir);
  return dir;
};
before(async () => {
  await new Promise<void>((r) => jiraServer.listen(0, "127.0.0.1", r));
  host = `127.0.0.1:${(jiraServer.address() as AddressInfo).port}`;
  site = `http://${host}`;
});
after(async () => {
  await Promise.all(servers.map((s) => s.stop()));
  for (const dir of dirs) fs.rmSync(dir, { recursive: true, force: true });
  jiraServer.close();
});

const raw = (server: TestServer, route: string, init: RequestInit = {}) => fetch(server.url + route, { redirect: "manual", ...init });
/** Ce que fait la page locale : site, e-mail et jeton collés partent en PUT, sans jeton d'appairage. */
const paste = (server: TestServer, body: unknown) =>
  raw(server, "/local/accounts/jira", { method: "PUT", headers: { "content-type": "application/json" }, body: JSON.stringify(body) });
/** Site connu seulement une fois le faux Jira à l'écoute. */
const good = () => ({ site, email: EMAIL, token: TOKEN });
const disconnect = (server: TestServer) => raw(server, "/local/accounts/jira", { method: "DELETE" });
const storedFile = (server: TestServer) => path.join(server.dataDir, "jira-auth.json");
const status = async (server: TestServer) => (await server.call("GET", "/local/status", undefined, false)).json.jira;
const asBasic = (email: string, token: string) => "Basic " + Buffer.from(`${email}:${token}`).toString("base64");

describe("jira : site saisi", () => {
  it("nom court, hôte ou adresse entière donnent la même adresse https", () => {
    for (const input of [
      "unowhy",
      "  Unowhy ",
      "unowhy.atlassian.net",
      "UNOWHY.atlassian.net/",
      "https://unowhy.atlassian.net",
      "http://unowhy.atlassian.net/jira/software/projects/SQOOL/boards/1?x=1",
    ]) {
      assert.equal(normalizeJiraSite(input), "https://unowhy.atlassian.net", JSON.stringify(input));
    }
  });

  it("seul un faux Jira local est admis en http", () => {
    assert.equal(normalizeJiraSite("http://127.0.0.1:4010/"), "http://127.0.0.1:4010");
    assert.equal(normalizeJiraSite("http://localhost:4010"), "http://localhost:4010");
    for (const input of ["http://unowhy.atlassian.net.evil.test", "http://127.0.0.1:4010/x", "http://127.0.0.1.evil.test:80"]) {
      assert.equal(normalizeJiraSite(input) === input, false, input);
    }
  });

  it("un autre hôte que Atlassian, ou vide, est refusé", () => {
    for (const input of [
      "",
      "   ",
      "jira.exemple.test",
      "unowhy.atlassian.net.evil.test",
      "u@unowhy.atlassian.net",
      "evil.test/unowhy.atlassian.net",
      "a b",
    ]) {
      assert.equal(normalizeJiraSite(input), undefined, JSON.stringify(input));
    }
  });
});

describe("jira : sans compte relié", () => {
  let server: TestServer;
  before(async () => {
    server = await boot();
  });

  it("/jira/issues répond 200 « pas connecté », Jira n'est pas appelé, le widget est proposé", async () => {
    seen.length = 0;
    const issues = await server.call("GET", "/jira/issues");
    assert.equal(issues.status, 200);
    assert.deepEqual(issues.json, { connected: false, boardsChosen: false, columns: [] });
    assert.equal((await server.call("GET", "/deck/features")).json.jira, true);
    assert.deepEqual(seen, []);
  });

  it("/jira/issues reste protégé par le jeton d'appairage", async () => {
    assert.equal((await server.call("GET", "/jira/issues", undefined, false)).status, 401);
  });

  it("les autres routes Jira répondent 501", async () => {
    for (const [method, route, body] of [
      ["GET", "/jira/boards"],
      ["POST", "/jira/boards", { ids: [1] }],
      ["GET", "/jira/columns"],
      ["GET", "/jira/issues/SQOOL-1/transitions"],
      ["POST", "/jira/issues/SQOOL-1/open"],
    ] as const) {
      assert.equal((await server.call(method, route, body)).status, 501, `${method} ${route}`);
    }
  });

  it("la page voit « non relié » ; un plugin Jira coupé retire quand même le widget", async () => {
    assert.deepEqual(await status(server), { configured: false, site: null });
    assert.equal((await server.call("POST", "/plugins", { choices: { jira: false } })).status, 200);
    const features = (await server.call("GET", "/deck/features")).json;
    assert.equal(features.jira, false);
    assert.ok(features.off.includes("jira"));
    await server.call("POST", "/plugins", { choices: { jira: true } });
    assert.equal((await server.call("GET", "/deck/features")).json.jira, true);
  });

  it("la tablette ouvre la carte Jira de la page de l'ordinateur (/local/open)", async () => {
    server.clearOsCalls();
    assert.equal((await server.call("POST", "/local/open", { section: "jira" })).status, 200);
    assert.deepEqual(server.osCalls(), [{ fn: "openTarget", args: [`${server.url}/?section=jira`] }]);
  });
});

describe("jira : coller site, e-mail et jeton", () => {
  let server: TestServer;
  before(async () => {
    server = await boot();
  });
  const noAccount = async () => {
    assert.deepEqual(await status(server), { configured: false, site: null });
    assert.equal(fs.existsSync(storedFile(server)), false);
  };

  it("identifiants refusés par Jira (401) : phrase claire, rien d'enregistré, ni jeton ni e-mail répétés", async () => {
    const res = await paste(server, { ...good(), token: "jeton-inconnu-123456" });
    assert.equal(res.status, 400);
    const { error } = await res.json();
    assert.match(error, /^Jira refuse ces identifiants/);
    assert.ok(!error.includes("jeton-inconnu-123456") && !error.includes(EMAIL));
    assert.ok(!server.output().includes("jeton-inconnu-123456"));
    await noAccount();
  });

  it("Jira répond 403 : même refus 400", async () => {
    answer = "forbidden";
    const res = await paste(server, good());
    answer = "jira";
    assert.equal(res.status, 400);
    assert.match((await res.json()).error, /^Jira refuse ces identifiants/);
    await noAccount();
  });

  it("site qui n'est pas Jira (page HTML) : 502, rien d'enregistré", async () => {
    answer = "page";
    const res = await paste(server, good());
    answer = "jira";
    assert.equal(res.status, 502);
    assert.match((await res.json()).error, /^Jira ne répond pas/);
    await noAccount();
  });

  it("site, e-mail ou jeton de forme impossible : refusé sans appeler Jira", async () => {
    seen.length = 0;
    for (const body of [
      { ...good(), site: "" },
      { ...good(), site: "jira.exemple.test" },
      { ...good(), site: "http://unowhy.atlassian.net.evil.test" },
      { ...good(), email: "" },
      { ...good(), email: "pas un mail" },
      { ...good(), token: "" },
      { ...good(), token: "court" },
      { ...good(), token: "avec des espaces dedans" },
    ]) {
      assert.equal((await paste(server, body)).status, 400, JSON.stringify(body));
    }
    assert.deepEqual(seen, []);
    await noAccount();
  });

  it("corps invalide (champ absent, trop long, mauvais type) : 400", async () => {
    for (const body of [{}, { site, email: EMAIL }, { ...good(), token: "x".repeat(501) }, { ...good(), token: 12345678 }]) {
      assert.equal((await paste(server, body)).status, 400, JSON.stringify(body));
    }
    await noAccount();
  });

  it("Jira injoignable : 502 « ne répond pas », rien d'enregistré", async () => {
    const res = await paste(server, { ...good(), site: "http://127.0.0.1:1" });
    assert.equal(res.status, 502);
    assert.match((await res.json()).error, /^Jira ne répond pas/);
    await noAccount();
  });

  it("identifiants valides : vérifiés par GET /rest/api/3/myself en Basic, espaces autour retirés", async () => {
    seen.length = 0;
    const res = await paste(server, { site: `  ${site}/ `, email: `  ${EMAIL} `, token: `${TOKEN}\n` });
    assert.equal(res.status, 200);
    assert.deepEqual(await res.json(), { ok: true, site: host });
    assert.deepEqual(seen, [{ method: "GET", url: "/rest/api/3/myself", authorization: asBasic(EMAIL, TOKEN) }]);
    assert.deepEqual(await status(server), { configured: true, site: host, source: "page" });
  });

  it("pris en compte tout de suite, sans redémarrage : tickets lus avec les identifiants collés", async () => {
    seen.length = 0;
    const { status: code, json } = await server.call("GET", "/jira/issues");
    assert.equal(code, 200);
    assert.equal(json.connected, true);
    assert.deepEqual(
      json.columns.map((c: any) => [c.title, c.issues.map((i: any) => i.key)]),
      [["À faire", ["SQOOL-1"]]],
    );
    assert.ok(seen.length > 0 && seen.every((s) => s.authorization === asBasic(EMAIL, TOKEN)));
    assert.equal((await server.call("GET", "/deck/features")).json.jira, true);
  });

  it("le trio est dans .data/jira-auth.json en 0600, à part de jira.json ; rien n'en sort (réponse, page, journal)", async () => {
    assert.deepEqual(JSON.parse(fs.readFileSync(storedFile(server), "utf8")), { site, email: EMAIL, token: TOKEN });
    assert.equal(fs.statSync(storedFile(server)).mode & 0o777, 0o600);
    assert.equal(fs.existsSync(path.join(server.dataDir, "jira.json")), false);
    const answers = [
      await server.call("GET", "/jira/issues"),
      await server.call("GET", "/deck/features"),
      await server.call("GET", "/local/status", undefined, false),
      { json: await (await raw(server, "/")).text() },
    ];
    for (const a of answers) {
      const text = JSON.stringify(a.json);
      assert.ok(!text.includes(TOKEN) && !text.includes(EMAIL), text.slice(0, 80));
    }
    assert.ok(!server.output().includes(TOKEN) && !server.output().includes(EMAIL));
  });

  it("des identifiants refusés ne cassent pas le compte en place", async () => {
    assert.equal((await paste(server, { ...good(), token: "jeton-inconnu-123456" })).status, 400);
    assert.equal(JSON.parse(fs.readFileSync(storedFile(server), "utf8")).token, TOKEN);
  });

  it("un autre compte remplace le premier, tickets relus avec lui", async () => {
    assert.equal((await paste(server, { site, email: ENV_EMAIL, token: ENV_TOKEN })).status, 200);
    assert.equal(JSON.parse(fs.readFileSync(storedFile(server), "utf8")).email, ENV_EMAIL);
    seen.length = 0;
    assert.equal((await server.call("GET", "/jira/issues")).json.connected, true);
    assert.ok(seen.length > 0 && seen.every((s) => s.authorization === asBasic(ENV_EMAIL, ENV_TOKEN)));
  });

  it("déconnexion : le trio est effacé, Jira n'est plus appelé", async () => {
    assert.equal((await disconnect(server)).status, 200);
    await noAccount();
    seen.length = 0;
    assert.deepEqual((await server.call("GET", "/jira/issues")).json, { connected: false, boardsChosen: false, columns: [] });
    assert.deepEqual(seen, []);
  });

  it("déconnecter sans compte relié : 200", async () => {
    assert.equal((await disconnect(server)).status, 200);
  });
});

describe("jira : route réservée à la page locale", () => {
  it("sans la page locale (autre nom d'hôte, DNS rebinding), l'enregistrement et la déconnexion demandent le jeton d'appairage", async () => {
    const server = await boot();
    const viaHost = (method: string, body?: string) =>
      new Promise<number>((resolve, reject) => {
        const req = http.request(
          server.url + "/local/accounts/jira",
          { method, headers: { host: "evil.test", "content-type": "application/json" } },
          (res) => {
            res.resume();
            resolve(res.statusCode ?? 0);
          },
        );
        req.on("error", reject);
        req.end(body);
      });
    assert.equal(await viaHost("PUT", JSON.stringify(good())), 401);
    assert.equal(await viaHost("DELETE"), 401);
    assert.deepEqual(await status(server), { configured: false, site: null });
  });
});

describe("jira : compte gardé et .env de secours", () => {
  it("le compte collé survit à un redémarrage du compagnon", async () => {
    const dir = tempDir();
    const first = await boot({}, dir);
    assert.equal((await paste(first, good())).status, 200);
    await first.stop();
    const again = await boot({}, dir);
    assert.deepEqual(await status(again), { configured: true, site: host, source: "page" });
    assert.equal((await again.call("GET", "/jira/issues")).json.connected, true);
  });

  it("fichier abîmé : Jira n'est pas relié, le compagnon démarre", async () => {
    const dir = tempDir();
    fs.writeFileSync(path.join(dir, "jira-auth.json"), "{pas du json");
    const server = await boot({}, dir);
    assert.deepEqual(await status(server), { configured: false, site: null });
  });

  it(".env en secours : dit « .env » et reste ; les identifiants collés priment, la déconnexion rend le .env", async () => {
    const server = await boot({ JIRA_SITE: site, JIRA_EMAIL: ENV_EMAIL, JIRA_API_TOKEN: ENV_TOKEN });
    assert.deepEqual(await status(server), { configured: true, site: host, source: "env" });
    assert.equal((await server.call("GET", "/jira/issues")).json.connected, true);

    // Déconnecter ne touche pas au .env.
    assert.equal((await disconnect(server)).status, 200);
    assert.equal((await status(server)).source, "env");

    seen.length = 0;
    assert.equal((await paste(server, good())).status, 200);
    assert.equal((await status(server)).source, "page");
    seen.length = 0;
    await server.call("GET", "/jira/issues");
    assert.ok(seen.length > 0 && seen.every((s) => s.authorization === asBasic(EMAIL, TOKEN)));

    assert.equal((await disconnect(server)).status, 200);
    assert.equal((await status(server)).source, "env");
    assert.ok(!JSON.stringify(await status(server)).includes(ENV_TOKEN));
  });
});
