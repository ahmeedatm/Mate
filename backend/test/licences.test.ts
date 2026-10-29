import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { after, before, describe, it } from "node:test";
import { bundledPackages, noticeOf, thirdPartyNotices } from "../scripts/licences.ts";
import { startServer, type TestServer } from "./server.ts";

describe("mentions de licences du compagnon installé", () => {
  it("retrouve chaque paquet regroupé, portée et imbrication comprises", () => {
    const inputs = [
      "src/server.ts",
      "node_modules/fastify/fastify.js",
      "node_modules/fastify/lib/route.js",
      "node_modules/@fastify/busboy/lib/main.js",
      "node_modules/light-my-request/node_modules/cookie/index.js",
    ];
    assert.deepEqual(bundledPackages(inputs), [
      "node_modules/@fastify/busboy",
      "node_modules/fastify",
      "node_modules/light-my-request/node_modules/cookie",
    ]);
  });

  it("lit le fichier de licence, sinon joint le texte type MIT au nom de l'auteur", () => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), "licences-"));
    const withFile = path.join(root, "a");
    const without = path.join(root, "b");
    fs.mkdirSync(withFile);
    fs.mkdirSync(without);
    fs.writeFileSync(path.join(withFile, "package.json"), JSON.stringify({ name: "a", version: "1.0.0", license: "MIT" }));
    fs.writeFileSync(path.join(withFile, "LICENSE.md"), "Copyright (c) Auteur A\n");
    fs.writeFileSync(
      path.join(without, "package.json"),
      JSON.stringify({ name: "b", version: "2.0.0", license: "MIT", author: { name: "Auteur B" } }),
    );

    assert.equal(noticeOf(withFile).text, "Copyright (c) Auteur A");
    const b = noticeOf(without);
    assert.match(b.text, /^MIT License\n\nCopyright \(c\) Auteur B\n/);
    assert.match(b.text, /The above copyright notice and this permission notice shall be included/);
  });

  it("liste d'abord, textes ensuite, chaque paquet une seule fois, node en tête", () => {
    const n = { name: "zod", version: "4.0.0", license: "MIT", text: "texte zod" };
    const text = thirdPartyNotices([n, { ...n }, { name: "ajv", version: "8.0.0", license: "MIT", text: "texte ajv" }], {
      version: "v22.0.0",
      license: "texte node",
    });
    const list = text.split("\n").filter((l) => l.startsWith("- "));
    assert.deepEqual(list, [
      "- Node.js v22.0.0 (MIT et licences de ses composants, texte complet plus bas)",
      "- ajv 8.0.0 (MIT)",
      "- zod 4.0.0 (MIT)",
    ]);
    assert.equal(text.split("texte zod").length, 2);
    assert.ok(text.indexOf("texte node") < text.indexOf("texte ajv"));
  });
});

describe("GET /local/licences", () => {
  let server: TestServer;
  before(async () => {
    server = await startServer();
  });
  after(async () => {
    await server?.stop();
  });

  it("depuis le dépôt, pas de fichier : 404 et pas de lien sur la page locale", async () => {
    // Le fichier n'existe que dans le compagnon installé (scripts/package.ts).
    assert.equal(fs.existsSync(path.join(import.meta.dirname, "..", "LICENCES-TIERS.txt")), false);
    assert.equal((await server.call("GET", "/local/licences", undefined, false)).status, 404);
    assert.equal((await server.call("GET", "/local/status", undefined, false)).json.licences, false);
  });
});
