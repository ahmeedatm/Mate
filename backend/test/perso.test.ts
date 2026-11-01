import assert from "node:assert/strict";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { after, before, beforeEach, describe, it } from "node:test";
import { startServer, type TestServer } from "./server.ts";

const dir = await fs.mkdtemp(path.join(os.tmpdir(), "companion-perso-"));
process.env.DATA_DIR = dir;
const { loadKeys, saveKey, MIN_SLOTS, MAX_SLOTS } = await import("../src/perso.ts");

describe("touches perso : stockage", () => {
  const key = (i = 0) => ({ label: `T${i}`, steps: [{ type: "text" as const, text: String(i) }] });
  const fileSlots = async () => JSON.parse(await fs.readFile(path.join(dir, "keys.json"), "utf8")).slots as unknown[];

  beforeEach(() => fs.rm(path.join(dir, "keys.json"), { force: true }));

  it("14 emplacements vides au départ", async () => {
    assert.equal(MIN_SLOTS, 14);
    assert.deepEqual(await loadKeys(), Array(14).fill(null));
  });

  it("un fichier plus court est complété", async () => {
    await fs.writeFile(path.join(dir, "keys.json"), JSON.stringify({ slots: [key()] }));
    const keys = await loadKeys();
    assert.equal(keys.length, MIN_SLOTS);
    assert.deepEqual(keys[0], key());
  });

  it("il reste toujours un emplacement libre après le dernier rempli", async () => {
    await Promise.all(Array.from({ length: 14 }, (_, i) => saveKey(i, key(i))));
    const full = await loadKeys();
    assert.equal(full.length, 15);
    assert.equal(full[14], null);
    await saveKey(20, key(20));
    assert.equal((await loadKeys()).length, 22);
    await saveKey(20, null);
    assert.equal((await loadKeys()).length, 15);
  });

  it("un fichier plus long que le plafond est coupé", async () => {
    await fs.writeFile(path.join(dir, "keys.json"), JSON.stringify({ slots: Array(120).fill(key()) }));
    assert.equal((await loadKeys()).length, MAX_SLOTS);
  });

  it("vider la dernière touche retire les null de fin du fichier", async () => {
    await saveKey(2, key(2));
    await saveKey(20, key(20));
    assert.equal((await fileSlots()).length, 21);
    await saveKey(20, null);
    assert.equal((await fileSlots()).length, 3);
    await saveKey(2, null);
    assert.equal((await fileSlots()).length, 0);
  });

  it("enregistrer ou vider un emplacement ne touche pas aux autres, même en même temps", async () => {
    await Promise.all(Array.from({ length: MIN_SLOTS }, (_, i) => saveKey(i, key(i))));
    await saveKey(5, null);
    const keys = await loadKeys();
    assert.deepEqual(
      keys.slice(0, MIN_SLOTS).map((k) => k?.label ?? null),
      Array.from({ length: MIN_SLOTS }, (_, i) => (i === 5 ? null : `T${i}`)),
    );
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
      steps: [
        { type: "app", name: "Notes" },
        { type: "wait", ms: 0 },
        { type: "keys", keys: "cmd+n", app: "Notes" },
        { type: "text", text: "Ordre du jour" },
      ],
    };
    assert.equal((await put(0, macro)).status, 200);
    assert.deepEqual((await server.call("GET", "/deck/keys/0")).json.key, macro);

    const perso = (await server.call("GET", "/deck")).json.pages.at(-1);
    assert.deepEqual(perso.tiles[0], {
      id: "k-0",
      label: "Réunion",
      type: "macro",
      symbol: null,
      glyph: "📅",
      color: "#3366CC",
      when: null,
      lit: null,
      state: null,
      app: null,
    });

    assert.equal((await server.call("POST", "/deck/run", { id: "k-0" })).status, 200);
    assert.deepEqual(server.osCalls(), [
      { fn: "launchApp", args: ["Notes"] },
      { fn: "sendKeys", args: ["cmd+n", "Notes"] },
      { fn: "typeText", args: ["Ordre du jour"] },
    ]);
  });

  it("une touche à cycle fait une étape par appui, puis recommence", async () => {
    const cycle = {
      label: "Thème",
      cycle: true,
      steps: [
        { type: "text", text: "Jour" },
        { type: "text", text: "Nuit" },
        { type: "text", text: "Auto" },
      ],
    };
    assert.equal((await put(9, cycle)).status, 200);
    assert.deepEqual((await server.call("GET", "/deck/keys/9")).json.key, cycle);
    assert.equal((await server.call("GET", "/deck")).json.pages.at(-1).tiles[9].type, "cycle");

    for (let i = 0; i < 4; i++) assert.equal((await server.call("POST", "/deck/run", { id: "k-9" })).status, 200);
    assert.deepEqual(
      server.osCalls().map((c) => c.args[0]),
      ["Jour", "Nuit", "Auto", "Jour"],
    );
    await server.call("DELETE", "/deck/keys/9");
  });

  it("une touche sans cycle garde son enregistrement tel quel", async () => {
    await put(12, {
      label: "Deux",
      steps: [
        { type: "text", text: "a" },
        { type: "text", text: "b" },
      ],
    });
    assert.equal("cycle" in (await server.call("GET", "/deck/keys/12")).json.key, false);
    await server.call("DELETE", "/deck/keys/12");
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

  it("garde le symbole d'une touche et accepte les commandes micro et Ne pas déranger", async () => {
    const key = { label: "Micro", symbol: "mic_off", steps: [{ type: "system", command: "mute-mic" }] };
    assert.equal((await put(4, key)).status, 200);
    assert.deepEqual((await server.call("GET", "/deck/keys/4")).json.key, key);
    const tile = (await server.call("GET", "/deck")).json.pages.at(-1).tiles[4];
    assert.equal(tile.symbol, "mic_off");
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
      ["symbole inconnu", { label: "X", symbol: "fusee", steps: [{ type: "keys", keys: "cmd+a" }] }],
      ["plus de 20 étapes", { label: "X", steps: Array(21).fill({ type: "keys", keys: "a" }) }],
    ];
    for (const [why, body] of bad) assert.equal((await put(3, body)).status, 400, why);
    assert.equal((await server.call("GET", "/deck/keys/3")).json.key, null);
  });

  it("refuse un emplacement hors de la page", async () => {
    const key = { label: "X", steps: [{ type: "keys", keys: "a" }] };
    assert.equal((await put(MAX_SLOTS, key)).status, 400);
    assert.equal((await put(-1, key)).status, 400);
    assert.equal((await put("abc", key)).status, 400);
  });

  it("PUT /deck/keys/20 enregistre la 21e touche et /deck la montre", async () => {
    assert.equal((await put(20, { label: "Vingt et un", steps: [{ type: "keys", keys: "a" }] })).status, 200);
    const perso = (await server.call("GET", "/deck")).json.pages.at(-1);
    assert.equal(perso.tiles.find((t: { id: string }) => t.id === "k-20").label, "Vingt et un");
    assert.equal(perso.tiles.length, 22);
    await server.call("DELETE", "/deck/keys/20");
  });

  it("PUT /deck/keys/99 passe, /deck/keys/100 est refusé", async () => {
    const key = { label: "X", steps: [{ type: "keys", keys: "a" }] };
    assert.equal((await put(99, key)).status, 200);
    assert.equal((await put(100, key)).status, 400);
    await server.call("DELETE", "/deck/keys/99");
  });

  it("vide un emplacement", async () => {
    await put(4, { label: "Temp", steps: [{ type: "keys", keys: "a" }] });
    assert.equal((await server.call("DELETE", "/deck/keys/4")).status, 200);
    assert.equal((await server.call("GET", "/deck/keys/4")).json.key, null);
    assert.equal((await server.call("POST", "/deck/run", { id: "k-4" })).status, 404);
  });
});
