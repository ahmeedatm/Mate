import assert from "node:assert/strict";
import http from "node:http";
import type { AddressInfo } from "node:net";
import { after, before, describe, it } from "node:test";
import { groupIssues } from "../src/jira.ts";
import { startServer, type TestServer } from "./server.ts";

const EMAIL = "moi@exemple.test";
const API_TOKEN = "jeton-jira";

const issue = (key: string, status: string, category: string, extra: Record<string, unknown> = {}) => ({
  key,
  fields: {
    summary: `Titre ${key}`,
    status: { name: status, statusCategory: { key: category } },
    issuetype: { name: "Tâche" },
    priority: { name: "High" },
    ...extra,
  },
});

describe("jira : groupement en colonnes", () => {
  it("à faire d'abord, puis en cours, alphabétique dans la catégorie, ordre de Jira dans la colonne", () => {
    const columns = groupIssues([
      issue("A-1", "Review", "indeterminate"),
      issue("A-2", "En cours", "indeterminate"),
      issue("A-3", "À faire", "new"),
      issue("A-4", "Review", "indeterminate"),
      issue("A-5", "Étrange", "autre"),
      issue("A-6", "Backlog", "new", { issuetype: null, priority: null }),
    ]);
    assert.deepEqual(
      columns.map((c) => [c.status, c.category, c.issues.map((i) => i.key)]),
      [
        ["À faire", "new", ["A-3"]],
        ["Backlog", "new", ["A-6"]],
        ["En cours", "indeterminate", ["A-2"]],
        ["Review", "indeterminate", ["A-1", "A-4"]],
        ["Étrange", "autre", ["A-5"]],
      ],
    );
    assert.deepEqual(columns[1].issues[0], { key: "A-6", summary: "Titre A-6", type: null, priority: null });
    assert.deepEqual(columns[0].issues[0], { key: "A-3", summary: "Titre A-3", type: "Tâche", priority: "High" });
  });
});

/** Faux Jira : vérifie l'en-tête Basic, garde les requêtes reçues. */
type Seen = { method: string; url: string; body: any };
const seen: Seen[] = [];
let authFails = false;
let transitionStatus = 204;
let issueStatus = 404;
const jiraServer = http.createServer((req, res) => {
  let raw = "";
  req.on("data", (d) => (raw += d));
  req.on("end", () => {
    const send = (status: number, body?: unknown) => {
      res.writeHead(status, { "content-type": "application/json" });
      res.end(body === undefined ? undefined : JSON.stringify(body));
    };
    const expected = "Basic " + Buffer.from(`${EMAIL}:${API_TOKEN}`).toString("base64");
    if (authFails || req.headers.authorization !== expected) return send(401, {});
    const url = req.url ?? "";
    seen.push({ method: req.method ?? "", url, body: raw ? JSON.parse(raw) : undefined });
    if (req.method === "POST" && url === "/rest/api/3/search/jql") {
      return send(200, {
        issues: [
          issue("SQOOL-2", "En cours", "indeterminate"),
          issue("SQOOL-1", "À faire", "new"),
          issue("SQOOL-3", "En cours", "indeterminate"),
        ],
      });
    }
    if (url.startsWith("/rest/api/3/issue/SQOOL-1?")) return send(200, { fields: { status: { id: "10", name: "À faire" } } });
    if (url.startsWith("/rest/api/3/issue/SQOOL-404")) return send(issueStatus, {});
    if (url.startsWith("/rest/api/3/issue/SQOOL-1/transitions?")) {
      return send(200, {
        transitions: [
          { id: "11", name: "Démarrer", to: { id: "11", name: "En cours" }, isAvailable: true, fields: { x: { required: false } } },
          {
            id: "12",
            name: "Terminer",
            to: { id: "12", name: "Terminé" },
            fields: { resolution: { required: true, hasDefaultValue: false } },
          },
          {
            id: "13",
            name: "Terminer par défaut",
            to: { id: "13", name: "Fini" },
            fields: { resolution: { required: true, hasDefaultValue: true } },
          },
          { id: "14", name: "Reboucler", to: { id: "10", name: "À faire" } },
          { id: "15", name: "Interdit", to: { id: "15", name: "Interdit" }, isAvailable: false },
        ],
      });
    }
    if (req.method === "POST" && url === "/rest/api/3/issue/SQOOL-1/transitions") return send(transitionStatus);
    send(500, {});
  });
});

let site = "";
let server: TestServer;
let bare: TestServer;
before(async () => {
  await new Promise<void>((r) => jiraServer.listen(0, "127.0.0.1", r));
  site = `http://127.0.0.1:${(jiraServer.address() as AddressInfo).port}`;
  server = await startServer({ env: { JIRA_SITE: site, JIRA_EMAIL: EMAIL, JIRA_API_TOKEN: API_TOKEN } });
  bare = await startServer({ env: { JIRA_SITE: "", JIRA_EMAIL: "", JIRA_API_TOKEN: "" } });
});
after(async () => {
  await server?.stop();
  await bare?.stop();
  jiraServer.close();
});

describe("jira : API", () => {
  it("/deck/features annonce jira seulement si configuré", async () => {
    assert.equal((await server.call("GET", "/deck/features")).json.jira, true);
    assert.equal((await bare.call("GET", "/deck/features")).json.jira, false);
  });

  it("GET /jira/issues : colonnes groupées et triées, requête JQL attendue", async () => {
    seen.length = 0;
    const { status, json } = await server.call("GET", "/jira/issues");
    assert.equal(status, 200);
    assert.deepEqual(
      json.columns.map((c: any) => [c.status, c.category, c.issues.map((i: any) => i.key)]),
      [
        ["À faire", "new", ["SQOOL-1"]],
        ["En cours", "indeterminate", ["SQOOL-2", "SQOOL-3"]],
      ],
    );
    assert.deepEqual(json.columns[0].issues[0], { key: "SQOOL-1", summary: "Titre SQOOL-1", type: "Tâche", priority: "High" });
    assert.deepEqual(seen[0].body, {
      jql: "assignee = currentUser() AND statusCategory != Done ORDER BY updated DESC",
      fields: ["summary", "status", "issuetype", "priority"],
      maxResults: 100,
    });
  });

  it("GET transitions : needsJira, statut courant et transitions indisponibles exclus", async () => {
    const { status, json } = await server.call("GET", "/jira/issues/SQOOL-1/transitions");
    assert.equal(status, 200);
    assert.deepEqual(json.transitions, [
      { id: "11", name: "Démarrer", to: "En cours", needsJira: false },
      { id: "12", name: "Terminer", to: "Terminé", needsJira: true },
      { id: "13", name: "Terminer par défaut", to: "Fini", needsJira: false },
    ]);
  });

  it("POST transition : envoie { transition: { id } } à Jira", async () => {
    seen.length = 0;
    transitionStatus = 204;
    const res = await server.call("POST", "/jira/issues/SQOOL-1/transitions", { id: "11" });
    assert.deepEqual([res.status, res.json], [200, { ok: true }]);
    const post = seen.find((r) => r.method === "POST");
    assert.deepEqual(post?.body, { transition: { id: "11" } });
  });

  it("Jira répond 400 : 409 « À finir dans Jira »", async () => {
    transitionStatus = 400;
    const res = await server.call("POST", "/jira/issues/SQOOL-1/transitions", { id: "12" });
    transitionStatus = 204;
    assert.equal(res.status, 409);
    assert.match(res.json.error, /^À finir dans Jira/);
  });

  it("ticket inconnu : message en français", async () => {
    const res = await server.call("GET", "/jira/issues/SQOOL-404/transitions");
    assert.equal(res.status, 500);
    assert.equal(res.json.message, "Ticket introuvable dans Jira.");
  });

  it("identifiants refusés : message en français", async () => {
    authFails = true;
    const res = await server.call("GET", "/jira/issues");
    authFails = false;
    assert.equal(res.status, 500);
    assert.equal(res.json.message, "Jira refuse les identifiants du fichier .env de l'ordinateur.");
  });

  it("Jira injoignable : message en français, pas l'erreur réseau brute", async () => {
    const offline = await startServer({ env: { JIRA_SITE: "http://127.0.0.1:1", JIRA_EMAIL: EMAIL, JIRA_API_TOKEN: API_TOKEN } });
    try {
      const res = await offline.call("GET", "/jira/issues");
      assert.equal(res.status, 500);
      assert.equal(res.json.message, "Jira ne répond pas : vérifie la connexion de l'ordinateur.");
    } finally {
      await offline.stop();
    }
  });

  it("clé ou identifiant de transition invalide : 400, Jira n'est pas appelé", async () => {
    seen.length = 0;
    assert.equal((await server.call("GET", "/jira/issues/sqool-1/transitions")).status, 400);
    assert.equal((await server.call("GET", "/jira/issues/SQOOL-1;x/transitions")).status, 400);
    assert.equal((await server.call("POST", "/jira/issues/SQOOL-1/transitions", { id: "abc" })).status, 400);
    assert.equal((await server.call("POST", "/jira/issues/SQOOL-1/transitions", { id: "" })).status, 400);
    assert.equal((await server.call("POST", "/jira/issues/SQOOL-1/transitions", {})).status, 400);
    assert.deepEqual(seen, []);
  });

  it("POST open : ouvre la page du ticket sur l'ordinateur", async () => {
    server.clearOsCalls();
    const res = await server.call("POST", "/jira/issues/SQOOL-1/open");
    assert.deepEqual([res.status, res.json], [200, { ok: true }]);
    assert.deepEqual(server.osCalls(), [{ fn: "openTarget", args: [`${site}/browse/SQOOL-1`] }]);
  });

  it("Jira non configuré : 501 sur toutes les routes", async () => {
    const message = { error: "Jira n'est pas configuré sur l'ordinateur." };
    for (const [method, route, body] of [
      ["GET", "/jira/issues"],
      ["GET", "/jira/issues/SQOOL-1/transitions"],
      ["POST", "/jira/issues/SQOOL-1/transitions", { id: "11" }],
      ["POST", "/jira/issues/SQOOL-1/open"],
    ] as const) {
      const res = await bare.call(method, route, body);
      assert.deepEqual([res.status, res.json], [501, message], `${method} ${route}`);
    }
  });

  it("les routes demandent le jeton", async () => {
    assert.equal((await server.call("GET", "/jira/issues", undefined, false)).status, 401);
  });
});
