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

describe("Écran éveillé pendant le Focus par l'API", () => {
  it("POST /system/awake garde l'écran éveillé puis rend la veille", async () => {
    const on = await server.call("POST", "/system/awake", { on: true, minutes: 25 });
    assert.equal(on.status, 200);
    assert.deepEqual(on.json, { on: true });
    assert.deepEqual((await server.call("POST", "/system/awake", { on: false })).json, { on: false });
  });

  it("corps invalide : 400", async () => {
    assert.equal((await server.call("POST", "/system/awake", { on: "oui" })).status, 400);
    assert.equal((await server.call("POST", "/system/awake", { on: true, minutes: 0 })).status, 400);
    assert.equal((await server.call("POST", "/system/awake", {})).status, 400);
  });
});
