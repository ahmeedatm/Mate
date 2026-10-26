import assert from "node:assert/strict";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { test } from "node:test";
import { setLogger } from "../src/log.ts";
import { readJson, removeJson, updateJson, writeJson } from "../src/store.ts";

const dir = await fs.mkdtemp(path.join(os.tmpdir(), "companion-store-"));
const traces: string[] = [];
setLogger({ warn: (msg) => traces.push(msg) });

test("fichier absent : valeur par défaut, sans trace", async () => {
  assert.deepEqual(await readJson(path.join(dir, "absent.json"), () => ({ n: 0 })), { n: 0 });
  assert.equal(traces.length, 0);
});

test("fichier vide ou tronqué : valeur par défaut, trace, copie en .bad", async () => {
  for (const [name, content] of [["vide.json", ""], ["tronque.json", '{"items": [{"id": "a"']] as const) {
    const file = path.join(dir, name);
    await fs.writeFile(file, content);
    assert.deepEqual(await readJson(file, () => ({ items: [] })), { items: [] });
    assert.equal(await fs.readFile(file + ".bad", "utf8"), content);
    await assert.rejects(fs.stat(file));
  }
  assert.equal(traces.length, 2);
});

test("quarantine: false laisse le fichier en place", async () => {
  const file = path.join(dir, "a-la-main.json");
  await fs.writeFile(file, "{ coquille");
  assert.deepEqual(await readJson(file, () => ({}), { quarantine: false }), {});
  assert.equal(await fs.readFile(file, "utf8"), "{ coquille");
});

test("écriture : JSON valide, aucun fichier temporaire, mode demandé", async () => {
  const file = path.join(dir, "ecrit.json");
  await writeJson(file, { a: 1 }, { mode: 0o600 });
  assert.deepEqual(JSON.parse(await fs.readFile(file, "utf8")), { a: 1 });
  assert.equal((await fs.stat(file)).mode & 0o777, 0o600);
  assert.deepEqual((await fs.readdir(dir)).filter((f) => f.endsWith(".tmp")), []);
});

test("50 mises à jour simultanées : aucune n'est perdue", async () => {
  const file = path.join(dir, "compteur.json");
  await Promise.all(Array.from({ length: 50 }, () => updateJson(file, () => ({ n: 0 }), (d) => void d.n++)));
  assert.equal((await readJson(file, () => ({ n: -1 }))).n, 50);
});

test("une mise à jour qui échoue n'écrit rien et ne bloque pas la suivante", async () => {
  const file = path.join(dir, "echec.json");
  await writeJson(file, { n: 1 });
  await assert.rejects(updateJson(file, () => ({ n: 0 }), () => { throw new Error("raté"); }), /raté/);
  await updateJson(file, () => ({ n: 0 }), (d) => void d.n++);
  assert.equal((await readJson(file, () => ({ n: -1 }))).n, 2);
});

test("removeJson supprime, et ne dit rien si le fichier n'existe pas", async () => {
  const file = path.join(dir, "ecrit.json");
  await removeJson(file);
  await removeJson(file);
  await assert.rejects(fs.stat(file));
});
