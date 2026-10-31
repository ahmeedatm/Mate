import assert from "node:assert/strict";
import { after, before, describe, it } from "node:test";
import { startServer, type TestServer } from "./server.ts";

// Panneau de l'appli portable (routes/portable.ts) lancé depuis le dépôt : rien ne s'applique et rien ne change sur la
// machine, et un autre site ne peut pas déclencher les actions depuis le navigateur.

let server: TestServer;
before(async () => {
  server = await startServer();
});
after(async () => {
  await server?.stop();
});

const alive = async () => (await fetch(`${server.url}/health`)).ok;

describe("panneau hors appli portable", () => {
  it("dit qu'il n'est pas portable, sans jeton (page locale)", async () => {
    const res = await server.call("GET", "/local/panel", undefined, false);
    assert.equal(res.status, 200);
    assert.deepEqual(res.json, { portable: false });
  });

  it("refuse chaque action (409) sans rien faire, serveur toujours en marche", async () => {
    for (const [route, body] of [
      ["/local/autostart", { on: true }],
      ["/local/firewall", {}],
      ["/local/forget", { sure: true }],
      ["/local/quit", {}],
      ["/local/update", {}],
    ] as const) {
      const res = await server.call("POST", route, body, false);
      assert.equal(res.status, 409, route);
    }
    assert.equal((await server.call("GET", "/local/update", undefined, false)).status, 409);
    assert.ok(await alive());
  });

  it("« tout oublier » demande une confirmation explicite", async () => {
    assert.equal((await server.call("POST", "/local/forget", { sure: false }, false)).status, 400);
    assert.equal((await server.call("POST", "/local/forget", {}, false)).status, 400);
  });

  it("refuse un corps qui n'est pas du JSON (formulaire d'un autre site : pas de demande préalable du navigateur)", async () => {
    for (const type of ["text/plain", "application/x-www-form-urlencoded"]) {
      const res = await fetch(`${server.url}/local/quit`, { method: "POST", headers: { "content-type": type }, body: "{}" });
      assert.ok([400, 415].includes(res.status), `${type} : ${res.status}`);
    }
    assert.ok(await alive());
  });
});
