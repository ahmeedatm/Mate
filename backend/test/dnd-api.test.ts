import assert from "node:assert/strict";
import { after, before, describe, it } from "node:test";
import { runSanity } from "../qa/sanity.ts";
import { startServer, TOKEN, type TestServer } from "./server.ts";

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

describe("contrôle sanity de Ne pas déranger", () => {
  const sanity = async () =>
    (await runSanity({ url: server.url, token: TOKEN, os: "mac" })).find(
      (r) => r.name === "Ne pas déranger à la main (pastille de l'Accueil)",
    )!;

  it("passe son tour sur un Mac sans l'autorisation Accessibilité, comme les fenêtres et le pointeur", async () => {
    server.setOs({ accessibility: false, fail: ["setDnd"] });
    assert.equal((await sanity()).status, "skip");
  });

  it("échoue sur une erreur de lecture quand l'autorisation est accordée", async () => {
    server.setOs({ fail: ["setDnd"] });
    assert.equal((await sanity()).status, "fail");
  });
});
