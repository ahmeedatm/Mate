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

const keys = () => server.osCalls().filter((c) => c.fn === "sendKeys");

describe("Présentation : pilotage de l'appli de diapos par l'API", () => {
  it("PowerPoint ouvert : suivante et précédente envoient les flèches à PowerPoint", async () => {
    server.setOs({ running: ["Microsoft PowerPoint"] });
    server.clearOsCalls();
    const next = await server.call("POST", "/present/next");
    assert.equal(next.status, 200);
    assert.deepEqual(next.json, { ok: true, app: "Microsoft PowerPoint" });
    await server.call("POST", "/present/previous");
    assert.deepEqual(keys(), [
      { fn: "sendKeys", args: ["right", "Microsoft PowerPoint"] },
      { fn: "sendKeys", args: ["left", "Microsoft PowerPoint"] },
    ]);
  });

  it("début : 1 puis Entrée, l'appli devant seulement à la première touche ; fin : Échap", async () => {
    server.setOs({ running: ["Microsoft PowerPoint"] });
    server.clearOsCalls();
    await server.call("POST", "/present/start");
    await server.call("POST", "/present/end");
    assert.deepEqual(keys(), [
      { fn: "sendKeys", args: ["1", "Microsoft PowerPoint"] },
      { fn: "sendKeys", args: ["enter"] },
      { fn: "sendKeys", args: ["escape", "Microsoft PowerPoint"] },
    ]);
  });

  it("Keynote seul ouvert : les touches vont à Keynote", async () => {
    server.setOs({ running: ["Keynote"] });
    server.clearOsCalls();
    const res = await server.call("POST", "/present/next");
    assert.deepEqual(res.json, { ok: true, app: "Keynote" });
    assert.deepEqual(keys(), [{ fn: "sendKeys", args: ["right", "Keynote"] }]);
  });

  it("PowerPoint et Keynote ouverts : PowerPoint prime", async () => {
    server.setOs({ running: ["Keynote", "Microsoft PowerPoint"] });
    assert.equal((await server.call("POST", "/present/next")).json.app, "Microsoft PowerPoint");
  });

  it("aucune appli de diapos : 409 avec la phrase pour la tablette, aucune touche", async () => {
    server.setOs({ running: ["Safari"] });
    server.clearOsCalls();
    const res = await server.call("POST", "/present/next");
    assert.equal(res.status, 409);
    assert.equal(res.json.message, "Ouvre PowerPoint ou Keynote sur l'ordinateur");
    assert.deepEqual(keys(), []);
  });

  it("action inconnue : 400, jeton exigé", async () => {
    server.setOs({ running: ["Microsoft PowerPoint"] });
    server.clearOsCalls();
    assert.equal((await server.call("POST", "/present/saute")).status, 400);
    assert.equal((await server.call("POST", "/present/next", undefined, false)).status, 401);
    assert.deepEqual(keys(), []);
  });
});

describe("Présentation sur un ordinateur non pilotable", () => {
  it("501", async () => {
    const other = await startServer({ os: "other" });
    try {
      assert.equal((await other.call("POST", "/present/next")).status, 501);
    } finally {
      await other.stop();
    }
  });
});
