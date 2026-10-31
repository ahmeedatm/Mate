import assert from "node:assert/strict";
import fs from "node:fs";
import http from "node:http";
import type { AddressInfo } from "node:net";
import path from "node:path";
import { after, before, describe, it } from "node:test";
import { columnKey, groupIssues, mergeBoardColumns, type BoardColumn } from "../src/jira.ts";
import { startServer, type TestServer } from "./server.ts";

const EMAIL = "moi@exemple.test";
const API_TOKEN = "jeton-jira";

const STATUS_IDS: Record<string, string> = { "À faire": "10", "En cours": "11", Terminé: "12", Bizarre: "99" };

const issue = (key: string, status: string, category: string, extra: Record<string, unknown> = {}) => ({
  key,
  fields: {
    summary: `Titre ${key}`,
    status: { id: STATUS_IDS[status] ?? "0", name: status, statusCategory: { key: category } },
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
      columns.map((c) => [c.title, c.issues.map((i) => i.key)]),
      [
        ["À faire", ["A-3"]],
        ["Backlog", ["A-6"]],
        ["En cours", ["A-2"]],
        ["Review", ["A-1", "A-4"]],
        ["Étrange", ["A-5"]],
      ],
    );
    assert.deepEqual(columns[1].issues[0], { key: "A-6", summary: "Titre A-6", type: null, priority: null, done: false });
    assert.deepEqual(columns[0].issues[0], { key: "A-3", summary: "Titre A-3", type: "Tâche", priority: "High", done: false });
  });
});

const cols = (...names: string[]): BoardColumn[] => names.map((name, i) => ({ name, statusIds: [String(i)] }));
const QA_COLUMNS = cols("To Do", "In Progress", "BLOCKED/Stand By", "Done", "REJECTED", "En attente de réponse");
const TECH_COLUMNS = cols("Backlog", "TO DO", "IN PROGRESS", "BLOCKED / Standing by", "IN REVIEW", "READY / GO QA", "IN QA", "DONE");

describe("jira : clé de fusion et union des colonnes", () => {
  it("columnKey : mot-clé, sinon nom normalisé", () => {
    const keys: [string, string][] = [
      ["BLOCKED/Stand By", "blocked"],
      ["BLOCKED / Standing by", "blocked"],
      ["Bloqué", "blocked"],
      ["To Do", "todo"],
      ["TODO", "todo"],
      ["À faire", "todo"],
      ["In Progress", "progress"],
      ["En cours", "progress"],
      ["IN REVIEW", "review"],
      ["Revue", "review"],
      ["READY / GO QA", "goqa"],
      ["IN QA", "inqa"],
      ["Done", "done"],
      ["Terminé", "done"],
      ["Backlog", "backlog"],
      ["REJECTED", "rejected"],
      ["En attente de réponse", "en attente de reponse"],
      ["  Étape  n°2 ", "etape n 2"],
    ];
    for (const [name, key] of keys) assert.equal(columnKey(name), key, name);
  });

  it("mergeBoardColumns : TECH puis QA, fusion par mot-clé, colonnes QA insérées après Done", () => {
    const merged = mergeBoardColumns([TECH_COLUMNS, QA_COLUMNS]);
    assert.deepEqual(
      merged.map((c) => c.title),
      [
        "Backlog",
        "TO DO",
        "IN PROGRESS",
        "BLOCKED / Standing by",
        "IN REVIEW",
        "READY / GO QA",
        "IN QA",
        "DONE",
        "REJECTED",
        "En attente de réponse",
      ],
    );
  });

  it("mergeBoardColumns : les statuts des colonnes fusionnées sont réunis sans doublon", () => {
    const merged = mergeBoardColumns([[{ name: "To Do", statusIds: ["1", "2"] }], [{ name: "À faire", statusIds: ["2", "3"] }]]);
    assert.deepEqual(merged, [{ title: "To Do", statusIds: ["1", "2", "3"] }]);
  });

  it("mergeBoardColumns : QA en premier, les colonnes TECH s'insèrent à leur place", () => {
    const merged = mergeBoardColumns([QA_COLUMNS, TECH_COLUMNS]);
    assert.deepEqual(
      merged.map((c) => c.title),
      [
        "Backlog",
        "To Do",
        "In Progress",
        "BLOCKED/Stand By",
        "IN REVIEW",
        "READY / GO QA",
        "IN QA",
        "Done",
        "REJECTED",
        "En attente de réponse",
      ],
    );
  });

  it("mergeBoardColumns : un seul tableau, aucun, et deux colonnes d'un même tableau ne fusionnent pas", () => {
    assert.deepEqual(mergeBoardColumns([]), []);
    assert.deepEqual(
      mergeBoardColumns([cols("Code Review", "QA Review")]).map((c) => c.title),
      ["Code Review", "QA Review"],
    );
  });
});

/** Faux Jira : vérifie l'en-tête Basic, garde les requêtes reçues. */
type Seen = { method: string; url: string; body: any };
const seen: Seen[] = [];
let authFails = false;
let transitionStatus = 204;
let issueStatus = 404;
let withOutsider = false;
const BOARD_PAGES: Record<string, { id: number; name: string }[]> = {
  TECH: [
    { id: 1, name: "TECH Kanban" },
    { id: 2, name: "TECH Scrum" },
    { id: 3, name: "QA" },
  ],
  QA: [
    { id: 3, name: "QA" },
    { id: 4, name: "QA bis" },
  ],
};
const col = (name: string, ...ids: number[]) => ({ name, statuses: ids.map((id) => ({ id: String(id), self: "x" })) });
const BOARD_CONFIGS: Record<string, unknown[]> = {
  1: [
    col("Backlog", 20),
    col("TO DO", 10),
    col("IN PROGRESS", 11),
    col("BLOCKED / Standing by", 21),
    col("IN REVIEW", 22),
    col("READY / GO QA", 23),
    col("IN QA", 24),
    col("DONE", 12),
  ],
  3: [
    col("To Do", 10),
    col("In Progress", 11),
    col("BLOCKED/Stand By", 21),
    col("Done", 12, 40),
    col("REJECTED", 41),
    col("En attente de réponse", 42),
  ],
};
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
      if (JSON.parse(raw).jql.includes("statusCategory = Done")) {
        return send(200, {
          issues: [
            issue("TECH-9", "Terminé", "done"),
            {
              ...issue("QA-5", "Done", "done"),
              fields: { ...issue("QA-5", "Done", "done").fields, status: { id: "40", name: "Done", statusCategory: { key: "done" } } },
            },
          ],
        });
      }
      return send(200, {
        issues: [
          issue("TECH-2", "En cours", "indeterminate"),
          issue("TECH-1", "À faire", "new"),
          issue("TECH-3", "En cours", "indeterminate"),
          ...(withOutsider ? [issue("TECH-4", "Bizarre", "indeterminate")] : []),
        ],
      });
    }
    const boardList = url.match(/^\/rest\/agile\/1\.0\/board\?projectKeyOrId=([A-Z]+)&startAt=(\d+)&maxResults=50$/);
    if (boardList) {
      const all = BOARD_PAGES[boardList[1]] ?? [];
      const startAt = Number(boardList[2]);
      return send(200, { values: all.slice(startAt, startAt + 2), startAt, isLast: startAt + 2 >= all.length });
    }
    const boardConfig = url.match(/^\/rest\/agile\/1\.0\/board\/(\d+)\/configuration$/);
    if (boardConfig) {
      const columns = BOARD_CONFIGS[boardConfig[1]];
      return columns ? send(200, { columnConfig: { columns } }) : send(404, {});
    }
    if (url.startsWith("/rest/api/3/issue/TECH-1?")) return send(200, { fields: { status: { id: "10", name: "À faire" } } });
    if (url.startsWith("/rest/api/3/issue/TECH-404")) return send(issueStatus, {});
    if (url.startsWith("/rest/api/3/issue/TECH-1/transitions?")) {
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
    if (req.method === "POST" && url === "/rest/api/3/issue/TECH-1/transitions") return send(transitionStatus);
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
  it("/local/status dit si Jira est relié, avec l'hôte seulement (ni e-mail ni jeton)", async () => {
    const on = await server.call("GET", "/local/status", undefined, false);
    assert.deepEqual(on.json.jira, { configured: true, site: new URL(site).host });
    assert.ok(!JSON.stringify(on.json).includes(API_TOKEN));
    const off = await bare.call("GET", "/local/status", undefined, false);
    assert.deepEqual(off.json.jira, { configured: false, site: null });
  });

  it("/deck/features annonce jira seulement si configuré", async () => {
    assert.equal((await server.call("GET", "/deck/features")).json.jira, true);
    assert.equal((await bare.call("GET", "/deck/features")).json.jira, false);
  });

  it("GET /jira/issues : colonnes groupées et triées, requête JQL attendue", async () => {
    seen.length = 0;
    const { status, json } = await server.call("GET", "/jira/issues");
    assert.equal(status, 200);
    assert.equal(json.boardsChosen, false);
    assert.deepEqual(
      json.columns.map((c: any) => [c.title, c.issues.map((i: any) => i.key)]),
      [
        ["À faire", ["TECH-1"]],
        ["En cours", ["TECH-2", "TECH-3"]],
      ],
    );
    assert.deepEqual(json.columns[0].issues[0], { key: "TECH-1", summary: "Titre TECH-1", type: "Tâche", priority: "High", done: false });
    assert.equal(seen.length, 1, "pas de requête sur les terminés sans tableau choisi");
    assert.deepEqual(seen[0].body, {
      jql: "assignee = currentUser() AND statusCategory != Done ORDER BY updated DESC",
      fields: ["summary", "status", "issuetype", "priority"],
      maxResults: 100,
    });
  });

  it("GET /jira/boards : tableaux des projets de mes tickets, paginés et dédoublonnés, aucune sélection au départ", async () => {
    const { status, json } = await server.call("GET", "/jira/boards");
    assert.equal(status, 200);
    assert.deepEqual(json, {
      boards: [
        { id: 1, name: "TECH Kanban", project: "TECH" },
        { id: 2, name: "TECH Scrum", project: "TECH" },
        { id: 3, name: "QA", project: "TECH" },
        { id: 4, name: "QA bis", project: "QA" },
      ],
      selected: [],
    });
  });

  it("POST /jira/boards : sélection gardée dans .data/jira.json et relue", async () => {
    assert.deepEqual((await server.call("POST", "/jira/boards", { ids: [3, 1, 3] })).json, { ok: true });
    assert.deepEqual(JSON.parse(fs.readFileSync(path.join(server.dataDir, "jira.json"), "utf8")), { boards: [3, 1] });
    assert.deepEqual((await server.call("GET", "/jira/boards")).json.selected, [3, 1]);
  });

  it("POST /jira/boards : 400 sur des ids invalides, la sélection reste", async () => {
    for (const body of [
      {},
      { ids: [0] },
      { ids: [-2] },
      { ids: [1.5] },
      { ids: ["a"] },
      { ids: [null] },
      { ids: Array.from({ length: 11 }, (_, i) => i + 1) },
    ]) {
      assert.equal((await server.call("POST", "/jira/boards", body)).status, 400, JSON.stringify(body));
    }
    assert.deepEqual((await server.call("GET", "/jira/boards")).json.selected, [3, 1]);
  });

  it("GET /jira/issues avec tableaux : union des colonnes (vides comprises), tickets placés par id de statut, terminés marqués", async () => {
    assert.equal((await server.call("POST", "/jira/boards", { ids: [1, 3] })).status, 200);
    seen.length = 0;
    const { status, json } = await server.call("GET", "/jira/issues");
    assert.equal(status, 200);
    assert.equal(json.boardsChosen, true);
    assert.deepEqual(
      json.columns.map((c: any) => [c.title, c.issues.map((i: any) => [i.key, i.done])]),
      [
        ["Backlog", []],
        ["TO DO", [["TECH-1", false]]],
        [
          "IN PROGRESS",
          [
            ["TECH-2", false],
            ["TECH-3", false],
          ],
        ],
        ["BLOCKED / Standing by", []],
        ["IN REVIEW", []],
        ["READY / GO QA", []],
        ["IN QA", []],
        [
          "DONE",
          [
            ["TECH-9", true],
            ["QA-5", true],
          ],
        ],
        ["REJECTED", []],
        ["En attente de réponse", []],
      ],
    );
    assert.deepEqual(json.columns[1].issues[0], { key: "TECH-1", summary: "Titre TECH-1", type: "Tâche", priority: "High", done: false });
    const searches = seen.filter((r) => r.url === "/rest/api/3/search/jql").map((r) => r.body.jql);
    assert.equal(searches.length, 2);
    assert.ok(searches.includes("assignee = currentUser() AND statusCategory != Done ORDER BY updated DESC"));
    assert.ok(
      searches.includes("assignee = currentUser() AND statusCategory = Done AND statusCategoryChangedDate >= -7d ORDER BY updated DESC"),
    );
  });

  it("les configurations de tableaux sont gardées (pas de nouvelle requête)", async () => {
    seen.length = 0;
    await server.call("GET", "/jira/issues");
    assert.deepEqual(
      seen.filter((r) => r.url.includes("/configuration")),
      [],
    );
  });

  it("statut absent des tableaux : colonne de fin à son nom", async () => {
    withOutsider = true;
    const { json } = await server.call("GET", "/jira/issues");
    withOutsider = false;
    const last = json.columns.at(-1);
    assert.equal(last.title, "Bizarre");
    assert.deepEqual(
      last.issues.map((i: any) => i.key),
      ["TECH-4"],
    );
  });

  it("tableau choisi disparu de Jira : message en français", async () => {
    await server.call("POST", "/jira/boards", { ids: [404] });
    const res = await server.call("GET", "/jira/issues");
    assert.equal(res.status, 500);
    assert.equal(res.json.message, "Un tableau choisi n'existe plus dans Jira : choisis-le à nouveau.");
  });

  it("sélection vidée : boardsChosen repasse à false", async () => {
    await server.call("POST", "/jira/boards", { ids: [] });
    const { json } = await server.call("GET", "/jira/issues");
    assert.equal(json.boardsChosen, false);
    assert.deepEqual((await server.call("GET", "/jira/boards")).json.selected, []);
  });

  it("GET transitions : needsJira, statut courant et transitions indisponibles exclus", async () => {
    const { status, json } = await server.call("GET", "/jira/issues/TECH-1/transitions");
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
    const res = await server.call("POST", "/jira/issues/TECH-1/transitions", { id: "11" });
    assert.deepEqual([res.status, res.json], [200, { ok: true }]);
    const post = seen.find((r) => r.method === "POST");
    assert.deepEqual(post?.body, { transition: { id: "11" } });
  });

  it("Jira répond 400 : 409 « À finir dans Jira »", async () => {
    transitionStatus = 400;
    const res = await server.call("POST", "/jira/issues/TECH-1/transitions", { id: "12" });
    transitionStatus = 204;
    assert.equal(res.status, 409);
    assert.match(res.json.error, /^À finir dans Jira/);
  });

  it("ticket inconnu : message en français", async () => {
    const res = await server.call("GET", "/jira/issues/TECH-404/transitions");
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
    assert.equal((await server.call("GET", "/jira/issues/tech-1/transitions")).status, 400);
    assert.equal((await server.call("GET", "/jira/issues/TECH-1;x/transitions")).status, 400);
    assert.equal((await server.call("POST", "/jira/issues/TECH-1/transitions", { id: "abc" })).status, 400);
    assert.equal((await server.call("POST", "/jira/issues/TECH-1/transitions", { id: "" })).status, 400);
    assert.equal((await server.call("POST", "/jira/issues/TECH-1/transitions", {})).status, 400);
    assert.deepEqual(seen, []);
  });

  it("POST open : ouvre la page du ticket sur l'ordinateur", async () => {
    server.clearOsCalls();
    const res = await server.call("POST", "/jira/issues/TECH-1/open");
    assert.deepEqual([res.status, res.json], [200, { ok: true }]);
    assert.deepEqual(server.osCalls(), [{ fn: "openTarget", args: [`${site}/browse/TECH-1`] }]);
  });

  it("Jira non configuré : 501 sur toutes les routes", async () => {
    const message = { error: "Jira n'est pas configuré sur l'ordinateur." };
    for (const [method, route, body] of [
      ["GET", "/jira/issues"],
      ["GET", "/jira/boards"],
      ["POST", "/jira/boards", { ids: [1] }],
      ["GET", "/jira/issues/TECH-1/transitions"],
      ["POST", "/jira/issues/TECH-1/transitions", { id: "11" }],
      ["POST", "/jira/issues/TECH-1/open"],
    ] as const) {
      const res = await bare.call(method, route, body);
      assert.deepEqual([res.status, res.json], [501, message], `${method} ${route}`);
    }
  });

  it("les routes demandent le jeton", async () => {
    assert.equal((await server.call("GET", "/jira/issues", undefined, false)).status, 401);
    assert.equal((await server.call("GET", "/jira/boards", undefined, false)).status, 401);
    assert.equal((await server.call("POST", "/jira/boards", { ids: [1] }, false)).status, 401);
  });
});
