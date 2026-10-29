import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { describe, it } from "node:test";
import { adoptData } from "../src/paths.ts";

// Données d'un paquet d'avant (dossier « UnowhyCompanion » d'avant le renommage UnoMate, .data à côté du serveur) :
// recopiées une fois, pour que la tablette retrouve le même ordinateur.

function dir(files: Record<string, string> = {}) {
  const d = fs.mkdtempSync(path.join(os.tmpdir(), "companion-paths-"));
  for (const [name, text] of Object.entries(files)) fs.writeFileSync(path.join(d, name), text);
  return d;
}

describe("reprise des données d'un paquet d'avant", () => {
  it("recopie identité et données dans un dossier neuf, sans service.pid, et garde l'original", () => {
    const old = dir({ "identity.json": '{"id":"a"}', "tasks.json": "[]", "service.pid": "42" });
    const fresh = path.join(dir(), "UnoMate", "data");
    assert.equal(adoptData(old, fresh), true);
    assert.equal(fs.readFileSync(path.join(fresh, "identity.json"), "utf8"), '{"id":"a"}');
    assert.equal(fs.existsSync(path.join(fresh, "tasks.json")), true);
    assert.equal(fs.existsSync(path.join(fresh, "service.pid")), false);
    assert.equal(fs.existsSync(path.join(old, "identity.json")), true);
  });

  it("n'écrase jamais une identité déjà là, et ne fait rien sans ancienne identité", () => {
    const old = dir({ "identity.json": '{"id":"ancien"}' });
    const current = dir({ "identity.json": '{"id":"actuel"}' });
    assert.equal(adoptData(old, current), false);
    assert.equal(fs.readFileSync(path.join(current, "identity.json"), "utf8"), '{"id":"actuel"}');
    assert.equal(adoptData(dir({ "tasks.json": "[]" }), path.join(dir(), "neuf")), false);
    assert.equal(adoptData(old, old), false);
  });
});
