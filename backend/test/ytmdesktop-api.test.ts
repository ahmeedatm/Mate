import assert from "node:assert/strict";
import fs from "node:fs/promises";
import http from "node:http";
import type { AddressInfo } from "node:net";
import os from "node:os";
import path from "node:path";
import { after, before, describe, it } from "node:test";
import { startServer, type TestServer } from "./server.ts";

process.env.DATA_DIR = await fs.mkdtemp(path.join(os.tmpdir(), "companion-ytmd-"));
const { fromYtmd, parseSocketFrame, ytmdCommand } = await import("../src/ytmdesktop.ts");

const ytmdState = {
  player: {
    trackState: 1,
    videoProgress: 42,
    adPlaying: false,
    queue: {
      repeatMode: 1,
      selectedItemIndex: 1,
      items: [
        { title: "Aerodynamic", author: "Daft Punk", selected: false, thumbnails: [{ url: "https://i.ytimg.com/a-small.jpg", width: 60 }] },
        null,
        { title: "Digital Love", author: "Daft Punk", selected: true, thumbnails: [] },
      ],
    },
  },
  video: {
    title: "Digital Love",
    author: "Daft Punk",
    album: "Discovery",
    durationSeconds: 301,
    likeStatus: 2,
    thumbnails: [
      { url: "https://lh3.googleusercontent.com/small=w60-h60", width: 60 },
      { url: "https://lh3.googleusercontent.com/big=w544-h544", width: 544 },
    ],
  },
};

describe("YTMDesktop : traduction", () => {
  it("état : lecture, plus grande pochette, j'aime, répétition, file sans trous", () => {
    const s = fromYtmd(ytmdState);
    assert.equal(s.playing, true);
    assert.deepEqual(s.track, {
      title: "Digital Love",
      artist: "Daft Punk",
      album: "Discovery",
      cover: "https://lh3.googleusercontent.com/big=w544-h544",
      position: 42,
      duration: 301,
      like: "like",
    });
    assert.equal(s.repeat, "all");
    assert.deepEqual(
      s.queue.map((q) => [q.title, q.current]),
      [
        ["Aerodynamic", false],
        ["Digital Love", true],
      ],
    );
    assert.equal(fromYtmd({ player: { ...ytmdState.player, trackState: 0, queue: null }, video: null }).track, null);
  });

  it("commandes : liste fermée traduite, répétition suivante, pas de recherche", () => {
    assert.deepEqual(ytmdCommand({ op: "play-pause" }), { command: "playPause" });
    assert.deepEqual(ytmdCommand({ op: "seek", seconds: 30 }), { command: "seekTo", data: 30 });
    assert.deepEqual(ytmdCommand({ op: "play-queue", index: 2 }), { command: "playQueueIndex", data: 2 });
    assert.deepEqual(ytmdCommand({ op: "repeat" }, fromYtmd(ytmdState)), { command: "repeatMode", data: 2 });
    assert.equal(ytmdCommand({ op: "search", query: "daft" }), undefined);
  });

  it("trames Socket.IO : ouverture, ping, connexion, refus, évènement d'un autre namespace ignoré", () => {
    assert.equal(parseSocketFrame('0{"sid":"x","pingInterval":25000}').type, "open");
    assert.equal(parseSocketFrame("2").type, "ping");
    assert.equal(parseSocketFrame('40/api/v1/realtime,{"sid":"y"}').type, "connected");
    assert.equal(parseSocketFrame('44/api/v1/realtime,{"message":"Authentication not provided or invalid"}').type, "refused");
    assert.deepEqual(parseSocketFrame('42/api/v1/realtime,["state-update",{"a":1}]'), {
      type: "event",
      event: "state-update",
      data: { a: 1 },
    });
    assert.equal(parseSocketFrame('42/autre,["state-update",{}]').type, "other");
  });
});

// --- Parcours complet contre un faux YTMDesktop (HTTP seulement : sans temps réel, le compagnon relit /state).

let fake: http.Server;
const commands: unknown[] = [];
let server: TestServer;

before(async () => {
  fake = http.createServer((req, res) => {
    let body = "";
    req.on("data", (c) => (body += c));
    req.on("end", () => {
      const send = (status: number, json?: unknown) => {
        res.writeHead(status, { "content-type": "application/json" });
        res.end(json === undefined ? "" : JSON.stringify(json));
      };
      if (req.url === "/api/v1/auth/requestcode") return send(200, { code: "4821" });
      if (req.url === "/api/v1/auth/request") return send(200, { token: "jeton-ytmd" });
      if (req.headers.authorization !== "jeton-ytmd") return send(401, { error: "UNAUTHORIZED" });
      if (req.url === "/api/v1/state") return send(200, ytmdState);
      if (req.url === "/api/v1/command") {
        commands.push(JSON.parse(body));
        return send(204);
      }
      send(404, {});
    });
  });
  await new Promise<void>((resolve) => fake.listen(0, "127.0.0.1", resolve));
  server = await startServer({ os: "windows", env: { YTMD_URL: `http://127.0.0.1:${(fake.address() as AddressInfo).port}` } });
});
after(async () => {
  await server?.stop();
  fake?.close();
});

describe("YTMDesktop piloté depuis la tablette", () => {
  it("pas connecté : desktop off, rien n'est demandé à YTMDesktop", async () => {
    const res = await server.call("GET", "/ytmusic");
    assert.deepEqual(res.json, { open: false, desktop: { status: "off" } });
  });

  it("connexion : code à comparer, puis connecté sans que la tablette voie le jeton", async () => {
    const res = await server.call("POST", "/ytmusic/desktop/connect");
    assert.deepEqual(res.json, { desktop: { status: "connecting", code: "4821" } });
    let state = await server.call("GET", "/ytmusic");
    for (let i = 0; i < 20 && state.json.desktop?.status !== "connected"; i++) {
      await new Promise((r) => setTimeout(r, 100));
      state = await server.call("GET", "/ytmusic");
    }
    assert.equal(state.json.desktop.status, "connected");
    assert.ok(!JSON.stringify(state.json).includes("jeton-ytmd"));
    const stored = JSON.parse(await fs.readFile(path.join(server.dataDir, "ytmdesktop.json"), "utf8"));
    assert.equal(stored.token, "jeton-ytmd");
  });

  it("état de YTMDesktop servi à la tablette, commande traduite", async () => {
    // Relu au plus une fois par 5 s : on attend qu'il soit arrivé.
    let state = await server.call("GET", "/ytmusic");
    for (let i = 0; i < 60 && state.json.source !== "desktop"; i++) {
      await new Promise((r) => setTimeout(r, 100));
      state = await server.call("GET", "/ytmusic");
    }
    assert.equal(state.json.source, "desktop");
    assert.equal(state.json.track.title, "Digital Love");
    assert.equal((await server.call("POST", "/ytmusic/action", { op: "like" })).status, 200);
    assert.deepEqual(commands.at(-1), { command: "toggleLike" });
    const search = await server.call("POST", "/ytmusic/action", { op: "search", query: "daft punk" });
    assert.equal(search.status, 409);
    assert.match(search.json.error, /recherche/);
    assert.equal((await server.call("POST", "/ytmusic/action", { op: "play-queue", index: 9 })).status, 400);
  });

  it("oublier YTMDesktop : jeton effacé", async () => {
    assert.equal((await server.call("DELETE", "/ytmusic/desktop")).status, 200);
    assert.deepEqual((await server.call("GET", "/ytmusic")).json.desktop, { status: "off" });
    await assert.rejects(fs.access(path.join(server.dataDir, "ytmdesktop.json")));
  });
});
