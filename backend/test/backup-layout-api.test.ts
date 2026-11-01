import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { after, before, describe, it } from "node:test";
import { startServer, type TestServer } from "./server.ts";

// Sauvegarde, partie tablette : l'export reprend le dernier état lu de la tablette (profils, widgets, barres) et
// l'import le lui renvoie par l'ordre `layout` `set`.

const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), "companion-backup-layout-"));
let server: TestServer;
before(async () => {
  server = await startServer({ dataDir });
});
after(async () => {
  await server.stop();
  fs.rmSync(dataDir, { recursive: true, force: true });
});

const STATE = { mode: "home", app: "1.4.0", name: "Tablette", model: "Tab 4.2", screen: "1280x800", battery: null, settings: {} };
const LAYOUT = { v: 2, names: ["Accueil", "Réunion"], widgets: [{ t: "clock", s: "L", p: 0, c: 0, r: 0, cfg: {} }] };
const DATA = {
  layout: LAYOUT,
  topBar: { items: [{ key: "clock", label: "Heure" }], addable: [] },
  rail: { labels: false, tools: [{ key: "focus", label: "Focus" }], available: [] },
};

/** La tablette interroge, reçoit l'ordre, y répond par `reply`. */
async function tabletAnswers(call: Promise<{ status: number; json: any }>, reply: (command: any) => object) {
  const [order] = (await server.call("POST", "/tablet/poll", { state: STATE })).json.commands;
  await server.call("POST", "/tablet/result", { id: order.id, ...reply(order.command), state: STATE });
  return { order: order.command, done: await call };
}

describe("sauvegarde de la disposition de la tablette", () => {
  it("l'exporte après une lecture, et la renvoie à la tablette à l'import", async () => {
    void server.call("POST", "/tablet/poll", { state: STATE });
    await new Promise((r) => setTimeout(r, 200));
    const read = server.call("GET", "/local/tablet/layout", undefined, false);
    await tabletAnswers(read, () => ({ ok: true, data: DATA }));

    const backup = (await server.call("GET", "/local/config/backup", undefined, false)).json;
    assert.deepEqual(backup.parts.layout, { layout: LAYOUT, topBar: ["clock"], rail: { labels: false, tools: ["focus"] } });

    const post = server.call("POST", "/local/config/backup", { backup, parts: ["layout"] }, false);
    const { order, done } = await tabletAnswers(post, () => ({ ok: true, data: DATA }));
    assert.deepEqual(order, { type: "layout", action: "set", ...backup.parts.layout });
    assert.deepEqual(done.json, { imported: ["layout"], skipped: {}, notes: {} });
  });
});
