import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { after, before, describe, it } from "node:test";
import { startServer, type TestServer } from "./server.ts";

// Cadre photo de la tablette : GET /frame liste les images du dossier « Cadre photo », relues par /docs/file.

const docsDir = fs.mkdtempSync(path.join(os.tmpdir(), "companion-frame-docs-"));
const frame = path.join(docsDir, "Cadre photo");
let server: TestServer;
before(async () => {
  server = await startServer({ env: { DOCS_DIR: docsDir } });
});
after(async () => {
  await server?.stop();
  fs.rmSync(docsDir, { recursive: true, force: true });
});

describe("cadre photo", () => {
  it("crée le dossier s'il manque et renvoie une liste vide", async () => {
    const { status, json } = await server.call("GET", "/frame");
    assert.equal(status, 200);
    assert.deepEqual(json, { dir: "Cadre photo", photos: [] });
    assert.ok(fs.statSync(frame).isDirectory());
  });

  it("liste les images, sous-dossiers compris, sans fichiers cachés ni autres formats", async () => {
    fs.mkdirSync(path.join(frame, "Vacances"), { recursive: true });
    fs.mkdirSync(path.join(frame, ".cache"), { recursive: true });
    fs.writeFileSync(path.join(frame, "b.JPG"), "jpg");
    fs.writeFileSync(path.join(frame, "a.png"), "png");
    fs.writeFileSync(path.join(frame, "Vacances", "plage.webp"), "webp");
    fs.writeFileSync(path.join(frame, "note.txt"), "texte");
    fs.writeFileSync(path.join(frame, "film.mp4"), "mp4");
    fs.writeFileSync(path.join(frame, ".DS_Store"), "x");
    fs.writeFileSync(path.join(frame, ".cache", "c.jpg"), "jpg");

    const { status, json } = await server.call("GET", "/frame");
    assert.equal(status, 200);
    assert.deepEqual(
      json.photos.map((p: { path: string }) => p.path),
      ["Cadre photo/a.png", "Cadre photo/b.JPG", "Cadre photo/Vacances/plage.webp"],
    );
    const first = json.photos[0];
    assert.equal(first.size, 3);
    assert.equal(typeof first.mtimeMs, "number");
  });

  it("chaque photo se télécharge par /docs/file", async () => {
    const { json } = await server.call("GET", "/frame");
    const res = await fetch(`${server.url}/docs/file?path=${encodeURIComponent(json.photos[2].path)}`, {
      headers: { authorization: "Bearer jeton-de-test" },
    });
    assert.equal(res.status, 200);
    assert.equal(await res.text(), "webp");
  });
});
