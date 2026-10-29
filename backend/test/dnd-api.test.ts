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

describe("Ne pas déranger à la main par l'API", () => {
  it("PUT /system/dnd pose l'état, GET le relit", async () => {
    server.setOs({ dnd: false });
    server.clearOsCalls();
    const res = await server.call("PUT", "/system/dnd", { on: true });
    assert.equal(res.status, 200);
    assert.deepEqual(res.json, { on: true });
    assert.deepEqual((await server.call("GET", "/system/dnd")).json, { on: true });
    assert.deepEqual(
      server
        .osCalls()
        .filter((c) => c.fn === "setDnd")
        .map((c) => c.args[0]),
      [true],
    );
  });

  it("corps invalide : 400", async () => {
    assert.equal((await server.call("PUT", "/system/dnd", { on: "oui" })).status, 400);
    assert.equal((await server.call("PUT", "/system/dnd", {})).status, 400);
  });
});
