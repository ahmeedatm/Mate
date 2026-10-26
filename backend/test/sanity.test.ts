import assert from "node:assert/strict";
import { test } from "node:test";
import { runSanity } from "../qa/sanity.ts";
import { startServer, TOKEN } from "./server.ts";

// Script de sanity des jobs d'installation, contre le serveur des tests d'intégration (pilotage : 501 attendu).
// Sur Mac et Windows, il tourne sur le compagnon installé (jobs install-*), pas ici.
const linux = process.platform !== "darwin" && process.platform !== "win32";

const results = linux
  ? await (async () => {
      const server = await startServer();
      try {
        return await runSanity({ url: server.url, token: TOKEN, os: "other" });
      } finally {
        await server.stop();
      }
    })()
  : [];

test("sanity hors Linux : couverte par les jobs install-*", { skip: linux }, () => {});
for (const r of results) {
  test(`sanity : ${r.name}`, { skip: r.status === "skip" ? r.detail : false }, () => {
    assert.notEqual(r.status, "fail", r.detail);
  });
}
