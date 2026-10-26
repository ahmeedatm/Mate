import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { runSanity, type Os } from "../qa/sanity.ts";
import { startServer, TOKEN } from "./server.ts";

// Script de sanity des jobs d'installation, contre le serveur des tests d'intégration, pour chaque système simulé
// (test/fake-os.ts). Les jobs install-* le lancent sur un compagnon installé, au vrai pilotage.
for (const os of ["other", "windows", "mac"] as Os[]) {
  const server = await startServer({ os });
  const results = await runSanity({ url: server.url, token: TOKEN, os }).finally(() => server.stop());
  describe(`sanity (${os})`, () => {
    for (const r of results) {
      it(r.name, { skip: r.status === "skip" ? r.detail : false }, () => {
        assert.notEqual(r.status, "fail", r.detail);
      });
    }
  });
}
