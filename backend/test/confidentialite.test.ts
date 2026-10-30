import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { after, before, describe, it } from "node:test";
import { startServer, type TestServer } from "./server.ts";

const backend = path.join(import.meta.dirname, "..");

describe("confidentialité (docs/confidentialite.md)", () => {
  let server: TestServer;
  before(async () => {
    server = await startServer();
  });
  after(async () => {
    await server?.stop();
  });

  it("la page locale sert la politique, sans jeton, et y mène", async () => {
    const res = await fetch(`${server.url}/local/confidentialite`);
    assert.equal(res.status, 200);
    assert.match(res.headers.get("content-type") ?? "", /text\/plain/);
    assert.match(await res.text(), /^UnoMate : politique de confidentialité/);
    assert.match((await server.call("GET", "/", undefined, false)).json, /href="\/local\/confidentialite"/);
  });

  it("les flux retirés ne reviennent pas sans mettre la politique à jour", () => {
    const sources = fs.readdirSync(path.join(backend, "src"), { recursive: true, encoding: "utf8" }).filter((f) => f.endsWith(".ts"));
    const code = sources.map((f) => fs.readFileSync(path.join(backend, "src", f), "utf8")).join("\n");
    // Nom des sites du Deck chez Google : retiré le 29/10/2026 plutôt que déclaré.
    assert.ok(!code.includes("google.com/s2/favicons"), "service d'icônes de Google");
  });
});
