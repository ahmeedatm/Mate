import assert from "node:assert/strict";
import { after, before, describe, it } from "node:test";
import { startServer, type TestServer } from "./server.ts";

// Panneau Tablette de la page locale : état de la tablette dans /local/status, réglages envoyés par l'ordre `settings`.

let server: TestServer;
before(async () => {
  server = await startServer();
});
after(async () => {
  await server.stop();
});

const SETTINGS = { theme: "CADRAN", brightness: 0.6, volume: 0.4, nightDim: true, focusDnd: true };
const STATE = {
  mode: "home",
  app: "1.4.0",
  name: "Tablette Unowhy 4.2",
  model: "Unowhy 4.2",
  screen: "1280x800",
  battery: { level: 82, charging: true },
  settings: SETTINGS,
};

describe("panneau Tablette", () => {
  // En premier : tant qu'aucune tablette n'a interrogé ce serveur, elle est déconnectée.
  it("sans tablette, la carte est masquée et un réglage est refusé", async () => {
    const status = await server.call("GET", "/local/status", undefined, false);
    assert.equal(status.json.tabletInfo, null);
    const put = await server.call("PUT", "/local/tablet/settings", { nightDim: false }, false);
    assert.equal(put.status, 409);
    assert.equal((await server.call("POST", "/local/tablet/update", { action: "check" }, false)).status, 409);
    assert.equal(put.json.error, "Tablette non connectée");
  });

  it("envoie le réglage à la tablette, sans jeton depuis l'ordinateur", async () => {
    const polling = server.call("POST", "/tablet/poll", { state: STATE });
    await new Promise((r) => setTimeout(r, 300));

    const status = await server.call("GET", "/local/status", undefined, false);
    assert.deepEqual(status.json.tabletInfo, {
      app: "1.4.0",
      name: "Tablette Unowhy 4.2",
      model: "Unowhy 4.2",
      screen: "1280x800",
      battery: { level: 82, charging: true },
      settings: SETTINGS,
      update: null,
    });

    const put = server.call("PUT", "/local/tablet/settings", { theme: "RETRO", brightness: 0.3 }, false);
    const [order] = (await polling).json.commands;
    assert.deepEqual(order.command, { type: "settings", theme: "RETRO", brightness: 0.3 });

    const state = { ...STATE, settings: { ...SETTINGS, theme: "RETRO", brightness: 0.3 } };
    await server.call("POST", "/tablet/result", { id: order.id, ok: true, state });
    const done = await put;
    assert.equal(done.status, 200);
    // Réponse avec l'état renvoyé par la tablette : la page se met à jour sans attendre le poll suivant.
    assert.equal(done.json.tablet.settings.theme, "RETRO");
  });

  it("accepte le thème Contraste élevé", async () => {
    const polling = server.call("POST", "/tablet/poll", { state: STATE });
    await new Promise((r) => setTimeout(r, 300));
    const put = server.call("PUT", "/local/tablet/settings", { theme: "CONTRASTE" }, false);
    const [order] = (await polling).json.commands;
    assert.deepEqual(order.command, { type: "settings", theme: "CONTRASTE" });
    await server.call("POST", "/tablet/result", {
      id: order.id,
      ok: true,
      state: { ...STATE, settings: { ...SETTINGS, theme: "CONTRASTE" } },
    });
    assert.equal((await put).status, 200);
  });

  it("rend l'erreur de la tablette", async () => {
    const polling = server.call("POST", "/tablet/poll", { state: STATE });
    await new Promise((r) => setTimeout(r, 300));
    const put = server.call("PUT", "/local/tablet/settings", { volume: 0 }, false);
    const [order] = (await polling).json.commands;
    await server.call("POST", "/tablet/result", { id: order.id, ok: false, error: "Volume indisponible" });
    const done = await put;
    assert.equal(done.status, 502);
    assert.equal(done.json.error, "Volume indisponible");
  });

  it("refuse un réglage hors bornes, inconnu ou vide", async () => {
    for (const body of [{ brightness: 0 }, { volume: 1.5 }, { theme: "NEON" }, { kiosk: false }, {}]) {
      const put = await server.call("PUT", "/local/tablet/settings", body, false);
      assert.equal(put.status, 400, JSON.stringify(body));
    }
  });

  it("l'ordre settings n'est pas ouvert à l'extension MCP", async () => {
    const put = await server.call("POST", "/tablet/command", { type: "settings" });
    assert.equal(put.status, 400);
  });

  it("demande à la tablette de chercher une mise à jour et rend son état", async () => {
    const polling = server.call("POST", "/tablet/poll", { state: STATE });
    await new Promise((r) => setTimeout(r, 300));
    const post = server.call("POST", "/local/tablet/update", { action: "check" }, false);
    const [order] = (await polling).json.commands;
    assert.deepEqual(order.command, { type: "update", action: "check" });

    const update = { checked: true, available: "0.2.12", installing: false, error: null, kiosk: true };
    await server.call("POST", "/tablet/result", { id: order.id, ok: true, state: { ...STATE, update } });
    const done = await post;
    assert.equal(done.status, 200);
    assert.deepEqual(done.json.tablet.update, update);
  });

  it("refuse une action de mise à jour inconnue, et l'ordre update depuis l'extension MCP", async () => {
    assert.equal((await server.call("POST", "/local/tablet/update", { action: "rollback" }, false)).status, 400);
    assert.equal((await server.call("POST", "/local/tablet/update", {}, false)).status, 400);
    assert.equal((await server.call("POST", "/tablet/command", { type: "update" })).status, 400);
  });
});
