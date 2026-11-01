import assert from "node:assert/strict";
import { after, before, describe, it } from "node:test";
import { startServer, type TestServer } from "./server.ts";

// Faire sonner la tablette pour la retrouver : ordre `ring`, bouton de la page locale et outil MCP.

let server: TestServer;
before(async () => {
  server = await startServer();
});
after(async () => {
  await server.stop();
});

/** Prochain ordre `ring` : la veille du premier plan peut en pousser d'autres avant (un poll à part). */
async function nextRing() {
  for (;;) {
    const { json } = await server.call("POST", "/tablet/poll", { state: {} });
    const order = json.commands.find((c: { command: { type: string } }) => c.command.type === "ring");
    if (order) return order;
  }
}

describe("faire sonner la tablette", () => {
  // En premier : tant qu'aucune tablette n'a interrogé ce serveur, elle est déconnectée.
  it("sans tablette, la page locale répond 409", async () => {
    const post = await server.call("POST", "/local/tablet/ring", { on: true }, false);
    assert.equal(post.status, 409);
    assert.equal(post.json.error, "Tablette non connectée");
  });

  it("la page locale envoie l'ordre, sans jeton depuis l'ordinateur", async () => {
    const polling = nextRing();
    await new Promise((r) => setTimeout(r, 300));
    const post = server.call("POST", "/local/tablet/ring", { on: true }, false);
    const order = await polling;
    assert.deepEqual(order.command, { type: "ring", on: true });
    await server.call("POST", "/tablet/result", { id: order.id, ok: true });
    assert.equal((await post).status, 200);
  });

  it("l'API (outil MCP) envoie l'ordre d'arrêt, et refuse un ordre sans `on`", async () => {
    const polling = nextRing();
    await new Promise((r) => setTimeout(r, 300));
    const post = server.call("POST", "/tablet/command", { type: "ring", on: false });
    const order = await polling;
    assert.deepEqual(order.command, { type: "ring", on: false });
    await server.call("POST", "/tablet/result", { id: order.id, ok: true });
    assert.equal((await post).status, 200);

    const missing = await server.call("POST", "/tablet/command", { type: "ring" });
    assert.equal(missing.status, 400);
    assert.match(missing.json.error, /on/);
  });

  it("rend l'erreur de la tablette", async () => {
    const polling = nextRing();
    await new Promise((r) => setTimeout(r, 300));
    const post = server.call("POST", "/local/tablet/ring", { on: true }, false);
    const order = await polling;
    await server.call("POST", "/tablet/result", { id: order.id, ok: false, error: "Son indisponible" });
    const done = await post;
    assert.equal(done.status, 502);
    assert.equal(done.json.error, "Son indisponible");
  });

  it("refuse un corps sans `on` booléen", async () => {
    for (const body of [{}, { on: "oui" }]) {
      assert.equal((await server.call("POST", "/local/tablet/ring", body, false)).status, 400, JSON.stringify(body));
    }
  });
});
