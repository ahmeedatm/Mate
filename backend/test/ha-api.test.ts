import assert from "node:assert/strict";
import http from "node:http";
import type { AddressInfo } from "node:net";
import { after, before, describe, it } from "node:test";
import { startServer, type TestServer } from "./server.ts";

const HA_TOKEN = "jeton-ha";

type Seen = { method: string; url: string; body: any };
const seen: Seen[] = [];
let authFails = false;
const STATES = [
  { entity_id: "light.salon", state: "on", attributes: { friendly_name: "Lampe du salon" } },
  { entity_id: "switch.cafetiere", state: "off", attributes: { friendly_name: "Cafetière" } },
  { entity_id: "scene.film", state: "2026-11-01T10:00:00+00:00", attributes: { friendly_name: "Soirée film" } },
  { entity_id: "light.bureau", state: "unavailable", attributes: {} },
  { entity_id: "lock.porte", state: "locked", attributes: { friendly_name: "Porte" } },
  { entity_id: "light.cave", state: "on", attributes: { friendly_name: "Cave (hors liste)" } },
];
const haServer = http.createServer((req, res) => {
  let raw = "";
  req.on("data", (d) => (raw += d));
  req.on("end", () => {
    const send = (status: number, body?: unknown) => {
      res.writeHead(status, { "content-type": "application/json" });
      res.end(body === undefined ? undefined : JSON.stringify(body));
    };
    if (authFails || req.headers.authorization !== `Bearer ${HA_TOKEN}`) return send(401, {});
    const url = req.url ?? "";
    seen.push({ method: req.method ?? "", url, body: raw ? JSON.parse(raw) : undefined });
    if (req.method === "GET" && url === "/api/states") return send(200, STATES);
    if (req.method === "POST" && /^\/api\/services\/[a-z_]+\/[a-z_]+$/.test(url)) return send(200, []);
    send(404, {});
  });
});

let site = "";
let server: TestServer;
let bare: TestServer;
before(async () => {
  await new Promise<void>((r) => haServer.listen(0, "127.0.0.1", r));
  site = `http://127.0.0.1:${(haServer.address() as AddressInfo).port}`;
  server = await startServer({
    env: {
      HA_URL: site,
      HA_TOKEN,
      // `lock.porte` (domaine non géré), `light.absente` (inconnue de Home Assistant) et un identifiant faux restent sans effet ou grisés.
      HA_ENTITIES: "light.salon, switch.cafetiere,scene.film,light.bureau,lock.porte,light.absente,pas un id",
    },
  });
  bare = await startServer({ env: { HA_URL: "", HA_TOKEN: "", HA_ENTITIES: "" } });
});
after(async () => {
  await server?.stop();
  await bare?.stop();
  haServer.close();
});

describe("home assistant : API", () => {
  it("/deck/features annonce ha seulement si configuré", async () => {
    assert.equal((await server.call("GET", "/deck/features")).json.ha, true);
    assert.equal((await bare.call("GET", "/deck/features")).json.ha, false);
  });

  it("sans configuration : 501", async () => {
    assert.equal((await bare.call("GET", "/ha/entities")).status, 501);
    assert.equal((await bare.call("POST", "/ha/entities/light.salon/toggle")).status, 501);
  });

  it("jeton d'appairage exigé", async () => {
    assert.equal((await server.call("GET", "/ha/entities", undefined, false)).status, 401);
  });

  it("GET /ha/entities : la liste blanche seulement, dans son ordre, avec l'état", async () => {
    seen.length = 0;
    const { status, json } = await server.call("GET", "/ha/entities");
    assert.equal(status, 200);
    assert.deepEqual(json.entities, [
      { id: "light.salon", name: "Lampe du salon", kind: "toggle", on: true, available: true },
      { id: "switch.cafetiere", name: "Cafetière", kind: "toggle", on: false, available: true },
      { id: "scene.film", name: "Soirée film", kind: "run", on: false, available: true },
      { id: "light.bureau", name: "light.bureau", kind: "toggle", on: false, available: false },
      { id: "light.absente", name: "light.absente", kind: "toggle", on: false, available: false },
    ]);
    assert.deepEqual(seen, [{ method: "GET", url: "/api/states", body: undefined }]);
    assert.ok(!JSON.stringify(json).includes(HA_TOKEN));
  });

  it("POST toggle : bascule une lumière, lance une scène", async () => {
    seen.length = 0;
    assert.equal((await server.call("POST", "/ha/entities/light.salon/toggle")).status, 200);
    assert.equal((await server.call("POST", "/ha/entities/scene.film/toggle")).status, 200);
    assert.deepEqual(seen, [
      { method: "POST", url: "/api/services/light/toggle", body: { entity_id: "light.salon" } },
      { method: "POST", url: "/api/services/scene/turn_on", body: { entity_id: "scene.film" } },
    ]);
  });

  it("POST toggle : refuse ce qui n'est pas dans la liste blanche, sans appeler Home Assistant", async () => {
    seen.length = 0;
    assert.equal((await server.call("POST", "/ha/entities/light.cave/toggle")).status, 403);
    assert.equal((await server.call("POST", "/ha/entities/lock.porte/toggle")).status, 400);
    assert.equal((await server.call("POST", "/ha/entities/light.salon%2F..%2Fx/toggle")).status, 400);
    assert.equal(seen.length, 0);
  });

  it("jeton refusé par Home Assistant : message en français, sans le jeton", async () => {
    authFails = true;
    try {
      const res = await server.call("GET", "/ha/entities");
      assert.equal(res.status, 500);
      assert.equal(res.json.message, "Home Assistant refuse le jeton du fichier .env de l'ordinateur.");
    } finally {
      authFails = false;
    }
  });

  it("Home Assistant injoignable : message en français", async () => {
    const offline = await startServer({ env: { HA_URL: "http://127.0.0.1:1", HA_TOKEN, HA_ENTITIES: "light.salon" } });
    try {
      const res = await offline.call("GET", "/ha/entities");
      assert.equal(res.status, 500);
      assert.equal(res.json.message, "Home Assistant ne répond pas : vérifie la connexion de l'ordinateur.");
    } finally {
      await offline.stop();
    }
  });
});
