import assert from "node:assert/strict";
import http from "node:http";
import type { AddressInfo } from "node:net";
import { after, before, describe, it } from "node:test";
import { mainArtist, parseLrc } from "../src/lyrics.ts";
import { startServer, type TestServer } from "./server.ts";

let server: TestServer;
/** Faux LRCLIB : « One More Time » seulement par la recherche libre (durée proche), synchronisée. */
let lrclib: http.Server;
const lrclibCalls: string[] = [];
before(async () => {
  lrclib = http.createServer((req, res) => {
    lrclibCalls.push(req.url ?? "");
    res.setHeader("content-type", "application/json");
    if (req.url?.startsWith("/api/search"))
      return res.end(
        JSON.stringify([
          { duration: 200, syncedLyrics: "[00:01.00]Mauvais titre" },
          { duration: 321, syncedLyrics: "[00:12.50]One more time\n[00:15.00][00:30.00]We're gonna celebrate\n[00:20.00]" },
        ]),
      );
    res.statusCode = 404;
    res.end(JSON.stringify({ code: 404, name: "TrackNotFound" }));
  });
  await new Promise<void>((resolve) => lrclib.listen(0, "127.0.0.1", resolve));
  server = await startServer({
    os: "windows",
    env: { LYRICS_URL: `http://127.0.0.1:${(lrclib.address() as AddressInfo).port}/api` },
  });
  server.setOs({ installed: ["YouTube Music Desktop App"] });
});
after(async () => {
  await server?.stop();
  lrclib?.close();
});

describe("paroles au format LRC", () => {
  it("une ligne par heure (plusieurs heures sur une ligne), triées ; ligne sans heure ignorée", () => {
    assert.deepEqual(parseLrc("[ti:Titre]\n[00:15.00][00:30.00]Refrain\n[00:12.50]Début\nsans heure\n[01:02.5]Fin"), [
      { t: 12.5, text: "Début" },
      { t: 15, text: "Refrain" },
      { t: 30, text: "Refrain" },
      { t: 62.5, text: "Fin" },
    ]);
  });

  it("artiste principal d'un titre à plusieurs", () => {
    assert.equal(mainArtist("Orelsan & Yamê"), "Orelsan");
    assert.equal(mainArtist("Daft Punk feat. Pharrell Williams"), "Daft Punk");
    assert.equal(mainArtist("Stromae, Pomme"), "Stromae");
    assert.equal(mainArtist("Lomepal"), "Lomepal");
  });
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

  it("paroles du titre en cours : exacte, sans album, puis recherche libre à durée proche ; gardées en cache", async () => {
    const res = await server.call("GET", "/ytmusic/lyrics");
    assert.equal(res.status, 200);
    assert.equal(res.json.found, true);
    assert.equal(res.json.synced, true);
    assert.deepEqual(res.json.lines.slice(0, 2), [
      { t: 12.5, text: "One more time" },
      { t: 15, text: "We're gonna celebrate" },
    ]);
    assert.deepEqual(
      lrclibCalls.map((u) => u.split("?")[0]),
      ["/api/get", "/api/get", "/api/search"],
    );
    assert.match(lrclibCalls[0], /album_name=Discovery/);
    await server.call("GET", "/ytmusic/lyrics");
    assert.equal(lrclibCalls.length, 3);
  });

  it("ouvrir YouTube Music sur l'ordinateur : l'appli installée, sinon le site", async () => {
    server.clearOsCalls();
    // Onglet du navigateur actif, YTMDesktop pas relié : le site.
    assert.deepEqual((await server.call("POST", "/ytmusic/open")).json, { opened: "site" });
    assert.deepEqual(
      server
        .osCalls()
        .filter((c) => c.fn === "openTarget")
        .map((c) => c.args[0]),
      ["https://music.youtube.com/"],
    );
  });

  it("pochette : seulement une adresse de l'état en cours (pas de relais vers une adresse quelconque)", async () => {
    const res = await server.call("GET", "/ytmusic/cover?url=" + encodeURIComponent("https://lh3.googleusercontent.com/autre=w544-h544"));
    assert.equal(res.status, 404);
    const local = await server.call("GET", "/ytmusic/cover?url=" + encodeURIComponent("http://127.0.0.1:3000/health"));
    assert.equal(local.status, 404);
    assert.equal((await server.call("GET", "/ytmusic/cover")).status, 400);
  });

  it("route de la tablette protégée par le jeton", async () => {
    assert.equal((await server.call("GET", "/ytmusic", undefined, false)).status, 401);
    assert.equal((await server.call("POST", "/ytmusic/action", { op: "next" }, false)).status, 401);
  });
});
