import assert from "node:assert/strict";
import { after, before, describe, it } from "node:test";
import { startServer, type TestServer } from "./server.ts";

let server: TestServer;
before(async () => {
  server = await startServer({ os: "windows" });
});
after(async () => {
  await server?.stop();
});

const EXTENSION = "chrome-extension://abcdefghijklmnop";

/** Requête comme l'extension du navigateur : en local, sans jeton, avec son origine. */
function asExtension(route: string, body: unknown, origin = EXTENSION) {
  return fetch(server.url + route, {
    method: "POST",
    headers: { "content-type": "application/json", origin },
    body: JSON.stringify(body),
  });
}

const state = {
  playing: true,
  track: {
    title: "  One   More Time ",
    artist: "Daft Punk",
    album: "Discovery",
    cover: "https://lh3.googleusercontent.com/abc=w60-h60-l90-rj",
    position: 12.5,
    duration: 320,
    like: "like",
  },
  repeat: "all",
  queue: [
    { title: "One More Time", subtitle: "Daft Punk", cover: "https://i.ytimg.com/vi/x/hq.jpg", current: true },
    { title: "Aerodynamic", subtitle: "Daft Punk", cover: "http://evil.example/x.png", current: false },
  ],
  query: null,
  results: [],
};

/** Attente d'ordres comme l'extension (long-polling), ouverte avant l'action de la tablette. */
async function listen(browser: string) {
  const res = await asExtension("/browser/extension/commands", { browser });
  return (await res.json()).commands;
}

describe("YouTube Music piloté depuis la tablette", () => {
  it("sans onglet : open false, action refusée (409) sauf la recherche vers un navigateur connu", async () => {
    assert.deepEqual((await server.call("GET", "/ytmusic")).json, { open: false, desktop: { status: "off" } });
    const res = await server.call("POST", "/ytmusic/action", { op: "next" });
    assert.equal(res.status, 409);
    assert.match(res.json.error, /Ouvre YouTube Music/);
  });

  it("état réservé à l'extension : page web refusée (403), ni jeton ni extension en WiFi", async () => {
    assert.equal((await asExtension("/browser/extension/ytmusic", { browser: "Edge", state }, "https://evil.example")).status, 403);
    assert.equal((await server.call("POST", "/browser/extension/ytmusic", { browser: "Edge", state }, false)).status, 403);
  });

  it("état reçu : textes nettoyés, pochettes de Google seulement, en grand", async () => {
    assert.equal((await asExtension("/browser/extension/ytmusic", { browser: "Opera GX", state })).status, 200);
    const res = await server.call("GET", "/ytmusic");
    assert.equal(res.status, 200);
    assert.equal(res.json.open, true);
    assert.equal(res.json.browser, "Opera GX");
    assert.equal(res.json.track.title, "One More Time");
    assert.equal(res.json.track.cover, "https://lh3.googleusercontent.com/abc=w544-h544-l90-rj");
    assert.equal(res.json.queue[0].cover, "https://i.ytimg.com/vi/x/hq.jpg");
    assert.equal(res.json.queue[1].cover, null);
    assert.equal(res.json.repeat, "all");
  });

  it("action relayée à l'extension du navigateur de l'onglet", async () => {
    const waiting = listen("Opera GX");
    await new Promise((r) => setTimeout(r, 100));
    const res = await server.call("POST", "/ytmusic/action", { op: "play-queue", index: 1 });
    assert.equal(res.status, 200);
    assert.deepEqual(await waiting, [{ action: "ytmusic", op: "play-queue", index: 1 }]);
  });

  it("champ en trop (script…) jamais transmis à l'extension", async () => {
    const waiting = listen("Opera GX");
    await new Promise((r) => setTimeout(r, 100));
    assert.equal((await server.call("POST", "/ytmusic/action", { op: "next", script: "alert(1)" })).status, 200);
    assert.deepEqual(await waiting, [{ action: "ytmusic", op: "next" }]);
  });

  it("liste fermée : action inconnue, index hors liste, recherche vide ou trop longue refusés (400)", async () => {
    for (const body of [
      { op: "eval" },
      { op: "play-queue", index: 5 },
      { op: "play-queue" },
      { op: "play-result", index: 0 },
      { op: "search", query: "" },
      { op: "search", query: " " },
      { op: "search", query: "x".repeat(201) },
      { op: "seek" },
    ]) {
      assert.equal((await server.call("POST", "/ytmusic/action", body)).status, 400, JSON.stringify(body));
    }
  });

  it("route de la tablette protégée par le jeton", async () => {
    assert.equal((await server.call("GET", "/ytmusic", undefined, false)).status, 401);
    assert.equal((await server.call("POST", "/ytmusic/action", { op: "next" }, false)).status, 401);
  });
});
