import assert from "node:assert/strict";
import http from "node:http";
import type { AddressInfo } from "node:net";
import { after, before, describe, it } from "node:test";
import { startServer, type TestServer } from "./server.ts";

let door = "off";
const haServer = http.createServer((req, res) => {
  res.writeHead(200, { "content-type": "application/json" });
  res.end(
    req.url === "/api/states"
      ? JSON.stringify([
          { entity_id: "binary_sensor.porte", state: door, attributes: { friendly_name: "Porte d'entrée" } },
          { entity_id: "binary_sensor.autre", state: "on", attributes: {} },
        ])
      : "{}",
  );
});

let server: TestServer;
before(async () => {
  await new Promise<void>((r) => haServer.listen(0, "127.0.0.1", r));
  server = await startServer({
    env: {
      HA_URL: `http://127.0.0.1:${(haServer.address() as AddressInfo).port}`,
      HA_TOKEN: "jeton-ha",
      HA_ALERTS: "binary_sensor.porte",
      HA_ALERTS_POLL_MS: "20",
    },
  });
});
after(async () => {
  await server?.stop();
  haServer.close();
});

/** Long-polling de la tablette : acquitte tous les ordres reçus (d'autres veilleurs en envoient) et rend les alertes. */
async function nextAlerts(): Promise<any[]> {
  const { json } = await server.call("POST", "/tablet/poll", { state: {} });
  for (const c of json.commands) await server.call("POST", "/tablet/result", { id: c.id, ok: true });
  return json.commands.map((c: any) => c.command).filter((c: any) => c.type === "alert");
}

describe("alerte maison : un capteur de Home Assistant change", () => {
  it("la tablette reçoit un ordre `alert` au passage à « on », une seule fois", async () => {
    // Rien au démarrage : le capteur « autre » est déjà à « on » et hors liste.
    assert.deepEqual(await nextAlerts(), []);

    door = "on";
    const end = Date.now() + 5_000;
    let alerts: any[] = [];
    while (alerts.length === 0 && Date.now() < end) alerts = await nextAlerts();
    assert.deepEqual(alerts, [{ type: "alert", text: "Porte d'entrée" }]);
    await new Promise((r) => setTimeout(r, 150));
    assert.deepEqual(await nextAlerts(), []);
  });

  it("l'API refuse l'ordre `alert` : réservé à l'ordinateur", async () => {
    const res = await server.call("POST", "/tablet/command", { type: "alert", text: "x" });
    assert.equal(res.status, 400);
  });
});
