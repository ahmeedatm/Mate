import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { test } from "node:test";

// Deux comptes connectés : le nombre de non lus de chacun, un compte refusé ne cache pas l'autre.
const dir = fs.mkdtempSync(path.join(os.tmpdir(), "companion-mail-"));
process.env.DATA_DIR = dir;
process.env.ACCOUNTS_FILE = path.join(import.meta.dirname, "accounts-vide.json");
process.env.MICROSOFT_CLIENT_ID = "ms";
process.env.GOOGLE_CLIENT_ID = "g";
fs.writeFileSync(
  path.join(dir, "accounts.json"),
  JSON.stringify({
    microsoft: { email: "a@outlook.com", refreshToken: "r1", mail: true },
    google: { email: "b@gmail.com", refreshToken: "r2", mail: true },
  }),
);
const { accountUnread, clearMailCache, connectedAccounts, finishLogin, inboxUrl, loginUrl } = await import("../src/accounts.ts");

const scopeOf = (url: string) => new URL(url).searchParams.get("scope");
const REDIRECT = "http://localhost:3000/oauth/microsoft/callback";

// Ce que l'agenda demandait avant les mails (origin/feat/veille-novembre) : une connexion d'agenda seule ne change pas.
const AGENDA_SCOPE = {
  microsoft: "openid email offline_access https://graph.microsoft.com/Calendars.Read",
  google: "openid email https://www.googleapis.com/auth/calendar.readonly",
};

test("la connexion de l'agenda redemande exactement ses droits d'avant, sans les mails", async () => {
  assert.equal(scopeOf(await loginUrl("microsoft", REDIRECT)), AGENDA_SCOPE.microsoft);
  assert.equal(scopeOf(await loginUrl("google", REDIRECT)), AGENDA_SCOPE.google);
});

test("la connexion « mails » ajoute les droits de lecture au même fournisseur", async () => {
  assert.equal(
    scopeOf(await loginUrl("microsoft", REDIRECT, true)),
    `${AGENDA_SCOPE.microsoft} https://graph.microsoft.com/Mail.ReadBasic`,
  );
  assert.equal(scopeOf(await loginUrl("google", REDIRECT, true)), `${AGENDA_SCOPE.google} https://www.googleapis.com/auth/gmail.metadata`);
});

const restoreAccounts = () => {
  fs.writeFileSync(
    path.join(dir, "accounts.json"),
    JSON.stringify({
      microsoft: { email: "a@outlook.com", refreshToken: "r1", mail: true },
      google: { email: "b@gmail.com", refreshToken: "r2", mail: true },
    }),
  );
  clearMailCache();
};

test("lit les non lus de chaque compte ; le renouvellement Microsoft ne redemande pas de droits", async () => {
  restoreAccounts();
  const refreshBodies: string[] = [];
  globalThis.fetch = (async (url: string, init?: RequestInit) => {
    if (String(url).includes("/token")) {
      refreshBodies.push(String(init?.body));
      return Response.json({ access_token: "t", expires_in: 3600 });
    }
    if (String(url).includes("graph.microsoft.com")) return Response.json({ unreadItemCount: 4 });
    return Response.json({ messagesUnread: 3 });
  }) as typeof fetch;
  const rows = await accountUnread();
  assert.deepEqual(
    rows.map((r) => [r.provider, r.unread]),
    [
      ["microsoft", 4],
      ["google", 3],
    ],
  );
  const microsoft = refreshBodies.find((b) => b.includes("client_id=ms"))!;
  assert.doesNotMatch(microsoft, /scope=/);
});

test("un compte qui refuse (droits manquants) dit de se reconnecter, l'autre garde son nombre", async () => {
  restoreAccounts();
  globalThis.fetch = (async (url: string) => {
    if (String(url).includes("/token")) return Response.json({ access_token: "t", expires_in: 3600 });
    if (String(url).includes("graph.microsoft.com")) return new Response("", { status: 403 });
    return Response.json({ messagesUnread: 3 });
  }) as typeof fetch;
  const [microsoft, google] = await accountUnread();
  assert.equal(microsoft.unread, null);
  assert.match(microsoft.error!, /reconnecte/i);
  assert.equal(google.unread, 3);
});

test("la boîte à ouvrir est une adresse fixe par fournisseur", async () => {
  assert.equal(await inboxUrl("microsoft"), "https://outlook.office.com/mail/");
  assert.equal(await inboxUrl("google"), "https://mail.google.com/mail/?authuser=b%40gmail.com");
});

test("un compte sans droits mails n'est pas appelé chez le fournisseur ni listé", async () => {
  fs.writeFileSync(
    path.join(dir, "accounts.json"),
    JSON.stringify({
      microsoft: { email: "a@outlook.com", refreshToken: "r1" },
      google: { email: "b@gmail.com", refreshToken: "r2", mail: true },
    }),
  );
  clearMailCache();
  const urls: string[] = [];
  globalThis.fetch = (async (url: string) => {
    urls.push(String(url));
    if (String(url).includes("/token")) return Response.json({ access_token: "t", expires_in: 3600 });
    return Response.json({ messagesUnread: 3 });
  }) as typeof fetch;
  const rows = await accountUnread();
  assert.deepEqual(
    rows.map((r) => [r.provider, r.unread]),
    [["google", 3]],
  );
  assert.ok(
    urls.every((u) => !u.includes("microsoft")),
    `appel inattendu : ${urls}`,
  );
  assert.equal(await inboxUrl("microsoft"), undefined);
  assert.equal("email" in rows[0], false, "l'adresse n'est pas envoyée à la tablette");
});

test("la réponse est gardée 30 s et une lecture en cours est partagée", async () => {
  restoreAccounts();
  let calls = 0;
  globalThis.fetch = (async (url: string) => {
    if (String(url).includes("/token")) return Response.json({ access_token: "t", expires_in: 3600 });
    calls++;
    await new Promise((r) => setTimeout(r, 20));
    return String(url).includes("graph.microsoft.com") ? Response.json({ unreadItemCount: 4 }) : Response.json({ messagesUnread: 3 });
  }) as typeof fetch;
  const [a, b] = await Promise.all([accountUnread(), accountUnread()]);
  assert.equal(calls, 2, "une lecture par compte, pour les deux demandes");
  assert.deepEqual(a, b);
  await accountUnread();
  assert.equal(calls, 2, "gardée");
  clearMailCache();
  await accountUnread();
  assert.equal(calls, 4);
});

test("l'échec de lecture est journalisé avec le statut HTTP seulement", async () => {
  restoreAccounts();
  globalThis.fetch = (async (url: string) => {
    if (String(url).includes("/token")) return Response.json({ access_token: "SECRET-ACCESS", expires_in: 3600 });
    return String(url).includes("graph.microsoft.com")
      ? new Response("SECRET-BODY", { status: 503 })
      : Response.json({ messagesUnread: 1 });
  }) as typeof fetch;
  const logs: string[] = [];
  await accountUnread((line) => logs.push(line));
  assert.equal(logs.length, 1);
  assert.match(logs[0], /microsoft.*HTTP 503/);
  assert.doesNotMatch(logs.join(), /SECRET/);
});

// En dernier : la connexion garde un jeton d'accès Microsoft en mémoire, qui fausserait les lectures des tests d'avant.
const idToken = `x.${Buffer.from(JSON.stringify({ email: "c@outlook.com" })).toString("base64url")}.y`;

test("le compte garde trace des droits mails accordés ; l'échange du code redemande les mêmes droits", async () => {
  const bodies: string[] = [];
  globalThis.fetch = (async (_url: string, init?: RequestInit) => {
    bodies.push(String(init?.body));
    return Response.json({ access_token: "t", refresh_token: "r", id_token: idToken, expires_in: 3600 });
  }) as typeof fetch;
  const connect = async (mail: boolean) => {
    const state = new URL(await loginUrl("microsoft", REDIRECT, mail)).searchParams.get("state")!;
    await finishLogin(state, "code");
    return (await connectedAccounts()).find((a) => a.provider === "microsoft")!;
  };
  assert.equal((await connect(true)).mail, true);
  assert.match(new URLSearchParams(bodies.at(-1)).get("scope")!, /Mail\.ReadBasic/);
  // Reconnecter l'agenda seul : le nouveau jeton n'a plus les droits mails, le compte ne les annonce plus.
  assert.equal((await connect(false)).mail, false);
  assert.doesNotMatch(new URLSearchParams(bodies.at(-1)).get("scope")!, /Mail/);
});
