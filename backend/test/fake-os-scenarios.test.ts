import assert from "node:assert/strict";
import { after, before, describe, it } from "node:test";
import type { AppWindow, Display, OpenWindows } from "../src/screens.ts";
import type { MediaSession } from "../src/win/index.ts";
import { startServer, type TestServer } from "./server.ts";

let server: TestServer;
before(async () => {
  server = await startServer({ os: "windows" });
});
after(async () => {
  await server?.stop();
});

const display: Display = { id: 1, label: "Écran 1", main: true, x: 0, y: 0, w: 1920, h: 1080 };
const window = (title: string): AppWindow => ({ index: 1, title, minimized: false, fullscreen: false, display: 1, elsewhere: false });

describe("Scénarios du faux pilotage", () => {
  it("rend les fenêtres ouvertes du scénario", async () => {
    const openWindows: OpenWindows = {
      apps: [{ key: "discord", name: "Discord", windows: [window("Général")] }],
      displays: [display],
    };
    server.setOs({ openWindows });
    const res = await server.call("GET", "/windows/open");
    assert.equal(res.status, 200);
    assert.deepEqual(
      res.json.apps.map((a: { name: string }) => a.name),
      ["Discord"],
    );
    assert.equal(res.json.apps[0].windows[0].title, "Général");
  });

  it("rend la lecture en cours du scénario", async () => {
    const session: MediaSession = {
      app: "Spotify.exe",
      name: "Spotify",
      current: true,
      title: "Titre",
      artist: "Artiste",
      album: "Album",
      hasThumb: false,
      status: "playing",
      canPlayPause: true,
      canNext: true,
      canPrevious: true,
      canSeek: true,
      positionMs: 1000,
      durationMs: 200000,
    };
    server.setOs({ media: [session] });
    const res = await server.call("GET", "/system/media");
    assert.equal(res.status, 200);
    assert.equal(res.json.sessions.length, 1);
    assert.equal(res.json.sessions[0].title, "Titre");
  });

  it("sans scénario : aucune fenêtre ouverte, aucune lecture", async () => {
    server.setOs({});
    assert.deepEqual((await server.call("GET", "/windows/open")).json.apps, []);
    assert.deepEqual((await server.call("GET", "/system/media")).json.sessions, []);
  });
});
