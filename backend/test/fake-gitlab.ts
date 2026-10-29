import { createHash, randomBytes } from "node:crypto";
import http from "node:http";
import type { AddressInfo } from "node:net";

/**
 * Faux GitLab pour les tests et l'émulateur : page d'autorisation (OAuth, code + PKCE, appli non confidentielle),
 * `/oauth/token` (code et renouvellement, jeton de renouvellement tournant, portée accordée dans `scope`), `/api/v4/user`,
 * `/api/graphql` et la fusion. Comme le vrai, la page d'autorisation refuse une portée que l'appli n'a pas (`allowedScopes`).
 * Un jeton d'accès (Bearer) ou le jeton personnel `personalToken` (PRIVATE-TOKEN) ouvre l'API ; le reste : 401.
 */

export const FAKE_CLIENT_ID = "app-id-de-test";
export const FAKE_SHA = "0123456789abcdef0123456789abcdef01234567";

export const fakeMr = (over: Record<string, unknown> = {}) => ({
  iid: "12",
  title: "fix: un titre",
  draft: false,
  detailedMergeStatus: "MERGEABLE",
  diffHeadSha: FAKE_SHA,
  updatedAt: "2026-10-28T10:00:00Z",
  approvalsRequired: 1,
  approvalsLeft: 0,
  approvedBy: { nodes: [{ name: "Camille" }] },
  author: { name: "Ahmed" },
  project: { fullPath: "grp/proj" },
  headPipeline: { status: "SUCCESS" },
  ...over,
});

export type Seen = { method: string; url: string; authorization?: string; privateToken?: string; body: any };

export type FakeGitlab = {
  url: string;
  /** Requêtes reçues, dans l'ordre. */
  seen: Seen[];
  /** Jetons émis (accès et renouvellement) : pour vérifier qu'ils ne fuient pas. */
  issued: string[];
  /** Durée annoncée des jetons d'accès, en secondes (7200 comme GitLab ; ≤ 60 : à renouveler à chaque appel). */
  expiresIn: number;
  /** Jeton personnel accepté en PRIVATE-TOKEN (secours .env). */
  personalToken: string;
  /** Portées de l'appli OAuth : l'autorisation en demandant une autre est refusée (réglage de l'admin GitLab). */
  allowedScopes: string[];
  /** Portée annoncée dans la réponse du jeton à la place de celle demandée (GitLab peut en accorder moins). */
  grantScope?: string;
  /** Code HTTP de la fusion (200 : fusionnée). */
  mergeStatus: number;
  /** Jetons d'accès et de renouvellement tous révoqués : l'API répond 401, le renouvellement `invalid_grant`. */
  revokeAll: () => void;
  /** Page d'autorisation, étape « Autoriser » : adresse de retour de l'appli avec le code (ou l'erreur si `deny`). */
  authorize: (authorizeUrl: string, deny?: boolean) => Promise<string>;
  mrs: { assigned: unknown[]; review: unknown[] };
  stop: () => Promise<void>;
};

const sha256url = (s: string) => createHash("sha256").update(s).digest("base64url");

export async function startFakeGitlab(port = 0): Promise<FakeGitlab> {
  let counter = 0;
  const codes = new Map<string, { challenge: string; redirectUri: string; scope: string }>();
  const access = new Set<string>();
  const refresh = new Map<string, string>();
  const fake: FakeGitlab = {
    url: "",
    seen: [],
    issued: [],
    expiresIn: 7200,
    personalToken: "",
    allowedScopes: ["read_api", "openid", "profile", "email"],
    mergeStatus: 200,
    revokeAll: () => {
      access.clear();
      refresh.clear();
    },
    authorize: async (authorizeUrl, deny = false) => {
      const res = await fetch(authorizeUrl, { redirect: "manual" });
      const page = await res.text();
      const link = new RegExp(`href="([^"]+)">${deny ? "Refuser" : "Autoriser"}<`).exec(page)?.[1];
      if (!link) throw new Error(`Page d'autorisation inattendue (${res.status}) : ${page.slice(0, 200)}`);
      const done = await fetch(fake.url + link, { redirect: "manual" });
      return done.headers.get("location") ?? "";
    },
    mrs: {
      assigned: [fakeMr(), fakeMr({ iid: "3", updatedAt: "2026-10-29T10:00:00Z", detailedMergeStatus: "NOT_APPROVED" })],
      review: [fakeMr({ iid: "99", project: { fullPath: "dev/robot-framework" }, headPipeline: null })],
    },
    stop: () => new Promise((r) => server.close(() => r())),
  };

  const issue = (scope: string) => {
    const token = {
      access_token: `at-${counter}-${randomBytes(8).toString("hex")}`,
      refresh_token: `rt-${counter++}-${randomBytes(8).toString("hex")}`,
    };
    access.add(token.access_token);
    refresh.set(token.refresh_token, scope);
    fake.issued.push(token.access_token, token.refresh_token);
    return {
      ...token,
      token_type: "bearer",
      scope: fake.grantScope ?? scope,
      expires_in: fake.expiresIn,
      created_at: Math.floor(Date.now() / 1000),
    };
  };
  const authorized = (req: http.IncomingMessage) => {
    const bearer = /^Bearer (.+)$/.exec(String(req.headers.authorization ?? ""))?.[1];
    const personal = req.headers["private-token"];
    return (bearer !== undefined && access.has(bearer)) || (fake.personalToken !== "" && personal === fake.personalToken);
  };

  const server = http.createServer((req, res) => {
    let raw = "";
    req.on("data", (c) => (raw += c));
    req.on("end", () => {
      const url = new URL(req.url ?? "/", "http://fake");
      const isForm = String(req.headers["content-type"] ?? "").includes("x-www-form-urlencoded");
      fake.seen.push({
        method: req.method ?? "",
        url: req.url ?? "",
        authorization: req.headers.authorization,
        privateToken: req.headers["private-token"] as string | undefined,
        body: raw ? (isForm ? Object.fromEntries(new URLSearchParams(raw)) : JSON.parse(raw)) : undefined,
      });
      const json = (status: number, body: unknown) => {
        res.statusCode = status;
        res.setHeader("content-type", "application/json");
        res.end(JSON.stringify(body));
      };

      if (url.pathname === "/oauth/authorize") {
        const q = url.searchParams;
        const redirectUri = q.get("redirect_uri") ?? "";
        if (
          q.get("client_id") !== FAKE_CLIENT_ID ||
          q.get("response_type") !== "code" ||
          !redirectUri ||
          !q.get("code_challenge") ||
          q.get("code_challenge_method") !== "S256"
        ) {
          res.statusCode = 400;
          return res.end("Demande d'autorisation invalide");
        }
        const scope = q.get("scope") ?? "";
        const asked = scope.split(" ").filter(Boolean);
        if (asked.length === 0 || asked.some((s) => !fake.allowedScopes.includes(s))) {
          res.statusCode = 400;
          return res.end("The requested scope is invalid, unknown, or malformed.");
        }
        const code = `code-${counter++}-${randomBytes(8).toString("hex")}`;
        codes.set(code, { challenge: q.get("code_challenge")!, redirectUri, scope });
        const back = (extra: Record<string, string>) => `${redirectUri}?${new URLSearchParams({ ...extra, state: q.get("state") ?? "" })}`;
        const link = (href: string, text: string) => `<a href="${`/oauth/decision?to=${encodeURIComponent(href)}`}">${text}</a>`;
        res.setHeader("content-type", "text/html; charset=utf-8");
        return res.end(
          `<!doctype html><title>Faux GitLab</title><h1>Autoriser Unowhy Companion ?</h1><p>Accès à ton compte (${scope}).</p>` +
            `${link(back({ code }), "Autoriser")} ${link(back({ error: "access_denied" }), "Refuser")}`,
        );
      }
      if (url.pathname === "/oauth/decision") {
        res.statusCode = 302;
        res.setHeader("location", url.searchParams.get("to") ?? "/");
        return res.end();
      }
      if (url.pathname === "/oauth/token" && req.method === "POST") {
        const p = new URLSearchParams(raw);
        if (p.get("client_id") !== FAKE_CLIENT_ID || p.has("client_secret")) return json(401, { error: "invalid_client" });
        if (p.get("grant_type") === "authorization_code") {
          const entry = codes.get(p.get("code") ?? "");
          codes.delete(p.get("code") ?? "");
          const verifier = p.get("code_verifier") ?? "";
          if (!entry || entry.redirectUri !== p.get("redirect_uri") || sha256url(verifier) !== entry.challenge) {
            return json(400, { error: "invalid_grant", error_description: "Code ou vérificateur PKCE invalide" });
          }
          return json(200, issue(entry.scope));
        }
        if (p.get("grant_type") === "refresh_token") {
          const old = p.get("refresh_token") ?? "";
          const scope = refresh.get(old);
          if (scope === undefined) return json(400, { error: "invalid_grant", error_description: "Jeton de renouvellement invalide" });
          refresh.delete(old);
          // Tournant : le renouvellement invalide aussi les jetons d'accès émis avant.
          access.clear();
          return json(200, issue(scope));
        }
        return json(400, { error: "unsupported_grant_type" });
      }
      if (!authorized(req)) return json(401, { message: "401 Unauthorized" });
      if (url.pathname === "/api/v4/user") return json(200, { username: "camille", name: "Camille" });
      if (url.pathname === "/api/graphql") {
        return json(200, {
          data: {
            currentUser: { assignedMergeRequests: { nodes: fake.mrs.assigned }, reviewRequestedMergeRequests: { nodes: fake.mrs.review } },
          },
        });
      }
      if (/^\/api\/v4\/projects\/[^/]+\/merge_requests\/\d+\/merge$/.test(url.pathname) && req.method === "PUT") {
        return json(fake.mergeStatus, fake.mergeStatus === 200 ? { state: "merged" } : { message: "refus" });
      }
      json(404, { message: "404 Not Found" });
    });
  });
  await new Promise<void>((r) => server.listen(port, "127.0.0.1", r));
  fake.url = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  return fake;
}
