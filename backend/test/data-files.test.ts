import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { describe, it } from "node:test";
import { loadIdentity } from "../src/data-files.ts";
import { loadTls } from "../src/tls.ts";

// Fichiers de .data abîmés (coupure pendant l'écriture, édition à la main) : mis de côté puis recréés, jamais bloquants.

const tmp = () => fs.mkdtempSync(path.join(os.tmpdir(), "companion-data-"));
const setAside = (dir: string, name: string) => fs.readdirSync(dir).filter((f) => f.startsWith(`${name}.abime-`));

describe("identity.json", () => {
  it("se crée avec un id et un jeton, puis se relit à l'identique", () => {
    const dir = tmp();
    const first = loadIdentity(dir);
    assert.match(first.token, /^[0-9a-f]{48}$/);
    assert.deepEqual(loadIdentity(dir), first);
  });

  for (const [label, content] of [
    ["sans jeton", '{ "id": "a" }'],
    ["jeton vide", '{ "id": "a", "token": "" }'],
    ["jeton qui n'est pas un texte", '{ "id": "a", "token": {} }'],
    ["JSON coupé", '{ "id": "a", "tok'],
  ]) {
    it(`${label} : jamais de serveur sans jeton, l'ancien fichier est gardé à côté`, () => {
      const dir = tmp();
      fs.writeFileSync(path.join(dir, "identity.json"), content);
      const identity = loadIdentity(dir);
      assert.match(identity.token, /^[0-9a-f]{48}$/);
      assert.deepEqual(JSON.parse(fs.readFileSync(path.join(dir, "identity.json"), "utf8")), identity);
      assert.equal(setAside(dir, "identity.json").length, 1);
    });
  }
});

describe("tls.json", () => {
  for (const [label, content] of [
    ["JSON coupé", '{ "key": "-----BEGIN'],
    ["certificat illisible", '{ "key": "x", "cert": "y" }'],
  ]) {
    it(`${label} : un nouveau certificat, le serveur démarre`, () => {
      const dir = tmp();
      fs.writeFileSync(path.join(dir, "tls.json"), content);
      const tls = loadTls(dir);
      assert.match(tls.fingerprint, /^[0-9a-f]{64}$/);
      assert.deepEqual(loadTls(dir), tls);
      assert.equal(setAside(dir, "tls.json").length, 1);
    });
  }
});
