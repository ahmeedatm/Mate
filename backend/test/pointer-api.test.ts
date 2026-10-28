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

describe("pavé tactile en HTTP", () => {
  it("transmet un geste connu au pilotage et refuse un geste inconnu (400)", async () => {
    server.clearOsCalls();
    assert.deepEqual((await server.call("POST", "/pointer/gesture", { gesture: "task-view" })).json, { ok: true });
    assert.deepEqual((await server.call("POST", "/pointer/gesture", { gesture: "zoom-in" })).json, { ok: true });
    assert.deepEqual(server.osCalls(), [
      { fn: "pointerGesture", args: ["task-view"] },
      { fn: "pointerGesture", args: ["zoom-in"] },
    ]);
    server.clearOsCalls();
    for (const body of [{ gesture: "ctrl+alt+delete" }, { gesture: "" }, {}]) {
      assert.equal((await server.call("POST", "/pointer/gesture", body)).status, 400, JSON.stringify(body));
    }
    assert.equal((await server.call("POST", "/pointer/gesture", { gesture: "back" }, false)).status, 401);
    assert.deepEqual(server.osCalls(), []);
  });

  it("refuse sans jeton", async () => {
    for (const [route, body] of [
      ["/pointer/move", { dx: 1, dy: 1 }],
      ["/pointer/scroll", { sx: 0, sy: 1 }],
      ["/pointer/button", { button: "left", action: "click" }],
    ] as const) {
      assert.equal((await server.call("POST", route, body, false)).status, 401, route);
    }
  });

  it("transmet déplacement, défilement et boutons au pilotage", async () => {
    server.clearOsCalls();
    assert.deepEqual((await server.call("POST", "/pointer/move", { dx: 12, dy: -7 })).json, { ok: true });
    assert.deepEqual((await server.call("POST", "/pointer/scroll", { sx: 1, sy: -3 })).json, { ok: true });
    assert.deepEqual((await server.call("POST", "/pointer/button", { button: "right", action: "down" })).json, { ok: true });
    assert.deepEqual(server.osCalls(), [
      { fn: "pointerMove", args: [12, -7] },
      { fn: "pointerScroll", args: [1, -3] },
      { fn: "pointerButton", args: ["right", "down"] },
    ]);
  });

  it("refuse les valeurs hors bornes ou mal formées (400)", async () => {
    server.clearOsCalls();
    for (const [route, body] of [
      ["/pointer/move", { dx: 2001, dy: 0 }],
      ["/pointer/move", { dx: 0, dy: -2001 }],
      ["/pointer/move", { dx: 1.5, dy: 0 }],
      ["/pointer/move", { dx: 1 }],
      ["/pointer/scroll", { sx: 51, sy: 0 }],
      ["/pointer/button", { button: "middle", action: "click" }],
      ["/pointer/button", { button: "left", action: "double" }],
    ] as const) {
      assert.equal((await server.call("POST", route, body)).status, 400, `${route} ${JSON.stringify(body)}`);
    }
    assert.deepEqual(server.osCalls(), []);
  });

  it("n'écrit pas les déplacements dans le journal", async () => {
    await server.call("POST", "/pointer/move", { dx: 4242, dy: 1 });
    assert.ok(!server.output().includes("4242"));
  });
});

describe("pavé tactile hors Mac et Windows", () => {
  it("répond 501", async () => {
    const other = await startServer({ os: "other" });
    try {
      assert.equal((await other.call("POST", "/pointer/move", { dx: 0, dy: 0 })).status, 501);
      assert.equal((await other.call("POST", "/pointer/scroll", { sx: 0, sy: 0 })).status, 501);
      assert.equal((await other.call("POST", "/pointer/button", { button: "left", action: "click" })).status, 501);
      assert.deepEqual(other.osCalls(), []);
    } finally {
      await other.stop();
    }
  });
});
