import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { describe, it } from "node:test";
import { runSanity, type Os } from "../qa/sanity.ts";
import { startServer, TOKEN } from "./server.ts";

// Script de sanity des jobs d'installation, contre le serveur des tests d'intégration, pour chaque système simulé
// (test/fake-os.ts). Les jobs install-* le lancent sur un compagnon installé, au vrai pilotage.
for (const system of ["other", "windows", "mac"] as Os[]) {
  // Secret des hooks des agents connu d'avance : la sanity envoie de faux événements.
  const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), "companion-sanity-"));
  fs.writeFileSync(path.join(dataDir, "agents-hook.json"), JSON.stringify({ secret: "secret-sanity" }));
  const server = await startServer({ os: system, dataDir });
  const results = await runSanity({ url: server.url, token: TOKEN, os: system, hookSecret: "secret-sanity" }).finally(async () => {
    await server.stop();
    fs.rmSync(dataDir, { recursive: true, force: true });
  });
  describe(`sanity (${system})`, () => {
    for (const r of results) {
      it(r.name, { skip: r.status === "skip" ? r.detail : false }, () => {
        assert.notEqual(r.status, "fail", r.detail);
      });
    }
  });
}
