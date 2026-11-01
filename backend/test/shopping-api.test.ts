import assert from "node:assert/strict";
import fs from "node:fs/promises";
import path from "node:path";
import { after, before, beforeEach, describe, it } from "node:test";
import { startServer, type TestServer } from "./server.ts";

let server: TestServer;
before(async () => {
  server = await startServer();
});
after(async () => {
  await server?.stop();
});
beforeEach(async () => {
  await fs.rm(path.join(server.dataDir, "shopping.json"), { force: true });
});

type Item = { id: string; label: string; done: boolean };
const items = async () => ((await server.call("GET", "/shopping")).json.items as Item[]).map(({ label, done }) => ({ label, done }));

describe("liste de courses", () => {
  it("refuse sans jeton", async () => {
    assert.equal((await server.call("GET", "/shopping", undefined, false)).status, 401);
  });

  it("est vide au départ", async () => {
    assert.deepEqual(await items(), []);
  });

  it("ajoute des articles nettoyés, dans l'ordre", async () => {
    const added = await server.call("POST", "/shopping", { labels: ["  Lait ", "Pain"] });
    assert.equal(added.status, 200);
    assert.deepEqual(
      added.json.items.map((i: Item) => i.label),
      ["Lait", "Pain"],
    );
    assert.deepEqual(await items(), [
      { label: "Lait", done: false },
      { label: "Pain", done: false },
    ]);
  });

  it("ne double pas un article déjà à acheter, même écrit autrement", async () => {
    await server.call("POST", "/shopping", { labels: ["Lait"] });
    await server.call("POST", "/shopping", { labels: ["lait ", "LAIT"] });
    assert.deepEqual(await items(), [{ label: "Lait", done: false }]);
  });

  it("redemander un article coché le remet à acheter", async () => {
    const id = (await server.call("POST", "/shopping", { labels: ["Lait"] })).json.items[0].id;
    await server.call("POST", `/shopping/${id}/done`, { done: true });
    await server.call("POST", "/shopping", { labels: ["lait"] });
    assert.deepEqual(await items(), [{ label: "Lait", done: false }]);
  });

  it("coche et décoche", async () => {
    const id = (await server.call("POST", "/shopping", { labels: ["Lait"] })).json.items[0].id;
    assert.equal((await server.call("POST", `/shopping/${id}/done`, { done: true })).status, 200);
    assert.deepEqual(await items(), [{ label: "Lait", done: true }]);
    await server.call("POST", `/shopping/${id}/done`, { done: false });
    assert.deepEqual(await items(), [{ label: "Lait", done: false }]);
  });

  it("répond 404 pour un article inconnu", async () => {
    assert.equal((await server.call("POST", "/shopping/nope/done", { done: true })).status, 404);
    assert.equal((await server.call("DELETE", "/shopping/nope")).status, 404);
  });

  it("supprime un article", async () => {
    const id = (await server.call("POST", "/shopping", { labels: ["Lait", "Pain"] })).json.items[0].id;
    assert.equal((await server.call("DELETE", `/shopping/${id}`)).status, 200);
    assert.deepEqual(await items(), [{ label: "Pain", done: false }]);
  });

  it("vide les articles cochés et garde les autres", async () => {
    const added = (await server.call("POST", "/shopping", { labels: ["Lait", "Pain", "Oeufs"] })).json.items as Item[];
    await server.call("POST", `/shopping/${added[0].id}/done`, { done: true });
    await server.call("POST", `/shopping/${added[2].id}/done`, { done: true });
    const cleared = await server.call("POST", "/shopping/clear-done", {});
    assert.equal(cleared.json.removed, 2);
    assert.deepEqual(await items(), [{ label: "Pain", done: false }]);
  });

  it("refuse un article vide ou une liste vide", async () => {
    assert.equal((await server.call("POST", "/shopping", { labels: [] })).status, 400);
    assert.equal((await server.call("POST", "/shopping", { labels: ["   "] })).status, 400);
  });

  it("garde la liste après un redémarrage de la lecture du fichier", async () => {
    await server.call("POST", "/shopping", { labels: ["Lait"] });
    const raw = JSON.parse(await fs.readFile(path.join(server.dataDir, "shopping.json"), "utf8"));
    assert.equal(raw.items[0].label, "Lait");
  });
});
