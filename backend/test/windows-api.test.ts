import assert from "node:assert/strict";
import { after, before, describe, it } from "node:test";
import { startServer, type TestServer } from "./server.ts";

let server: TestServer;
before(async () => {
  server = await startServer();
});
after(async () => {
  await server?.stop();
});

describe("vue Ouvertes en HTTP", () => {
  const window = { index: 2, title: "Notes" };

  it("rend la liste à jour après une action, par défaut", async () => {
    server.clearOsCalls();
    const res = await server.call("POST", "/windows/open", { key: "com.apple.Notes", action: "fullscreen", window });
    assert.equal(res.status, 200);
    assert.ok(Array.isArray(res.json.apps) && Array.isArray(res.json.displays));
    assert.deepEqual(server.osCalls(), [{ fn: "controlOpenWindow", args: ["com.apple.Notes", window, "fullscreen"] }]);
  });

  it("répond sans relire la liste avec list: false", async () => {
    server.clearOsCalls();
    const res = await server.call("POST", "/windows/open", {
      key: "com.apple.Notes",
      action: "place",
      window,
      display: 1,
      list: false,
    });
    assert.deepEqual(res.json, { ok: true });
    assert.deepEqual(server.osCalls(), [
      { fn: "controlOpenWindow", args: ["com.apple.Notes", window, "place", { display: 1, layout: "keep" }] },
    ]);
  });

  it("enchaîne les actions rapprochées, dans l'ordre", async () => {
    server.clearOsCalls();
    const actions = ["focus", "zoom", "fullscreen"] as const;
    await Promise.all(actions.map((action) => server.call("POST", "/windows/open", { key: "k", action, window, list: false })));
    assert.deepEqual(
      server.osCalls().map((c) => c.args[2]),
      actions,
    );
  });
});

describe("pages du Mac en HTTP", () => {
  it("passe sur une page et refuse une page mal formée (400)", async () => {
    server.clearOsCalls();
    assert.deepEqual((await server.call("POST", "/windows/page", { page: 291 })).json, { ok: true });
    for (const body of [{}, { page: -1 }, { page: "291" + "x" }, { page: 1.5 }]) {
      assert.equal((await server.call("POST", "/windows/page", body)).status, 400, JSON.stringify(body));
    }
    assert.equal((await server.call("POST", "/windows/page", { page: 1 }, false)).status, 401);
    assert.deepEqual(server.osCalls(), [{ fn: "showPage", args: [291] }]);
  });
});
