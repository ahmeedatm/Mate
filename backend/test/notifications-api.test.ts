import assert from "node:assert/strict";
import { after, before, describe, it } from "node:test";
import { startServer, type TestServer } from "./server.ts";

// Historique des notifications en HTTP, sur le faux pilotage (test/fake-os.ts) relu toutes les 30 ms.

let server: TestServer;
before(async () => {
  server = await startServer({ env: { NOTIFICATIONS_POLL_MS: "30" } });
});
after(async () => {
  await server?.stop();
});

/** Attend que l'historique compte `count` notifications. */
async function waitFor(count: number) {
  for (let i = 0; i < 100; i++) {
    const { json } = await server.call("GET", "/notifications");
    if (json.items.length === count) return json.items;
    await new Promise((resolve) => setTimeout(resolve, 20));
  }
  assert.fail(`historique jamais à ${count}`);
}

describe("notifications en HTTP", () => {
  it("liste les notifications de l'ordinateur, la plus récente en haut ; retire, vide ; 404 pour une disparue", async () => {
    const now = Date.now();
    server.setOs({
      notifications: [
        { app: "Outlook", title: "Marie Dupont", body: "secret-du-journal-42", at: now - 2000 },
        { app: "Discord", title: "Léa", body: "on joue ?", at: now - 1000 },
      ],
    });
    const items = await waitFor(2);
    assert.deepEqual(
      items.map((item: { app: string; title: string }) => [item.app, item.title]),
      [
        ["Discord", "Léa"],
        ["Outlook", "Marie Dupont"],
      ],
    );
    assert.equal((await server.call("DELETE", `/notifications/${items[0].id}`)).status, 200);
    assert.equal((await server.call("DELETE", `/notifications/${items[0].id}`)).status, 404);
    assert.equal((await waitFor(1))[0].title, "Marie Dupont");
    assert.deepEqual((await server.call("DELETE", "/notifications")).json, { ok: true });
    assert.deepEqual((await server.call("GET", "/notifications")).json, { items: [] });
    assert.ok(!server.output().includes("secret-du-journal-42"));
  });

  it("refuse sans jeton", async () => {
    assert.equal((await server.call("GET", "/notifications", undefined, false)).status, 401);
    assert.equal((await server.call("DELETE", "/notifications", undefined, false)).status, 401);
  });
});

describe("notifications hors Mac et Windows", () => {
  it("répond 501", async () => {
    const other = await startServer({ os: "other" });
    try {
      assert.equal((await other.call("GET", "/notifications")).status, 501);
    } finally {
      await other.stop();
    }
  });
});
