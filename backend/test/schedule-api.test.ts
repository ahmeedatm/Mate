import assert from "node:assert/strict";
import { after, before, describe, it } from "node:test";
import { startServer, type TestServer } from "./server.ts";

let server: TestServer;
before(async () => {
  server = await startServer({ os: "mac" });
});
after(async () => {
  await server?.stop();
});

const firstTile = async (): Promise<{ id: string; label: string }> =>
  (await server.call("GET", "/deck")).json.pages
    .flatMap((p: { tiles: { id: string; label: string }[] }) => p.tiles)
    .find((t: { label?: string }) => t.label);

describe("Programmations d'une touche du Deck", () => {
  it("au départ, rien n'est programmé", async () => {
    assert.deepEqual((await server.call("GET", "/schedule")).json, { items: [] });
  });

  it("crée à heure fixe, liste avec le nom de la touche, désactive, supprime", async () => {
    const tile = await firstTile();
    const res = await server.call("POST", "/schedule", { tileId: tile.id, at: "18:00", days: [1, 2, 3, 4, 5] });
    assert.equal(res.status, 200);
    assert.equal(res.json.enabled, true);
    assert.equal(res.json.label, tile.label);
    assert.equal(res.json.schedule, "Lun–ven · 18:00");
    const id: string = res.json.id;
    assert.equal((await server.call("POST", `/schedule/${id}/enabled`, { enabled: false })).status, 200);
    assert.equal((await server.call("GET", "/schedule")).json.items[0].enabled, false);
    assert.equal((await server.call("DELETE", `/schedule/${id}`)).status, 200);
    assert.equal((await server.call("DELETE", `/schedule/${id}`)).status, 404);
    assert.equal((await server.call("POST", `/schedule/${id}/enabled`, { enabled: true })).status, 404);
  });

  it("intervalle lisible", async () => {
    const tile = await firstTile();
    const res = await server.call("POST", "/schedule", { tileId: tile.id, everyMinutes: 45 });
    assert.equal(res.json.schedule, "Toutes les 45 min");
    await server.call("DELETE", `/schedule/${res.json.id}`);
  });

  it("refuse : touche inconnue (404), ni heure ni intervalle ou les deux (400), heure ou intervalle invalides (400)", async () => {
    const tile = await firstTile();
    assert.equal((await server.call("POST", "/schedule", { tileId: "n-existe-pas", at: "18:00" })).status, 404);
    assert.equal((await server.call("POST", "/schedule", { tileId: tile.id })).status, 400);
    assert.equal((await server.call("POST", "/schedule", { tileId: tile.id, at: "18:00", everyMinutes: 30 })).status, 400);
    assert.equal((await server.call("POST", "/schedule", { tileId: tile.id, at: "25:00" })).status, 400);
    assert.equal((await server.call("POST", "/schedule", { tileId: tile.id, everyMinutes: 1 })).status, 400);
    assert.equal((await server.call("GET", "/schedule", undefined, false)).status, 401);
  });
});
