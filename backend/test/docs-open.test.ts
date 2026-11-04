import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { after, before, describe, it } from "node:test";
import { startServer, type TestServer } from "./server.ts";

// POST /docs/open : n'ouvre sur l'ordinateur que les types de la liste blanche (celle du partage vers Mate) et les dossiers.

const docsDir = fs.mkdtempSync(path.join(os.tmpdir(), "companion-open-docs-"));
let server: TestServer;
before(async () => {
  fs.mkdirSync(path.join(docsDir, "Cours"));
  fs.mkdirSync(path.join(docsDir, "Outil.app"));
  for (const name of [
    "cours.pdf",
    "PHOTO.JPG",
    "acces.rdp",
    "page.html",
    "macro.docm",
    "image.iso",
    "lien.webloc",
    "sans-extension",
    "a.exe.",
  ]) {
    fs.writeFileSync(path.join(docsDir, name), "x");
  }
  server = await startServer({ env: { DOCS_DIR: docsDir } });
});
after(async () => {
  await server?.stop();
  fs.rmSync(docsDir, { recursive: true, force: true });
});

const opened = () => server.osCalls().filter((c) => c.fn === "openTarget");

describe("garde de /docs/open", () => {
  it("ouvre un type de la liste blanche (majuscules comprises) et un dossier", async () => {
    server.clearOsCalls();
    for (const p of ["cours.pdf", "PHOTO.JPG", "Cours", ""]) {
      const res = await server.call("POST", "/docs/open", { path: p });
      assert.equal(res.status, 200, p);
    }
    assert.equal(opened().length, 4);
  });

  it("refuse ce qui n'est pas dans la liste blanche, sans rien ouvrir", async () => {
    server.clearOsCalls();
    for (const p of [
      "acces.rdp",
      "page.html",
      "macro.docm",
      "image.iso",
      "lien.webloc",
      "sans-extension",
      "a.exe.",
      "Outil.app",
      "absent.pdf.exe",
    ]) {
      const res = await server.call("POST", "/docs/open", { path: p });
      assert.equal(res.status, 400, p);
      assert.match(res.json.message ?? res.json.error, /type de fichier/i, p);
    }
    assert.deepEqual(opened(), []);
  });
});
