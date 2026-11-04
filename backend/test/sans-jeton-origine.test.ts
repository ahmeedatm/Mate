import assert from "node:assert/strict";
import { after, before, describe, it } from "node:test";
import { startServer, type TestServer } from "./server.ts";

// Mode sans jeton (HOST=127.0.0.1, COMPANION_TOKEN vide : émulateur) : une page web ouverte sur l'ordinateur peut poster
// vers le compagnon sans pré-vérification CORS. Une écriture qui porte un `Origin` non local est refusée.

let server: TestServer;
let withToken: TestServer;
before(async () => {
  server = await startServer({ env: { COMPANION_TOKEN: "" } });
  withToken = await startServer();
});
after(async () => {
  await server?.stop();
  await withToken?.stop();
});

const post = (s: TestServer, origin?: string) =>
  fetch(`${s.url}/tasks`, {
    method: "POST",
    headers: { "content-type": "application/json", ...(origin ? { origin } : {}) },
    body: JSON.stringify({ label: "essai" }),
  });

describe("écritures sans jeton et en-tête Origin", () => {
  it("refuse (403) un Origin d'un autre site", async () => {
    for (const origin of ["https://exemple.com", "http://evil.test:3000", "null", "http://127.0.0.1.evil.test"]) {
      const res = await post(server, origin);
      assert.equal(res.status, 403, origin);
    }
  });

  it("refuse aussi les routes sans jeton (appairage) et les formulaires multipart", async () => {
    const pair = await fetch(`${server.url}/pair/request`, {
      method: "POST",
      headers: { "content-type": "application/json", origin: "https://exemple.com" },
      body: "{}",
    });
    assert.equal(pair.status, 403);
    const form = new FormData();
    form.append("file", new Blob(["x"]), "a.pdf");
    const share = await fetch(`${server.url}/docs/share`, { method: "POST", headers: { origin: "https://exemple.com" }, body: form });
    assert.equal(share.status, 403);
  });

  it("accepte l'émulateur et les outils (pas d'Origin), la page locale et l'extension du navigateur", async () => {
    for (const origin of [undefined, "http://127.0.0.1:3000", "http://localhost:3000", "http://[::1]:3000", "chrome-extension://abc"]) {
      const res = await post(server, origin);
      assert.notEqual(res.status, 403, String(origin));
    }
  });

  it("laisse les lectures passer, même avec un Origin étranger", async () => {
    const res = await fetch(`${server.url}/health`, { headers: { origin: "https://exemple.com" } });
    assert.equal(res.status, 200);
  });

  it("ne change rien quand un jeton est défini (le jeton protège déjà)", async () => {
    const res = await post(withToken, "https://exemple.com");
    assert.equal(res.status, 401);
  });
});
