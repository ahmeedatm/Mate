import assert from "node:assert/strict";
import fs from "node:fs/promises";
import path from "node:path";
import { after, before, beforeEach, describe, it } from "node:test";
import { startServer, type TestServer } from "./server.ts";

let server: TestServer;
const file = () => path.join(server.dataDir, "metrics.json");
before(async () => {
  server = await startServer();
});
after(async () => {
  await server?.stop();
});
beforeEach(async () => {
  await fs.rm(file(), { force: true });
});

describe("valeurs de l'ordinateur", () => {
  it("refuse sans jeton", async () => {
    assert.equal((await server.call("GET", "/metrics", undefined, false)).status, 401);
  });

  it("est vide tant que le fichier n'existe pas", async () => {
    const res = await server.call("GET", "/metrics");
    assert.equal(res.status, 200);
    assert.deepEqual(res.json, { items: [] });
  });

  it("rend la sortie des commandes du fichier, lancées par l'ordinateur", async () => {
    await fs.writeFile(file(), JSON.stringify({ metrics: [{ label: "Compteur", command: "compter" }] }));
    server.setOs({ commands: { compter: "17\n" } });
    const res = await server.call("GET", "/metrics");
    assert.equal(res.status, 200);
    assert.deepEqual(res.json, { items: [{ label: "Compteur", value: "17" }] });
    assert.ok(server.osCalls().some((c) => c.fn === "runCommand" && c.args[0] === "compter"));
  });

  it("n'accepte aucune commande venue de la tablette", async () => {
    for (const method of ["POST", "PUT"] as const) {
      const res = await server.call(method, "/metrics", { command: "id" });
      assert.ok(res.status === 404 || res.status === 405, `${method} : ${res.status}`);
    }
  });
});
