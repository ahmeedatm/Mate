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
  JSON.stringify({ microsoft: { email: "a@outlook.com", refreshToken: "r1" }, google: { email: "b@gmail.com", refreshToken: "r2" } }),
);
const { accountUnread, inboxUrl } = await import("../src/accounts.ts");

test("lit les non lus de chaque compte ; le renouvellement Microsoft ne redemande pas de droits", async () => {
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
