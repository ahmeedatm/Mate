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

  it("refuse un fichier sans nom exploitable (415)", async () => {
    assert.equal((await share("..", "x")).status, 415);
  });

  it("garde les accents du nom (UTF-8, comme l'envoie la tablette)", async () => {
    const res = await share("Reçu d'été.pdf", "x");
    assert.equal((await res.json()).path, "Reçu d'été.pdf");
  });

  it("remplace les caractères interdits sous Windows (titre de page : « a: b? »)", async () => {
    const res = await share("Titre: page? <1>.txt", "x");
    assert.equal((await res.json()).path, "Titre_ page_ _1_.txt");
  });

  it("évite les noms réservés de Windows (CON, NUL…) et les points ou espaces finaux", async () => {
    assert.equal((await (await share("CON.txt", "x")).json()).path, "_CON.txt");
    assert.equal((await (await share("nul.txt", "x")).json()).path, "_nul.txt");
    assert.equal((await (await share("fin.txt. . ", "x")).json()).path, "fin.txt");
  });

  it("teste le nom réservé sur la partie avant le premier point (con.x.txt, nul.tar.gz)", async () => {
    assert.equal((await (await share("con.x.txt", "x")).json()).path, "_con.x.txt");
    assert.equal((await (await share("LPT1.backup.csv", "x")).json()).path, "_LPT1.backup.csv");
    assert.equal((await share("nul.tar.gz", "x")).status, 415);
  });

  it("retire les caractères de direction et invisibles du nom", async () => {
    assert.equal((await (await share("devis\u200B-ok\u202E.pdf", "x")).json()).path, "devis-ok.pdf");
    assert.equal((await (await share("a\u2066b\u2069\u200Fc\u202A.txt", "x")).json()).path, "abc.txt");
    // Le piège classique : « photo<RLO>gpj.exe » s'afficherait « photoexe.jpg ».
    assert.equal((await share("photo\u202Egpj.exe", "x")).status, 415);
  });

  it("ne dépose pas de fichier caché : les points de tête sont retirés", async () => {
    assert.equal((await (await share(".notes.txt", "x")).json()).path, "notes.txt");
    assert.equal((await (await share("..a.md", "x")).json()).path, "a.md");
  });

  it("raccourcit un nom trop long en gardant l'extension", async () => {
    const res = await share("a".repeat(300) + ".pdf", "x");
    assert.equal(res.status, 200);
    const name = (await res.json()).path as string;
    assert.ok(name.length <= 130 && name.endsWith(".pdf"), name);
  });

  it("refuse tout type hors liste blanche (415) et ne dépose rien", async () => {
    for (const name of [
      "virus.exe",
      "acces.rdp",
      "macro.docm",
      "page.html",
      "script.ps1",
      "lien.lnk",
      "sans-extension",
      ".bashrc",
      "double.pdf.exe",
    ]) {
      const res = await share(name, "x");
      assert.equal(res.status, 415, name);
      assert.equal((await res.json()).message, "Type de fichier non accepté");
    }
    assert.equal(fs.existsSync(path.join(docsDir, "virus.exe")), false);
  });

  it("accepte chaque type de la liste blanche, majuscules comprises", async () => {
    const exts = "pdf jpg jpeg png gif webp heic txt md csv json log docx xlsx pptx odt ods odp zip mp3 m4a mp4 mov".split(" ");
    for (const ext of exts) assert.equal((await share(`liste.${ext}`, "x")).status, 200, ext);
    assert.equal((await share("MAJ.PDF", "x")).status, 200);
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
    const res = await share("gros.pdf", new Uint8Array(21 * 1024 * 1024));
    assert.equal(res.status, 413);
    assert.equal(fs.existsSync(path.join(docsDir, "gros.pdf")), false);
  });

  it("le fichier déposé apparaît dans la liste du dossier partagé", async () => {
    const { json } = await server.call("GET", "/docs");
    assert.ok(json.entries.some((e: { name: string }) => e.name === "facture.pdf"));
  });
});
