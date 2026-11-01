import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { after, before, describe, it } from "node:test";
import { startServer, TOKEN, type TestServer } from "./server.ts";

// « Partager » vers Mate : POST /docs/share dépose le fichier envoyé dans le dossier partagé, sous son nom.

const docsDir = fs.mkdtempSync(path.join(os.tmpdir(), "companion-share-docs-"));
let server: TestServer;
before(async () => {
  server = await startServer({ env: { DOCS_DIR: docsDir } });
});
after(async () => {
  await server?.stop();
  fs.rmSync(docsDir, { recursive: true, force: true });
});

const share = (name: string, content: string | Uint8Array, auth = true) => {
  const form = new FormData();
  form.append("file", new Blob([content as BlobPart]), name);
  return fetch(`${server.url}/docs/share`, {
    method: "POST",
    headers: auth ? { authorization: `Bearer ${TOKEN}` } : {},
    body: form,
  });
};

describe("partager vers Mate", () => {
  it("dépose un PDF dans le dossier partagé sous son nom", async () => {
    const res = await share("facture.pdf", "%PDF-1.4");
    assert.equal(res.status, 200);
    assert.deepEqual(await res.json(), { path: "facture.pdf" });
    assert.equal(fs.readFileSync(path.join(docsDir, "facture.pdf"), "utf8"), "%PDF-1.4");
  });

  it("ne remplace jamais un fichier déjà là : (2), (3)…", async () => {
    await share("photo.png", "un");
    const second = await (await share("photo.png", "deux")).json();
    const third = await (await share("photo.png", "trois")).json();
    assert.equal(second.path, "photo (2).png");
    assert.equal(third.path, "photo (3).png");
    assert.equal(fs.readFileSync(path.join(docsDir, "photo.png"), "utf8"), "un");
  });

  it("garde seulement le nom du fichier : rien ne sort du dossier partagé", async () => {
    const res = await share("../../evil.txt", "x");
    assert.equal(res.status, 200);
    assert.equal((await res.json()).path, "evil.txt");
    assert.ok(fs.existsSync(path.join(docsDir, "evil.txt")));
  });

  it("donne un nom à un fichier sans nom exploitable", async () => {
    const res = await share("..", "x");
    assert.equal(res.status, 200);
    assert.match((await res.json()).path, /^Reçu /);
  });

  it("garde les accents du nom (UTF-8, comme l'envoie la tablette)", async () => {
    const res = await share("Reçu d'été.pdf", "x");
    assert.equal((await res.json()).path, "Reçu d'été.pdf");
  });

  it("refuse sans jeton (401) et sans fichier (400)", async () => {
    assert.equal((await share("a.pdf", "x", false)).status, 401);
    const empty = await fetch(`${server.url}/docs/share`, {
      method: "POST",
      headers: { authorization: `Bearer ${TOKEN}`, "content-type": "application/json" },
      body: "{}",
    });
    assert.equal(empty.status, 400);
  });

  it("refuse plus de 20 Mo (413) et ne dépose rien", async () => {
    const res = await share("gros.bin", new Uint8Array(21 * 1024 * 1024));
    assert.equal(res.status, 413);
    assert.equal(fs.existsSync(path.join(docsDir, "gros.bin")), false);
  });

  it("le fichier déposé apparaît dans la liste du dossier partagé", async () => {
    const { json } = await server.call("GET", "/docs");
    assert.ok(json.entries.some((e: { name: string }) => e.name === "facture.pdf"));
  });
});
