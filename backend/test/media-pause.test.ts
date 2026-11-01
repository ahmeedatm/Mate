import assert from "node:assert/strict";
import { after, before, beforeEach, describe, it } from "node:test";
import type { MediaSession } from "../src/win/index.ts";
import { startServer, type TestServer } from "./server.ts";

let server: TestServer;
before(async () => {
  server = await startServer({ os: "windows" });
});
after(async () => {
  await server?.stop();
});
beforeEach(() => server.clearOsCalls());

const session = (app: string, status: string, canPlayPause = true): MediaSession => ({
  app,
  name: app,
  current: true,
  title: "Titre",
  artist: "Artiste",
  album: "",
  hasThumb: false,
  status,
  canPlayPause,
  canNext: true,
  canPrevious: true,
  canSeek: true,
  positionMs: 1000,
  durationMs: 200000,
});

const controls = () => server.osCalls().filter((c) => c.fn === "mediaControl");

describe("POST /system/media/pause (minuteur de la tablette)", () => {
  it("met en pause ce qui joue, et seulement cela", async () => {
    server.setOs({ media: [session("Spotify.exe", "playing"), session("Chrome.exe", "paused")] });
    const res = await server.call("POST", "/system/media/pause", {});
    assert.equal(res.status, 200);
    assert.equal(res.json.paused, 1);
    assert.deepEqual(
      controls().map((c) => c.args),
      [["play-pause", "Spotify.exe"]],
    );
  });

  it("ne touche à rien quand rien ne joue (sinon la touche relancerait la musique)", async () => {
    server.setOs({ media: [session("Spotify.exe", "paused")] });
    const res = await server.call("POST", "/system/media/pause", {});
    assert.equal(res.status, 200);
    assert.equal(res.json.paused, 0);
    assert.equal(controls().length, 0);
  });

  it("laisse une lecture qu'on ne sait pas mettre en pause", async () => {
    server.setOs({ media: [session("Radio.exe", "playing", false)] });
    const res = await server.call("POST", "/system/media/pause", {});
    assert.equal(res.json.paused, 0);
    assert.equal(controls().length, 0);
  });

  it("exige le jeton", async () => {
    const res = await server.call("POST", "/system/media/pause", {}, false);
    assert.equal(res.status, 401);
  });
});
