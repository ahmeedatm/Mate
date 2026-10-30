import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { after, before, describe, it } from "node:test";
import { startServer, type TestServer } from "./server.ts";

// Page locale, section Profils : GET/PUT /local/tablet/layout relaient l'ordre `layout` (get / set) à la tablette et
// gardent son dernier état (tablet-layout.json) pour l'afficher en lecture seule quand elle est éteinte.

const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), "companion-layout-"));
let server: TestServer;
before(async () => {
  server = await startServer({ dataDir });
});
after(async () => {
  await server.stop();
  fs.rmSync(dataDir, { recursive: true, force: true });
});

const STATE = { mode: "home", app: "1.4.0", name: "Tablette", model: "Unowhy 4.2", screen: "1280x800", battery: null, settings: {} };
const LAYOUT = {
  v: 2,
  pages: 2,
  names: ["Accueil", "Réunion"],
  icons: [null, null],
  home: 0,
  deck: -1,
  call: 1,
  roles: true,
  widgets: [{ t: "clock", s: "L", p: 0, c: 0, r: 0, cfg: {} }],
};
const DATA = {
  layout: LAYOUT,
  cadran: true,
  computerId: "pc-1",
  nameMax: 30,
  cols: 4,
  rows: 4,
  sizes: [{ key: "S", w: 1, h: 1, label: "Petit" }],
  catalog: [{ key: "clock", title: "Horloge", computer: false, offered: true, sizes: [{ key: "L", w: 2, h: 2, label: "Grand" }] }],
  topBar: { items: [{ key: "clock", label: "Heure" }], addable: [{ key: "date", label: "Date" }] },
  rail: { labels: true, tools: [{ key: "focus", label: "Focus" }], available: [] },
};

/** La tablette interroge, reçoit l'ordre, y répond par `reply`. */
async function tabletAnswers(call: Promise<{ status: number; json: any }>, reply: (command: any) => object) {
  const polling = server.call("POST", "/tablet/poll", { state: STATE });
  const [order] = (await polling).json.commands;
  const answer = reply(order.command);
  await server.call("POST", "/tablet/result", { id: order.id, ...answer, state: STATE });
  return { order: order.command, done: await call };
}

/** La tablette interroge une première fois : elle est vue (connectée), le poll suivant reçoit l'ordre. */
async function connect() {
  void server.call("POST", "/tablet/poll", { state: STATE });
  await new Promise((r) => setTimeout(r, 200));
}

describe("profils et barres de la page locale", () => {
  // En premier : tant qu'aucune tablette n'a interrogé ce serveur, elle est déconnectée et rien n'a été lu.
  it("sans tablette ni lecture, rend un état vide en lecture seule, et refuse l'envoi", async () => {
    const get = await server.call("GET", "/local/tablet/layout", undefined, false);
    assert.equal(get.status, 200);
    assert.equal(get.json.readOnly, true);
    assert.equal(get.json.empty, true);
    assert.ok(Array.isArray(get.json.deck));
    const put = await server.call("PUT", "/local/tablet/layout", { topBar: ["clock"] }, false);
    assert.equal(put.status, 409);
    assert.equal(put.json.error, "Tablette non connectée");
  });

  it("lit la disposition de la tablette, avec les pages de touches du Deck, et la garde", async () => {
    await connect();
    const get = server.call("GET", "/local/tablet/layout", undefined, false);
    const { order, done } = await tabletAnswers(get, () => ({ ok: true, data: DATA }));
    assert.deepEqual(order, { type: "layout", action: "get" });
    assert.equal(done.status, 200);
    assert.equal(done.json.readOnly, false);
    assert.equal(done.json.computerId, "pc-1");
    assert.deepEqual(done.json.layout.names, ["Accueil", "Réunion"]);
    assert.equal(typeof done.json.at, "number");
    // Pages du Deck de l'ordinateur : titre + touches (id, label, glyph, color, symbol).
    assert.ok(done.json.deck.length > 0);
    const tile = done.json.deck.flatMap((p: any) => p.tiles)[0];
    for (const key of ["id", "label", "glyph", "color", "symbol"]) assert.ok(key in tile, key);
    assert.equal(fs.existsSync(path.join(dataDir, "tablet-layout.json")), true);
  });

  it("envoie la disposition à la tablette et rend son nouvel état", async () => {
    const change = {
      layout: { ...LAYOUT, widgets: [] },
      topBar: ["clock", "date"],
      rail: { labels: false, tools: ["focus"] },
    };
    const put = server.call("PUT", "/local/tablet/layout", change, false);
    const next = { ...DATA, layout: change.layout };
    const { order, done } = await tabletAnswers(put, () => ({ ok: true, data: next }));
    assert.deepEqual(order, { type: "layout", action: "set", ...change });
    assert.equal(done.status, 200);
    assert.deepEqual(done.json.layout.widgets, []);
    assert.equal(done.json.readOnly, false);
  });

  it("rend l'erreur en français de la tablette, sans toucher au cache", async () => {
    const put = server.call("PUT", "/local/tablet/layout", { layout: { ...LAYOUT, widgets: [{ t: "x" }] } }, false);
    const { done } = await tabletAnswers(put, () => ({ ok: false, error: "Widget inconnu : x (mettre à jour l'appli ?)." }));
    assert.equal(done.status, 502);
    assert.equal(done.json.error, "Widget inconnu : x (mettre à jour l'appli ?).");
  });

  it("refuse un corps hors schéma : vide, inconnu, mal typé", async () => {
    const bodies = [
      {},
      { kiosk: true },
      { topBar: [{}] },
      { rail: { labels: "oui" } },
      { layout: { names: ["a"] } },
      { layout: { names: [{}], widgets: [] } },
      { layout: { names: ["a"], widgets: ["x"] } },
      [],
    ];
    for (const body of bodies) {
      const put = await server.call("PUT", "/local/tablet/layout", body, false);
      assert.equal(put.status, 400, JSON.stringify(body));
    }
  });

  it("l'ordre layout n'est pas ouvert à l'extension MCP", async () => {
    assert.equal((await server.call("POST", "/tablet/command", { type: "layout" })).status, 400);
  });

  it("après un redémarrage, tablette éteinte : dernier état lu, en lecture seule", async () => {
    await server.stop();
    server = await startServer({ dataDir });
    const get = await server.call("GET", "/local/tablet/layout", undefined, false);
    assert.equal(get.status, 200);
    assert.equal(get.json.readOnly, true);
    assert.equal(get.json.empty, undefined);
    assert.equal(get.json.computerId, "pc-1");
    assert.deepEqual(get.json.layout.names, ["Accueil", "Réunion"]);
    assert.ok(get.json.at > 0);
    assert.ok(get.json.deck.length > 0);
  });
});
