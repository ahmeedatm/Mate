import assert from "node:assert/strict";
import { after, before, beforeEach, describe, it } from "node:test";
import type { Monitor } from "../src/screens.ts";
import { startServer, type TestServer } from "./server.ts";

const sets = (server: TestServer) => server.osCalls().filter((c) => c.fn === "setMonitor");

const screens: Monitor[] = [
  {
    id: "\\\\.\\DISPLAY1",
    name: "MSI G24C4",
    primary: true,
    width: 1920,
    height: 1080,
    hz: 144,
    modes: [{ width: 1920, height: 1080, hz: [144, 120, 60] }],
    ddc: true,
    brightness: { value: 80, max: 100 },
    contrast: { value: 70, max: 100 },
    preset: { value: 5, options: [5, 6, 11] },
  },
  { id: "\\\\.\\DISPLAY2", name: "Écran portable", primary: false, width: 1920, height: 1200, hz: 60, modes: [], ddc: false },
];

describe("Widget Écrans (Windows)", () => {
  let server: TestServer;
  before(async () => {
    server = await startServer({ os: "windows" });
  });
  after(async () => {
    await server?.stop();
  });
  beforeEach(() => {
    server.setOs({ monitors: screens });
    server.clearOsCalls();
  });

  it("liste les écrans et leurs réglages", async () => {
    const res = await server.call("GET", "/monitors");
    assert.equal(res.status, 200);
    assert.deepEqual(res.json.monitors, screens);
  });

  it("règle la luminosité et rend la liste à jour", async () => {
    const res = await server.call("POST", "/monitors", { id: "\\\\.\\DISPLAY1", control: "brightness", value: 40 });
    assert.equal(res.status, 200);
    assert.deepEqual(res.json.monitors[0].brightness, { value: 40, max: 100 });
    assert.deepEqual(sets(server)[0].args, [{ id: "\\\\.\\DISPLAY1", control: "brightness", value: 40 }]);
  });

  it("change la fréquence pour un mode proposé", async () => {
    const res = await server.call("POST", "/monitors", { id: "\\\\.\\DISPLAY1", width: 1920, height: 1080, hz: 60 });
    assert.equal(res.status, 200);
    assert.equal(res.json.monitors[0].hz, 60);
  });

  it("refuse un mode que l'écran ne propose pas, sans rien envoyer", async () => {
    const res = await server.call("POST", "/monitors", { id: "\\\\.\\DISPLAY1", width: 1920, height: 1080, hz: 240 });
    assert.equal(res.status, 400);
    assert.equal(sets(server).length, 0);
  });

  it("refuse un réglage inconnu, une valeur sans réglage, un identifiant d'une autre forme", async () => {
    for (const body of [
      { id: "\\\\.\\DISPLAY1", control: "volume", value: 3 },
      { id: "\\\\.\\DISPLAY1", value: 3 },
      { id: "C:\\Windows", control: "brightness", value: 3 },
      { id: "\\\\.\\DISPLAY1" },
    ]) {
      const res = await server.call("POST", "/monitors", body);
      assert.equal(res.status, 400, JSON.stringify(body));
    }
    assert.equal(sets(server).length, 0);
  });

  it("annonce le widget dans les fonctions de l'Accueil", async () => {
    const res = await server.call("GET", "/deck/features");
    assert.equal(res.json.monitors, true);
  });
});

describe("Widget Écrans (Mac)", () => {
  let server: TestServer;
  before(async () => {
    server = await startServer({ os: "mac" });
  });
  after(async () => {
    await server?.stop();
  });

  it("n'est pas proposé et répond 501", async () => {
    assert.equal((await server.call("GET", "/deck/features")).json.monitors, false);
    assert.equal((await server.call("GET", "/monitors")).status, 501);
  });
});
