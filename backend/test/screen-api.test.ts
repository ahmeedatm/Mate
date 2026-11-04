import assert from "node:assert/strict";
import { after, before, beforeEach, describe, it } from "node:test";
import { startServer, TOKEN, type TestServer } from "./server.ts";

// Aperçu de l'écran de l'ordinateur : GET /screen/preview rend un JPEG réduit, sous jeton, jamais gardé sur disque.

let server: TestServer;
let other: TestServer;
before(async () => {
  server = await startServer();
  other = await startServer({ os: "other" });
});
after(async () => {
  await server?.stop();
  await other?.stop();
});
beforeEach(() => {
  server.setOs({});
  server.clearOsCalls();
});

const get = (target: TestServer, auth = true) =>
  fetch(`${target.url}/screen/preview`, { headers: auth ? { authorization: `Bearer ${TOKEN}` } : {} });

describe("aperçu de l'écran", () => {
  it("refuse sans jeton", async () => {
    assert.equal((await get(server, false)).status, 401);
  });

  it("rend une image JPEG de 640 px au plus, jamais mise en cache", async () => {
    const res = await get(server);
    assert.equal(res.status, 200);
    assert.equal(res.headers.get("content-type"), "image/jpeg");
    assert.match(res.headers.get("cache-control") ?? "", /no-store/);
    const body = Buffer.from(await res.arrayBuffer());
    assert.deepEqual([...body.subarray(0, 2)], [0xff, 0xd8]);
    assert.deepEqual(
      server.osCalls().filter((c) => c.fn === "screenPreview"),
      [{ fn: "screenPreview", args: [640] }],
    );
  });

  it("une capture à la fois : des requêtes en même temps partagent la même", async () => {
    server.setOs({ screenDelayMs: 300 });
    const replies = await Promise.all([get(server), get(server), get(server)]);
    assert.deepEqual(
      replies.map((r) => r.status),
      [200, 200, 200],
    );
    assert.equal(server.osCalls().filter((c) => c.fn === "screenPreview").length, 1);
  });

  it("sans autorisation de l'écran : une phrase claire (403), pas d'image", async () => {
    server.setOs({ screenDenied: true });
    const res = await get(server);
    assert.equal(res.status, 403);
    const body = (await res.json()) as { message?: string; error?: string };
    assert.match(body.message ?? body.error ?? "", /Enregistrement de l'écran/);
  });

  it("une erreur de capture ne reste pas : la requête suivante réessaie", async () => {
    server.setOs({ screenDenied: true });
    assert.equal((await get(server)).status, 403);
    server.setOs({});
    assert.equal((await get(server)).status, 200);
  });

  it("répond 501 hors Mac et Windows", async () => {
    assert.equal((await get(other)).status, 501);
  });
});
