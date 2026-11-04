import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { after, before, describe, it } from "node:test";
import { startServer, type TestServer } from "./server.ts";

// POST /docs/open : n'ouvre sur l'ordinateur que les types de la liste blanche (celle du partage vers Mate) et les dossiers.

const docsDir = fs.mkdtempSync(path.join(os.tmpdir(), "companion-open-docs-"));
// Hors du dossier partagé : cible des liens symboliques.
const outside = fs.mkdtempSync(path.join(os.tmpdir(), "companion-open-outside-"));
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
  fs.mkdirSync(path.join(docsDir, "v1.2"));
  fs.mkdirSync(path.join(docsDir, "Vrai dossier"));
  fs.writeFileSync(path.join(docsDir, "Vrai dossier", "interne.pdf"), "x");
  fs.writeFileSync(path.join(docsDir, "programme.exe"), "x");
  fs.writeFileSync(path.join(outside, "externe.pdf"), "x");
  fs.mkdirSync(path.join(outside, "Applications"));
  // Liens symboliques : `open` les suit, la garde doit juger la cible réelle.
  fs.symlinkSync("/bin/sh", path.join(docsDir, "sh.pdf"));
  fs.symlinkSync(path.join(outside, "externe.pdf"), path.join(docsDir, "externe.pdf"));
  fs.symlinkSync(path.join(outside, "Applications"), path.join(docsDir, "Apps"));
  fs.symlinkSync(path.join(docsDir, "programme.exe"), path.join(docsDir, "inoffensif.pdf"));
  fs.symlinkSync(path.join(docsDir, "Outil.app"), path.join(docsDir, "Raccourci"));
  fs.symlinkSync(path.join(docsDir, "cours.pdf"), path.join(docsDir, "copie.pdf"));
  fs.symlinkSync(path.join(docsDir, "Vrai dossier"), path.join(docsDir, "Lien dossier"));
  server = await startServer({ env: { DOCS_DIR: docsDir } });
});
after(async () => {
  await server?.stop();
  fs.rmSync(docsDir, { recursive: true, force: true });
  fs.rmSync(outside, { recursive: true, force: true });
});

const opened = () => server.osCalls().filter((c) => c.fn === "openTarget");

describe("garde de /docs/open", () => {
  it("ouvre un type de la liste blanche (majuscules comprises) et un dossier", async () => {
    server.clearOsCalls();
    for (const p of ["cours.pdf", "PHOTO.JPG", "Cours", "v1.2", "copie.pdf", "Lien dossier", "Vrai dossier/interne.pdf", ""]) {
      const res = await server.call("POST", "/docs/open", { path: p });
      assert.equal(res.status, 200, p);
    }
    assert.equal(opened().length, 8);
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

  it("refuse un lien symbolique qui sort du dossier partagé ou mène à un programme", async () => {
    server.clearOsCalls();
    for (const p of ["sh.pdf", "externe.pdf", "Apps", "inoffensif.pdf", "Raccourci"]) {
      const res = await server.call("POST", "/docs/open", { path: p });
      assert.equal(res.status, 400, p);
    }
    assert.deepEqual(opened(), []);
  });
});
