import assert from "node:assert/strict";
import http from "node:http";
import type { AddressInfo } from "node:net";
import { after, before, describe, it } from "node:test";
import { startServer, TOKEN, type TestServer } from "./server.ts";

const HA_TOKEN = "jeton-ha";

type Seen = { method: string; url: string; body: any };
const seen: Seen[] = [];
let authFails = false;
let htmlStates = false;
const STATES = [
  {
    entity_id: "light.salon",
    state: "on",
    attributes: { friendly_name: "Lampe du salon", supported_color_modes: ["rgb"], brightness: 128, rgb_color: [255, 120, 0] },
  },
  { entity_id: "light.ruban", state: "off", attributes: { friendly_name: "Ruban", supported_color_modes: ["brightness"] } },
  { entity_id: "light.applique", state: "on", attributes: { friendly_name: "Applique", supported_color_modes: ["onoff"] } },
  { entity_id: "switch.cafetiere", state: "off", attributes: { friendly_name: "Cafetière" } },
  { entity_id: "scene.film", state: "2026-11-01T10:00:00+00:00", attributes: { friendly_name: "Soirée film" } },
  { entity_id: "light.bureau", state: "unavailable", attributes: {} },
  { entity_id: "lock.porte", state: "locked", attributes: { friendly_name: "Porte" } },
  { entity_id: "light.cave", state: "on", attributes: { friendly_name: "Cave (hors liste)" } },
  { entity_id: "media_player.tv", state: "playing", attributes: { friendly_name: "Télé" } },
  { entity_id: "media_player.chambre", state: "standby", attributes: { friendly_name: "Apple TV" } },
  { entity_id: "media_player.salon", state: "idle", attributes: { friendly_name: "Enceinte" } },
  { entity_id: "media_player.bureau", state: "off", attributes: { friendly_name: "Écran" } },
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
    if (req.method === "GET" && url === "/api/states" && htmlStates) {
      res.writeHead(200, { "content-type": "text/html" });
      return res.end("<!DOCTYPE html><p>Portail captif</p>");
    }
    if (req.method === "GET" && url === "/api/states") return send(200, STATES);
    if (url === "/rebond") {
      res.writeHead(302, { location: "/api/states" });
      return res.end();
    }
    if (req.method === "POST" && /^\/api\/services\/[a-z_]+\/[a-z_]+$/.test(url)) return send(200, []);
    send(404, {});
  });
});

let site = "";
let server: TestServer;
let bare: TestServer;
let media: TestServer;
let settingsOnly: TestServer;
before(async () => {
  await new Promise<void>((r) => haServer.listen(0, "127.0.0.1", r));
  site = `http://127.0.0.1:${(haServer.address() as AddressInfo).port}`;
  server = await startServer({
    env: {
      HA_URL: site,
      HA_TOKEN,
      // `lock.porte` (domaine non géré), `light.absente` (inconnue de Home Assistant) et un identifiant faux restent sans effet ou grisés.
      HA_ENTITIES: "light.salon,light.ruban,light.applique, switch.cafetiere,scene.film,light.bureau,lock.porte,light.absente,pas un id",
    },
  });
  settingsOnly = await startServer({ env: { HA_URL: site, HA_TOKEN, HA_ENTITIES: "" } });
  bare = await startServer({ env: { HA_URL: "", HA_TOKEN: "", HA_ENTITIES: "" } });
  media = await startServer({
    env: { HA_URL: site, HA_TOKEN, HA_ENTITIES: "media_player.tv,media_player.chambre,media_player.salon,media_player.bureau" },
  });
});
after(async () => {
  await server?.stop();
  await bare?.stop();
  await media?.stop();
  await settingsOnly?.stop();
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
    assert.equal((await bare.call("POST", "/ha/entities/light.salon/light", { brightness: 50 })).status, 501);
  });

  it("GET /ha/settings : URL, jeton et HTTP au porteur du jeton, sans cache, même sans liste blanche", async () => {
    const want = { url: site, token: HA_TOKEN, allowHttp: false };
    for (const s of [server, settingsOnly]) {
      const res = await fetch(`${s.url}/ha/settings`, { headers: { authorization: `Bearer ${TOKEN}` } });
      assert.equal(res.status, 200);
      assert.equal(res.headers.get("cache-control"), "no-store");
      assert.deepEqual(await res.json(), want);
    }
    assert.equal((await server.call("GET", "/ha/settings", undefined, false)).status, 401);
    assert.ok(!server.output().includes(HA_TOKEN), "le jeton Home Assistant ne doit jamais être journalisé");
  });

  it("GET /ha/settings sans Home Assistant : 404 en français, jamais 501", async () => {
    const { status, json } = await bare.call("GET", "/ha/settings");
    assert.equal(status, 404);
    assert.equal(json.error, "Home Assistant n'est pas configuré sur l'ordinateur.");
  });

  it("jeton d'appairage exigé", async () => {
    assert.equal((await server.call("GET", "/ha/entities", undefined, false)).status, 401);
  });

  it("GET /ha/entities : la liste blanche seulement, dans son ordre, avec l'état", async () => {
    seen.length = 0;
    const { status, json } = await server.call("GET", "/ha/entities");
    assert.equal(status, 200);
    assert.deepEqual(json.entities, [
      {
        id: "light.salon",
        name: "Lampe du salon",
        kind: "toggle",
        on: true,
        available: true,
        light: { color: true, brightness: 50, rgb: [255, 120, 0] },
      },
      {
        id: "light.ruban",
        name: "Ruban",
        kind: "toggle",
        on: false,
        available: true,
        light: { color: false, brightness: null, rgb: null },
      },
      { id: "light.applique", name: "Applique", kind: "toggle", on: true, available: true },
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

  it("POST light : luminosité et couleur d'une lumière par light.turn_on", async () => {
    seen.length = 0;
    assert.equal((await server.call("POST", "/ha/entities/light.salon/light", { brightness: 40, rgb: [0, 128, 255] })).status, 200);
    assert.equal((await server.call("POST", "/ha/entities/light.ruban/light", { brightness: 100 })).status, 200);
    assert.deepEqual(seen, [
      {
        method: "POST",
        url: "/api/services/light/turn_on",
        body: { entity_id: "light.salon", brightness_pct: 40, rgb_color: [0, 128, 255] },
      },
      { method: "POST", url: "/api/services/light/turn_on", body: { entity_id: "light.ruban", brightness_pct: 100 } },
    ]);
  });

  it("POST light : refuse hors liste (403), hors bornes, vide ou sur une prise (400), sans appeler Home Assistant", async () => {
    seen.length = 0;
    assert.equal((await server.call("POST", "/ha/entities/light.cave/light", { brightness: 50 })).status, 403);
    for (const body of [{}, { brightness: 101 }, { brightness: 0 }, { brightness: 2.5 }, { rgb: [0, 0, 256] }, { rgb: [0, 0] }])
      assert.equal((await server.call("POST", "/ha/entities/light.salon/light", body)).status, 400, JSON.stringify(body));
    assert.equal((await server.call("POST", "/ha/entities/switch.cafetiere/light", { brightness: 50 })).status, 400);
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

  it("http hors de cet ordinateur : refusé sauf HA_ALLOW_HTTP=1 (le jeton passerait en clair sur le WiFi)", async () => {
    const lan = { HA_URL: "http://homeassistant.local:8123", HA_TOKEN, HA_ENTITIES: "light.salon" };
    const refused = await startServer({ env: { ...lan, HA_ALLOW_HTTP: "" } });
    const allowed = await startServer({ env: { ...lan, HA_ALLOW_HTTP: "1" } });
    const tls = await startServer({ env: { ...lan, HA_URL: "https://homeassistant.local:8123", HA_ALLOW_HTTP: "" } });
    try {
      assert.equal((await refused.call("GET", "/deck/features")).json.ha, false);
      assert.equal((await allowed.call("GET", "/deck/features")).json.ha, true);
      assert.equal((await tls.call("GET", "/deck/features")).json.ha, true);
    } finally {
      await refused.stop();
      await allowed.stop();
      await tls.stop();
    }
  });

  it("réponse qui n'est pas du JSON (portail captif) : message en français, sans le corps reçu", async () => {
    htmlStates = true;
    try {
      const res = await server.call("GET", "/ha/entities");
      assert.equal(res.status, 500);
      assert.equal(res.json.message, "Home Assistant a répondu quelque chose d'illisible.");
    } finally {
      htmlStates = false;
    }
  });
});

describe("home assistant : télé et Apple TV", () => {
  it("GET /ha/entities : un media_player est allumé dans tout état autre que off ou standby", async () => {
    const { status, json } = await media.call("GET", "/ha/entities");
    assert.equal(status, 200);
    assert.deepEqual(json.entities, [
      { id: "media_player.tv", name: "Télé", kind: "toggle", on: true, available: true },
      { id: "media_player.chambre", name: "Apple TV", kind: "toggle", on: false, available: true },
      { id: "media_player.salon", name: "Enceinte", kind: "toggle", on: true, available: true },
      { id: "media_player.bureau", name: "Écran", kind: "toggle", on: false, available: true },
    ]);
  });

  it("POST toggle : allume ou éteint la télé par media_player.toggle", async () => {
    seen.length = 0;
    assert.equal((await media.call("POST", "/ha/entities/media_player.tv/toggle")).status, 200);
    assert.deepEqual(seen, [{ method: "POST", url: "/api/services/media_player/toggle", body: { entity_id: "media_player.tv" } }]);
  });
});
