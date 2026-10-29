import assert from "node:assert/strict";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { beforeEach, describe, it } from "node:test";

const dir = await fs.mkdtemp(path.join(os.tmpdir(), "companion-mail-"));
process.env.DATA_DIR = dir;
process.env.CALENDAR_ICS_URLS = "";
process.env.MICROSOFT_CLIENT_ID = "client-de-test";
const { disconnect, finishLogin, loginUrl } = await import("../src/accounts.ts");
const { mailSummary } = await import("../src/mail.ts");

const accountsFile = path.join(dir, "accounts.json");
const link = (account: object) =>
  fs.writeFile(accountsFile, JSON.stringify({ microsoft: { email: "moi@exemple.fr", refreshToken: "r1", ...account } }));

// --- Faux Microsoft : jeton et Graph. `requests` garde les adresses demandées, `scopes` les scopes des renouvellements.

const NOW = Date.parse("2026-10-29T10:00:00Z");
let requests: string[] = [];
let scopes: string[] = [];
let tokenAnswer: { status: number; json: object };
let graphStatus: number;

const json = (status: number, body: object) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });

const FOLDERS: Record<string, { unreadItemCount: number; totalItemCount: number }> = {
  inbox: { unreadItemCount: 12, totalItemCount: 340 },
  junkemail: { unreadItemCount: 3, totalItemCount: 8 },
  drafts: { unreadItemCount: 0, totalItemCount: 2 },
  sentitems: { unreadItemCount: 0, totalItemCount: 1200 },
};

const MESSAGES = [
  {
    subject: "Budget 2027",
    receivedDateTime: "2026-10-29T09:30:00Z",
    from: { emailAddress: { name: "Léa Martin", address: "lea@exemple.fr" } },
  },
  { subject: "", receivedDateTime: "2026-10-29T08:00:00Z", from: { emailAddress: { name: "", address: "noreply@exemple.fr" } } },
  { receivedDateTime: "2026-10-28T17:00:00Z" },
];

const event = (id: string, title: string, startH: number, endH: number, extra: object = {}) => ({
  iCalUId: id,
  subject: title,
  isAllDay: false,
  isCancelled: false,
  showAs: "busy",
  start: { dateTime: `2026-10-29T${String(startH).padStart(2, "0")}:00:00.0000000` },
  end: { dateTime: `2026-10-29T${String(endH).padStart(2, "0")}:00:00.0000000` },
  ...extra,
});

globalThis.fetch = async (input, init) => {
  const url = String(input instanceof Request ? input.url : input);
  if (url.includes("login.microsoftonline.com") && url.endsWith("/token")) {
    const body = new URLSearchParams(String(init?.body));
    if (body.get("grant_type") === "refresh_token") scopes.push(body.get("scope") ?? "");
    return json(tokenAnswer.status, tokenAnswer.json);
  }
  assert.ok(url.startsWith("https://graph.microsoft.com/v1.0/"), `appel inattendu : ${url}`);
  requests.push(url);
  assert.equal((init?.headers as Record<string, string>).authorization, "Bearer jeton-graph");
  if (graphStatus !== 200) return json(graphStatus, { error: { code: "ErrorAccessDenied" } });
  const folder = url.match(/\/me\/mailFolders\/(\w+)\?/)?.[1];
  if (folder) return json(200, FOLDERS[folder]);
  if (url.includes("/me/mailFolders/inbox/messages")) return json(200, { value: MESSAGES });
  if (url.includes("/me/calendarView")) {
    return json(200, {
      value: [
        event("fini", "Déjà fini", 8, 9),
        event("journee", "Congé", 0, 24, { isAllDay: true }),
        event("libre", "Créneau libre", 10, 11, { showAs: "free" }),
        event("point", "Point d'équipe", 11, 12),
        event("apres", "Revue", 14, 15),
      ],
    });
  }
  throw new Error(`Graph inattendu : ${url}`);
};

beforeEach(async () => {
  await disconnect("microsoft");
  requests = [];
  scopes = [];
  graphStatus = 200;
  tokenAnswer = { status: 200, json: { access_token: "jeton-graph", expires_in: 3600 } };
});

describe("mailSummary", () => {
  it("compteurs, 5 derniers non lus et prochain rendez-vous", async () => {
    await link({ mail: true });
    const summary = await mailSummary(NOW);
    assert.equal(summary.status, "ok");
    if (summary.status !== "ok") return;
    assert.deepEqual([summary.unread, summary.junk, summary.drafts, summary.sent], [12, 3, 2, 1200]);
    assert.deepEqual(
      summary.recent.map((m) => [m.from, m.subject, m.receivedMs]),
      [
        ["Léa Martin", "Budget 2027", Date.parse("2026-10-29T09:30:00Z")],
        ["noreply@exemple.fr", "", Date.parse("2026-10-29T08:00:00Z")],
        ["", "", Date.parse("2026-10-28T17:00:00Z")],
      ],
    );
    // Ni terminé, ni journée entière, ni « disponible » : le point d'équipe de 11 h.
    assert.deepEqual(summary.next, {
      title: "Point d'équipe",
      startMs: Date.parse("2026-10-29T11:00:00Z"),
      endMs: Date.parse("2026-10-29T12:00:00Z"),
    });
  });

  it("Graph : les non lus sont demandés du plus récent au plus ancien, avec le tri en tête du filtre, sans le corps des messages", async () => {
    await link({ mail: true });
    await mailSummary(NOW);
    const query = new URL(requests.find((u) => u.includes("/inbox/messages"))!).searchParams;
    assert.equal(query.get("$filter"), "receivedDateTime ge 1900-01-01T00:00:00Z and isRead eq false");
    assert.equal(query.get("$orderby"), "receivedDateTime desc");
    assert.equal(query.get("$top"), "5");
    assert.equal(query.get("$select"), "from,subject,receivedDateTime");
  });

  it("prochain rendez-vous : null quand il n'y en a plus", async () => {
    await link({ mail: true });
    const realGraph = globalThis.fetch;
    globalThis.fetch = async (input, init) =>
      String(input).includes("/me/calendarView") ? json(200, { value: [] }) : realGraph(input, init);
    try {
      const summary = await mailSummary(NOW);
      assert.equal(summary.status === "ok" && summary.next, null);
    } finally {
      globalThis.fetch = realGraph;
    }
  });

  it("pas de compte Microsoft relié : no-account, sans appel réseau", async () => {
    assert.deepEqual(await mailSummary(NOW), { status: "no-account" });
    assert.deepEqual(requests, []);
  });

  it("compte relié avant le courrier (sans Mail.Read) : à reconnecter, sans appel à Graph ni renouvellement", async () => {
    await link({});
    assert.deepEqual(await mailSummary(NOW), { status: "reconnect" });
    assert.deepEqual(requests, []);
    assert.deepEqual(scopes, []);
  });

  it("compte expiré : à reconnecter", async () => {
    await link({ mail: true, expired: true });
    assert.deepEqual(await mailSummary(NOW), { status: "reconnect" });
  });

  it("renouvellement refusé (invalid_grant) : à reconnecter, compte marqué expiré", async () => {
    await link({ mail: true });
    tokenAnswer = { status: 400, json: { error: "invalid_grant", error_description: "refusé" } };
    assert.deepEqual(await mailSummary(NOW), { status: "reconnect" });
    assert.equal(JSON.parse(await fs.readFile(accountsFile, "utf8")).microsoft.expired, true);
  });

  it("Graph refuse l'accès (403) : à reconnecter", async () => {
    await link({ mail: true });
    graphStatus = 403;
    assert.deepEqual(await mailSummary(NOW), { status: "reconnect" });
  });

  it("autre erreur de Graph : remontée, pas masquée en « à reconnecter »", async () => {
    await link({ mail: true });
    graphStatus = 500;
    await assert.rejects(mailSummary(NOW), /Courrier du compte : HTTP 500/);
  });

  it("renouvellement du jeton : Mail.Read demandé seulement si le compte l'a accordé", async () => {
    await link({ mail: true });
    await mailSummary(NOW);
    assert.match(scopes[0], /Mail\.Read/);
    assert.match(scopes[0], /Calendars\.Read/);
  });
});

describe("connexion Microsoft", () => {
  const login = async (scope: string) => {
    tokenAnswer = {
      status: 200,
      json: {
        access_token: "jeton-graph",
        refresh_token: "r2",
        expires_in: 3600,
        scope,
        id_token: `x.${Buffer.from('{"email":"moi@exemple.fr"}').toString("base64url")}.y`,
      },
    };
    const state = new URL(await loginUrl("microsoft", "http://localhost:3000/oauth/microsoft/callback")).searchParams.get("state")!;
    await finishLogin(state, "code");
  };

  it("la page de connexion demande Mail.Read en plus de l'agenda", async () => {
    const url = new URL(await loginUrl("microsoft", "http://localhost:3000/oauth/microsoft/callback"));
    assert.match(url.searchParams.get("scope")!, /Calendars\.Read.*Mail\.Read/);
  });

  it("Mail.Read accordé : le compte donne accès au courrier", async () => {
    await login("Mail.Read Calendars.Read openid profile email");
    assert.equal((await mailSummary(NOW)).status, "ok");
  });

  it("Mail.Read non accordé : à reconnecter", async () => {
    await login("Calendars.Read openid profile email");
    assert.equal((await mailSummary(NOW)).status, "reconnect");
  });
});
