import assert from "node:assert/strict";
import fs from "node:fs/promises";
import path from "node:path";
import { after, before, beforeEach, describe, it } from "node:test";
import type { AppWindow, Display, OpenWindows } from "../src/screens.ts";
import { startServer, type TestServer } from "./server.ts";

const display = (id: number): Display => ({ id, label: `Écran ${id}`, main: id === 1, x: (id - 1) * 1920, y: 0, w: 1920, h: 1080 });
const win = (index: number, title: string, over: Partial<AppWindow> = {}): AppWindow => ({
  index,
  title,
  minimized: false,
  fullscreen: false,
  display: 1,
  elsewhere: false,
  ...over,
});
const scene = (apps: OpenWindows["apps"]): OpenWindows => ({ apps, displays: [display(1), display(2)] });

/** Ce que le faux pilotage a reçu : appli, titre, action (et écran pour `place`). */
const moves = (s: TestServer) =>
  s
    .osCalls()
    .filter((c) => c.fn === "controlOpenWindow")
    .map((c) => {
      const [key, window, action, opts] = c.args as [string, { title: string }, string, { display: number; layout: string } | undefined];
      return opts ? `${action} ${key}/${window.title} -> ${opts.display} ${opts.layout}` : `${action} ${key}/${window.title}`;
    });

let server: TestServer;
before(async () => {
  server = await startServer({ os: "windows" });
});
after(async () => {
  await server?.stop();
});
beforeEach(async () => {
  await fs.rm(path.join(server.dataDir, "away.json"), { force: true });
  server.clearOsCalls();
});

describe("Je m'absente / Reprendre", () => {
  it("start garde les fenêtres ouvertes sans rien toucher, resume les remet devant, la plus en avant en dernier", async () => {
    server.setOs({
      openWindows: scene([
        { key: "mail", name: "Courrier", windows: [win(1, "Boîte", { front: true })] },
        { key: "doc", name: "Document", windows: [win(2, "Rapport"), win(3, "Notes", { display: 2 })] },
        {
          key: "min",
          name: "Réduite",
          windows: [win(4, "Cachée", { minimized: true }), win(5, "Ailleurs", { elsewhere: true, display: null })],
        },
      ]),
    });
    const started = await server.call("POST", "/away/start");
    assert.equal(started.status, 200);
    assert.deepEqual(started.json, { saved: 3 });
    assert.deepEqual(moves(server), []);
    assert.deepEqual((await server.call("GET", "/away")).json, { saved: true, count: 3 });

    const resumed = await server.call("POST", "/away/resume");
    assert.equal(resumed.status, 200);
    assert.deepEqual(resumed.json, { restored: 3, saved: true });
    assert.deepEqual(moves(server), ["focus doc/Rapport", "focus doc/Notes", "focus mail/Boîte"]);
    assert.deepEqual((await server.call("GET", "/away")).json, { saved: false, count: 0 });
  });

  it("une fenêtre disparue ou au titre changé est ignorée, les autres sont remises", async () => {
    const before = scene([{ key: "doc", name: "Document", windows: [win(2, "Rapport"), win(3, "Notes", { front: true })] }]);
    server.setOs({ openWindows: before });
    await server.call("POST", "/away/start");
    server.setOs({ openWindows: scene([{ key: "doc", name: "Document", windows: [win(9, "Notes", { front: true })] }]) });
    const resumed = await server.call("POST", "/away/resume");
    assert.deepEqual(resumed.json, { restored: 1, saved: true });
    assert.deepEqual(moves(server), ["focus doc/Notes"]);
  });

  it("une fenêtre qui a changé d'écran revient sur le sien avant d'être mise devant", async () => {
    server.setOs({ openWindows: scene([{ key: "doc", name: "Document", windows: [win(2, "Rapport", { display: 2, front: true })] }]) });
    await server.call("POST", "/away/start");
    server.setOs({ openWindows: scene([{ key: "doc", name: "Document", windows: [win(2, "Rapport", { display: 1, front: true })] }]) });
    await server.call("POST", "/away/resume");
    assert.deepEqual(moves(server), ["place doc/Rapport -> 2 keep", "focus doc/Rapport"]);
  });

  it("resume sans start : rien à remettre, aucune fenêtre touchée", async () => {
    server.setOs({ openWindows: scene([{ key: "doc", name: "Document", windows: [win(2, "Rapport", { front: true })] }]) });
    const res = await server.call("POST", "/away/resume");
    assert.equal(res.status, 200);
    assert.deepEqual(res.json, { restored: 0, saved: false });
    assert.deepEqual(moves(server), []);
  });

  it("resume ne remet qu'une fois", async () => {
    server.setOs({ openWindows: scene([{ key: "doc", name: "Document", windows: [win(2, "Rapport", { front: true })] }]) });
    await server.call("POST", "/away/start");
    await server.call("POST", "/away/resume");
    server.clearOsCalls();
    assert.deepEqual((await server.call("POST", "/away/resume")).json, { restored: 0, saved: false });
    assert.deepEqual(moves(server), []);
  });

  it("une fenêtre qui résiste n'arrête pas les autres", async () => {
    server.setOs({
      openWindows: scene([{ key: "doc", name: "Document", windows: [win(2, "Rapport"), win(3, "Notes", { front: true })] }]),
      fail: ["controlOpenWindow doc"],
    });
    await server.call("POST", "/away/start");
    const resumed = await server.call("POST", "/away/resume");
    assert.equal(resumed.status, 200);
    assert.equal(resumed.json.restored, 0);
  });

  it("le jeton est exigé", async () => {
    for (const [method, route] of [
      ["GET", "/away"],
      ["POST", "/away/start"],
      ["POST", "/away/resume"],
    ]) {
      assert.equal((await server.call(method, route, undefined, false)).status, 401, route);
    }
  });
});
