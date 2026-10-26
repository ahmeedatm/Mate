import assert from "node:assert/strict";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { after, before, beforeEach, describe, it } from "node:test";
import { startServer, type TestServer } from "./server.ts";

const dir = await fs.mkdtemp(path.join(os.tmpdir(), "companion-perso-"));
process.env.DATA_DIR = dir;
const { loadKeys, saveKey, SLOTS } = await import("../src/perso.ts");

describe("touches perso : stockage", () => {
  it("14 emplacements vides au départ", async () => {
    assert.equal(SLOTS, 14);
    assert.deepEqual(await loadKeys(), Array(14).fill(null));
  });

  it("un fichier plus court est complété, un plus long est coupé", async () => {
    const key = { label: "A", steps: [{ type: "keys" as const, keys: "cmd+a" }] };
    await fs.writeFile(path.join(dir, "keys.json"), JSON.stringify({ slots: [key] }));
    const keys = await loadKeys();
    assert.equal(keys.length, 14);
    assert.deepEqual(keys[0], key);
    await fs.writeFile(path.join(dir, "keys.json"), JSON.stringify({ slots: Array(20).fill(key) }));
    assert.equal((await loadKeys()).length, 14);
  });

  it("enregistrer ou vider un emplacement ne touche pas aux autres, même en même temps", async () => {
    await fs.rm(path.join(dir, "keys.json"), { force: true });
    const key = (i: number) => ({ label: `T${i}`, steps: [{ type: "text" as const, text: String(i) }] });
    await Promise.all(Array.from({ length: SLOTS }, (_, i) => saveKey(i, key(i))));
    await saveKey(5, null);
    const keys = await loadKeys();
    assert.deepEqual(keys.map((k) => k?.label ?? null), Array.from({ length: SLOTS }, (_, i) => (i === 5 ? null : `T${i}`)));
  });
});

describe("touches perso : routes", () => {
  let server: TestServer;
  before(async () => {
    server = await startServer();
  });
  after(async () => {
    await server?.stop();
  });
  beforeEach(() => server.clearOsCalls());

  const put = (slot: number | string, body: unknown) => server.call("PUT", `/deck/keys/${slot}`, body);

  it("enregistre une macro, la montre dans le deck et l'exécute dans l'ordre", async () => {
    const macro = {
      label: "Réunion",
      glyph: "📅",
      color: "#3366CC",
      steps: [{ type: "app", name: "Notes" }, { type: "wait", ms: 0 }, { type: "keys", keys: "cmd+n", app: "Notes" }, { type: "text", text: "Ordre du jour" }],
    };
    assert.equal((await put(0, macro)).status, 200);
    assert.deepEqual((await server.call("GET", "/deck/keys/0")).json.key, macro);

    const perso = (await server.call("GET", "/deck")).json.pages.at(-1);
    assert.deepEqual(perso.tiles[0], { id: "k-0", label: "Réunion", type: "macro", glyph: "📅", color: "#3366CC", when: null, app: null });

    assert.equal((await server.call("POST", "/deck/run", { id: "k-0" })).status, 200);
    assert.deepEqual(server.osCalls(), [
      { fn: "launchApp", args: ["Notes"] },
      { fn: "sendKeys", args: ["cmd+n", "Notes"] },
      { fn: "typeText", args: ["Ordre du jour"] },
    ]);
  });

  it("une touche d'une seule étape garde son type", async () => {
    await put(1, { label: "Capture", steps: [{ type: "system", command: "screenshot-clipboard" }] });
    const perso = (await server.call("GET", "/deck")).json.pages.at(-1);
    assert.equal(perso.tiles[1].type, "system");
  });

  it("retire les champs qui n'appartiennent pas au type de l'étape", async () => {
    await put(2, { label: "Lien", steps: [{ type: "url", url: "https://example.com", keys: "cmd+q", name: "Terminal" }] });
    assert.deepEqual((await server.call("GET", "/deck/keys/2")).json.key.steps, [{ type: "url", url: "https://example.com" }]);
  });

  it("refuse les touches mal formées", async () => {
    const bad: [string, unknown][] = [
      ["sans étape", { label: "Vide", steps: [] }],
      ["sans nom", { label: "", steps: [{ type: "keys", keys: "cmd+a" }] }],
      ["type inconnu", { label: "X", steps: [{ type: "shell", command: "rm -rf ~" }] }],
      ["bouton (réservé à config/deck.json)", { label: "X", steps: [{ type: "press", app: "Discord", names: ["Déconnecter"] }] }],
      ["champ obligatoire absent", { label: "X", steps: [{ type: "app" }] }],
      ["attente trop longue", { label: "X", steps: [{ type: "wait", ms: 60_000 }] }],
      ["couleur invalide", { label: "X", color: "red", steps: [{ type: "keys", keys: "cmd+a" }] }],
      ["commande système inconnue", { label: "X", steps: [{ type: "system", command: "shutdown" }] }],
      ["plus de 20 étapes", { label: "X", steps: Array(21).fill({ type: "keys", keys: "a" }) }],
    ];
    for (const [why, body] of bad) assert.equal((await put(3, body)).status, 400, why);
    assert.equal((await server.call("GET", "/deck/keys/3")).json.key, null);
  });

  it("refuse un emplacement hors de la page", async () => {
    const key = { label: "X", steps: [{ type: "keys", keys: "a" }] };
    assert.equal((await put(14, key)).status, 400);
    assert.equal((await put(-1, key)).status, 400);
    assert.equal((await put("abc", key)).status, 400);
  });

  it("vide un emplacement", async () => {
    await put(4, { label: "Temp", steps: [{ type: "keys", keys: "a" }] });
    assert.equal((await server.call("DELETE", "/deck/keys/4")).status, 200);
    assert.equal((await server.call("GET", "/deck/keys/4")).json.key, null);
    assert.equal((await server.call("POST", "/deck/run", { id: "k-4" })).status, 404);
  });
});
