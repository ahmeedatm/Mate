import assert from "node:assert/strict";
import fs from "node:fs/promises";
import path from "node:path";
import { after, before, beforeEach, describe, it } from "node:test";
import { startServer, type TestServer } from "./server.ts";

let server: TestServer;
before(async () => {
  server = await startServer();
});
after(async () => {
  await server?.stop();
});
beforeEach(async () => {
  await fs.rm(path.join(server.dataDir, "menu.json"), { force: true });
});

const emptyWeek = () => Array.from({ length: 7 }, () => ({ lunch: "", dinner: "" }));

describe("menu de la semaine", () => {
  it("refuse sans jeton côté tablette", async () => {
    assert.equal((await server.call("GET", "/menu", undefined, false)).status, 401);
  });

  it("rend sept jours vides tant qu'aucun menu n'est réglé", async () => {
    const res = await server.call("GET", "/menu");
    assert.equal(res.status, 200);
    assert.deepEqual(res.json, { days: emptyWeek() });
  });

  it("garde le menu réglé sur la page locale et le rend à la tablette", async () => {
    const days = emptyWeek();
    days[0] = { lunch: "  Pâtes au pesto ", dinner: "Soupe" };
    days[6] = { lunch: "Rôti", dinner: "" };
    const put = await server.call("PUT", "/local/config/menu", { days }, false);
    assert.equal(put.status, 200);
    days[0].lunch = "Pâtes au pesto";
    assert.deepEqual((await server.call("GET", "/local/config/menu", undefined, false)).json, { days });
    assert.deepEqual((await server.call("GET", "/menu")).json, { days });
  });

  it("coupe un plat trop long à 80 caractères", async () => {
    const days = emptyWeek();
    days[2].dinner = "x".repeat(150);
    await server.call("PUT", "/local/config/menu", { days }, false);
    assert.equal((await server.call("GET", "/menu")).json.days[2].dinner.length, 80);
  });

  it("refuse une semaine qui n'a pas sept jours", async () => {
    for (const days of [[], emptyWeek().slice(0, 6), [...emptyWeek(), { lunch: "", dinner: "" }]]) {
      assert.equal((await server.call("PUT", "/local/config/menu", { days }, false)).status, 400, String(days.length));
    }
    assert.equal((await server.call("PUT", "/local/config/menu", { days: "lundi" }, false)).status, 400);
  });

  it("relit un fichier abîmé comme une semaine vide", async () => {
    await fs.writeFile(path.join(server.dataDir, "menu.json"), JSON.stringify({ days: [{ lunch: 3 }] }));
    assert.deepEqual((await server.call("GET", "/menu")).json, { days: emptyWeek() });
  });
});
