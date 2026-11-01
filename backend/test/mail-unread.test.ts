import assert from "node:assert/strict";
import fs from "node:fs/promises";
import path from "node:path";
import { after, afterEach, before, beforeEach, describe, it } from "node:test";
import { unreadFrom } from "../src/accounts.ts";
import { startServer, type TestServer } from "./server.ts";

describe("mails non lus : lecture de la réponse du fournisseur", () => {
  it("lit unreadItemCount chez Microsoft et messagesUnread chez Google", () => {
    assert.equal(unreadFrom("microsoft", { unreadItemCount: 7 }), 7);
    assert.equal(unreadFrom("google", { messagesUnread: 12 }), 12);
  });

  it("refuse une réponse sans nombre valide", () => {
    for (const json of [{}, { unreadItemCount: "7" }, { unreadItemCount: -1 }, { unreadItemCount: 1.5 }]) {
      assert.throws(() => unreadFrom("microsoft", json), /réponse/i);
    }
    assert.throws(() => unreadFrom("google", { unreadItemCount: 7 }), /réponse/i);
  });
});

// Un serveur par test : la réponse est gardée 30 s, un compte changé entre deux tests y resterait.
let server: TestServer;
beforeEach(async () => {
  server = await startServer();
});
afterEach(async () => {
  await server?.stop();
});

describe("mails non lus : routes", () => {
  it("refuse sans jeton", async () => {
    assert.equal((await server.call("GET", "/mail/unread", undefined, false)).status, 401);
    assert.equal((await server.call("POST", "/mail/open", { provider: "google" }, false)).status, 401);
  });

  it("rend une liste vide sans compte connecté", async () => {
    const res = await server.call("GET", "/mail/unread");
    assert.equal(res.status, 200);
    assert.deepEqual(res.json, { accounts: [] });
  });

  it("garde le compte en panne dans la liste, sans détail technique ni jeton", async () => {
    await fs.writeFile(
      path.join(server.dataDir, "accounts.json"),
      JSON.stringify({ google: { email: "moi@exemple.fr", refreshToken: "SECRET-TOKEN", mail: true } }),
    );
    const res = await server.call("GET", "/mail/unread");
    assert.equal(res.status, 200);
    assert.equal(res.json.accounts.length, 1);
    assert.deepEqual(
      { provider: res.json.accounts[0].provider, unread: res.json.accounts[0].unread },
      { provider: "google", unread: null },
    );
    assert.doesNotMatch(JSON.stringify(res.json), /moi@exemple/, "l'adresse reste sur l'ordinateur");
    assert.ok(res.json.accounts[0].error);
    assert.doesNotMatch(JSON.stringify(res.json), /SECRET-TOKEN/);
  });

  it("dit de se reconnecter quand le compte a perdu son accès", async () => {
    await fs.writeFile(
      path.join(server.dataDir, "accounts.json"),
      JSON.stringify({ microsoft: { email: "moi@exemple.fr", refreshToken: "x", expired: true, mail: true } }),
    );
    const res = await server.call("GET", "/mail/unread");
    assert.match(res.json.accounts[0].error, /reconnect/i);
  });

  it("ne liste pas un compte connecté pour l'agenda seulement", async () => {
    await fs.writeFile(
      path.join(server.dataDir, "accounts.json"),
      JSON.stringify({ google: { email: "moi@exemple.fr", refreshToken: "x" } }),
    );
    assert.deepEqual((await server.call("GET", "/mail/unread")).json, { accounts: [] });
    assert.equal((await server.call("POST", "/mail/open", { provider: "google" })).status, 404);
  });

  it("n'ouvre pas la boîte d'un fournisseur non connecté ni inconnu", async () => {
    assert.equal((await server.call("POST", "/mail/open", { provider: "google" })).status, 404);
    assert.equal((await server.call("POST", "/mail/open", { provider: "yahoo" })).status, 400);
  });
});

describe("connexion des mails : /oauth/:provider/start", () => {
  let configured: TestServer;
  before(async () => {
    configured = await startServer({ env: { GOOGLE_CLIENT_ID: "g" } });
  });
  after(async () => {
    await configured?.stop();
  });
  const scope = async (query: string) => {
    const res = await fetch(`${configured.url}/oauth/google/start${query}`, { redirect: "manual" });
    assert.equal(res.status, 302);
    return new URL(res.headers.get("location")!).searchParams.get("scope")!;
  };

  it("demande les droits mails seulement avec ?mail=1", async () => {
    assert.doesNotMatch(await scope(""), /gmail/);
    assert.match(await scope("?mail=1"), /gmail\.metadata/);
  });
});
