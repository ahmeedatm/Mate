import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { after, before, beforeEach, describe, it } from "node:test";
import { startServer, type TestServer } from "./server.ts";

const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), "companion-duplicate-"));
const micro = {
  label: "Micro",
  color: "#B8F34A",
  steps: [{ type: "system", command: "mute-mic" }],
  hold: [{ type: "text", text: "salut" }],
};
const pagesFile = path.join(dataDir, "pages.json");
const seed = () => {
  fs.writeFileSync(
    pagesFile,
    JSON.stringify({
      pages: [
        { title: "Réunions", slots: [micro] },
        { title: "Jeux", slots: [] },
      ],
    }),
  );
  fs.rmSync(path.join(dataDir, "keys.json"), { force: true });
};

let server: TestServer;
before(async () => {
  seed();
  server = await startServer({ dataDir });
});
after(async () => {
  await server?.stop();
  fs.rmSync(dataDir, { recursive: true, force: true });
});
beforeEach(async () => {
  await server.stop();
  seed();
  server = await startServer({ dataDir });
});

const keyOf = async (page: number | null, slot: number) =>
  (await server.call("GET", page === null ? `/deck/keys/${slot}` : `/deck/pages/${page}/keys/${slot}`)).json.key;

describe("dupliquer une touche", () => {
  it("copie une touche de page dans le premier emplacement libre d'une autre page, l'original reste", async () => {
    const res = await server.call("POST", "/deck/keys/duplicate", { page: 0, slot: 0, to: 1 });
    assert.equal(res.status, 200);
    assert.deepEqual(res.json, { page: 1, slot: 0 });
    assert.deepEqual(await keyOf(1, 0), micro);
    assert.deepEqual(await keyOf(0, 0), micro);
  });

  it("vers Perso quand `to` est absent, dans le premier trou", async () => {
    await server.call("PUT", "/deck/keys/0", { label: "A", steps: [{ type: "text", text: "a" }] });
    const res = await server.call("POST", "/deck/keys/duplicate", { page: 0, slot: 0 });
    assert.deepEqual(res.json, { slot: 1 });
    assert.deepEqual(await keyOf(null, 1), micro);
  });

  it("sur la même page, la copie va après la touche d'origine", async () => {
    const res = await server.call("POST", "/deck/keys/duplicate", { page: 0, slot: 0, to: 0 });
    assert.deepEqual(res.json, { page: 0, slot: 1 });
  });

  it("la copie est indépendante de l'original", async () => {
    await server.call("POST", "/deck/keys/duplicate", { page: 0, slot: 0, to: 1 });
    await server.call("PUT", "/deck/pages/1/keys/0", { label: "Autre", steps: [{ type: "text", text: "x" }] });
    assert.equal((await keyOf(0, 0)).label, "Micro");
  });

  it("deux copies à la fois prennent deux places différentes", async () => {
    const [a, b] = await Promise.all([
      server.call("POST", "/deck/keys/duplicate", { page: 0, slot: 0, to: 1 }),
      server.call("POST", "/deck/keys/duplicate", { page: 0, slot: 0, to: 1 }),
    ]);
    assert.deepEqual([a.json.slot, b.json.slot].sort(), [0, 1]);
    assert.equal((await keyOf(1, 0)).label, "Micro");
    assert.equal((await keyOf(1, 1)).label, "Micro");
  });

  it("refuse une touche vide ou une page inconnue", async () => {
    assert.equal((await server.call("POST", "/deck/keys/duplicate", { page: 1, slot: 5 })).status, 404);
    assert.equal((await server.call("POST", "/deck/keys/duplicate", { page: 9, slot: 0 })).status, 404);
    assert.equal((await server.call("POST", "/deck/keys/duplicate", { page: 0, slot: 0, to: 9 })).status, 404);
  });

  it("refuse quand la page visée est pleine", async () => {
    const full = Array.from({ length: 100 }, () => micro);
    fs.writeFileSync(pagesFile, JSON.stringify({ pages: [{ title: "Pleine", slots: full }] }));
    await server.stop();
    server = await startServer({ dataDir });
    assert.equal((await server.call("POST", "/deck/keys/duplicate", { page: 0, slot: 0, to: 0 })).status, 409);
  });

  it("existe aussi sur la page locale, sans jeton", async () => {
    const res = await server.call("POST", "/local/config/keys/duplicate", { page: 0, slot: 0, to: 1 }, false);
    assert.deepEqual(res.json, { page: 1, slot: 0 });
  });
});

describe("dupliquer une page", () => {
  it("ajoute une page « (copie) » avec les mêmes touches, à la fin", async () => {
    const res = await server.call("POST", "/deck/pages/0/duplicate");
    assert.equal(res.status, 200);
    assert.deepEqual(res.json, { page: 2 });
    const pages = (await server.call("GET", "/local/config/keys", undefined, false)).json.pages;
    assert.equal(pages.length, 3);
    assert.equal(pages[2].title, "Réunions (copie)");
    assert.deepEqual(pages[2].keys[0], micro);
    assert.equal(pages[0].title, "Réunions");
  });

  it("refuse une page inconnue", async () => {
    assert.equal((await server.call("POST", "/deck/pages/9/duplicate")).status, 404);
  });

  it("refuse au-delà de 50 pages", async () => {
    fs.writeFileSync(pagesFile, JSON.stringify({ pages: Array.from({ length: 50 }, (_, i) => ({ title: `P${i}`, slots: [micro] })) }));
    await server.stop();
    server = await startServer({ dataDir });
    assert.equal((await server.call("POST", "/deck/pages/0/duplicate")).status, 409);
  });

  it("existe aussi sur la page locale, sans jeton", async () => {
    const res = await server.call("POST", "/local/config/pages/1/duplicate", undefined, false);
    assert.deepEqual(res.json, { page: 2 });
  });
});
