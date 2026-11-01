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
  await fs.rm(path.join(server.dataDir, "news.json"), { force: true });
});

describe("actualités", () => {
  it("refuse sans jeton", async () => {
    assert.equal((await server.call("GET", "/news", undefined, false)).status, 401);
    assert.equal((await server.call("POST", "/news/feed", { url: null }, false)).status, 401);
    assert.equal((await server.call("POST", "/news/open", { index: 0 }, false)).status, 401);
  });

  it("est vide tant qu'aucun flux n'est réglé", async () => {
    const res = await server.call("GET", "/news");
    assert.equal(res.status, 200);
    assert.deepEqual(res.json, { feed: null, title: null, items: [] });
  });

  it("garde l'adresse du flux réglée", async () => {
    assert.equal((await server.call("POST", "/news/feed", { url: "https://exemple.fr/rss" })).status, 200);
    const saved = JSON.parse(await fs.readFile(path.join(server.dataDir, "news.json"), "utf8"));
    assert.equal(saved.feed, "https://exemple.fr/rss");
  });

  it("refuse une adresse qui n'est pas http(s), ou avec un mot de passe", async () => {
    for (const url of ["file:///etc/passwd", "javascript:alert(1)", "https://moi:mdp@exemple.fr/rss", "pas une adresse"]) {
      const res = await server.call("POST", "/news/feed", { url });
      assert.equal(res.status, 400, url);
      assert.match(res.json.error, /adresse/i);
    }
  });

  it("dit qu'un flux injoignable ne répond pas, sans détail technique", async () => {
    await server.call("POST", "/news/feed", { url: "http://127.0.0.1:9/rss" });
    const res = await server.call("GET", "/news");
    assert.equal(res.status, 502);
    assert.doesNotMatch(res.json.error, /127\.0\.0\.1|ECONN/);
  });

  it("n'ouvre rien qui n'ait été montré", async () => {
    assert.equal((await server.call("POST", "/news/open", { index: 0 })).status, 404);
    assert.equal((await server.call("POST", "/news/open", { index: 9 })).status, 400);
  });
});
