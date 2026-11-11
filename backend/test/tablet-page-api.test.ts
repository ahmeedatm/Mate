import assert from "node:assert/strict";
import { after, before, describe, it } from "node:test";
import { startServer, type TestServer } from "./server.ts";

// Page web en plein écran sur la tablette (maquettes de scripts/maquettes.mjs) : ordre `page`, route locale et outil MCP.

let server: TestServer;
before(async () => {
  server = await startServer();
});
after(async () => {
  await server.stop();
});

/** Prochain ordre `page` : la veille du premier plan peut en pousser d'autres avant (un poll à part). */
async function nextPage() {
  const deadline = Date.now() + 5_000;
  while (Date.now() < deadline) {
    const { json } = await server.call("POST", "/tablet/poll", { state: {} });
    const order = json.commands.find((c: { command: { type: string } }) => c.command.type === "page");
    if (order) return order;
  }
  throw new Error("Aucun ordre `page` reçu");
}

describe("page en plein écran sur la tablette", () => {
  // En premier : tant qu'aucune tablette n'a interrogé ce serveur, elle est déconnectée.
  it("sans tablette, la route locale répond 409", async () => {
    const post = await server.call("POST", "/local/tablet/page", { url: "http://192.168.1.5:4300/a/" }, false);
    assert.equal(post.status, 409);
    assert.equal(post.json.error, "Tablette non connectée");
  });

  it("la route locale envoie l'ordre, sans jeton depuis l'ordinateur", async () => {
    const polling = nextPage();
    await new Promise((r) => setTimeout(r, 300));
    const post = server.call("POST", "/local/tablet/page", { url: "http://127.0.0.1:4300/a/" }, false);
    const order = await polling;
    assert.deepEqual(order.command, { type: "page", url: "http://127.0.0.1:4300/a/" });
    await server.call("POST", "/tablet/result", { id: order.id, ok: true });
    assert.equal((await post).status, 200);
  });

  it("l'API (outil MCP) ferme la page avec url null, et refuse un ordre sans url", async () => {
    const polling = nextPage();
    await new Promise((r) => setTimeout(r, 300));
    const post = server.call("POST", "/tablet/command", { type: "page", url: null });
    const order = await polling;
    assert.deepEqual(order.command, { type: "page", url: null });
    await server.call("POST", "/tablet/result", { id: order.id, ok: true });
    assert.equal((await post).status, 200);

    const missing = await server.call("POST", "/tablet/command", { type: "page" });
    assert.equal(missing.status, 400);
    assert.match(missing.json.error, /url/);
  });

  it("refuse tout ce qui n'est pas http ou https", async () => {
    for (const url of ["file:///etc/passwd", "javascript:alert(1)", "intent://x", "ftp://a/b", "pas une url", ""]) {
      assert.equal((await server.call("POST", "/tablet/command", { type: "page", url })).status, 400, url);
      assert.equal((await server.call("POST", "/local/tablet/page", { url }, false)).status, 400, url);
    }
  });

  it("sans jeton, seulement une adresse du réseau local", async () => {
    for (const url of ["https://exemple.fr/", "http://8.8.8.8/", "http://172.32.0.1/", "http://192.168.1.5.exemple.fr/"]) {
      assert.equal((await server.call("POST", "/local/tablet/page", { url }, false)).status, 400, url);
    }
  });

  it("rend l'erreur de la tablette", async () => {
    const polling = nextPage();
    await new Promise((r) => setTimeout(r, 300));
    const post = server.call("POST", "/local/tablet/page", { url: "https://mac.local:4300/c.html" }, false);
    const order = await polling;
    await server.call("POST", "/tablet/result", { id: order.id, ok: false, error: "WebView absent" });
    const done = await post;
    assert.equal(done.status, 502);
    assert.equal(done.json.error, "WebView absent");
  });
});
