import assert from "node:assert/strict";
import { describe, it } from "node:test";
import type { Tile } from "../src/deck.ts";
import { litTiles, startTileStateWatch } from "../src/tile-state.ts";

const until = async (done: () => boolean, ms = 2000) => {
  const end = Date.now() + ms;
  while (!done()) {
    if (Date.now() > end) throw new Error("délai dépassé");
    await new Promise((resolve) => setTimeout(resolve, 2));
  }
};

const tile = (id: string, extra: Partial<Tile>): Tile => ({ id, label: id, action: { type: "app", name: "Notes" }, ...extra });
const readers = (micMuted: boolean, dnd: boolean, running: string[]) => ({
  micMuted: async () => micMuted,
  dnd: async () => dnd,
  running: async (app: string) => running.includes(app),
});

describe("touches qui montrent l'état réel", () => {
  const tiles = [
    tile("mic", { state: "mic-muted", action: { type: "system", command: "mute-mic" } }),
    tile("dnd", { state: "dnd", action: { type: "system", command: "dnd" } }),
    tile("notes", { state: "running" }),
    tile("nue", {}),
  ];

  it("allume chaque touche dont l'état est vrai, et seulement celles-là", async () => {
    assert.deepEqual(await litTiles(tiles, readers(true, false, [])), ["mic"]);
    assert.deepEqual(await litTiles(tiles, readers(false, true, ["Notes"])), ["dnd", "notes"]);
    assert.deepEqual(await litTiles(tiles, readers(false, false, [])), []);
  });

  it("une touche `running` suit l'appli de son action, même dans une macro", async () => {
    const macro = tile("m", { state: "running", action: { type: "macro", steps: [{ type: "app", name: "Spotify" }] } });
    assert.deepEqual(await litTiles([macro], readers(false, false, ["Spotify"])), ["m"]);
  });

  it("une lecture qui échoue éteint la touche sans empêcher les autres", async () => {
    const broken = { ...readers(true, true, []), dnd: async () => Promise.reject(new Error("illisible")) };
    assert.deepEqual(await litTiles(tiles, broken), ["mic"]);
  });

  it("l'état `running` sans appli dans l'action reste éteint", async () => {
    const sans = tile("k", { state: "running", action: { type: "keys", keys: "cmd+c" } });
    assert.deepEqual(await litTiles([sans], readers(false, false, ["Notes"])), []);
  });
});

describe("veille des états : l'ordinateur prévient la tablette", () => {
  it("un ordre par changement, pas un par lecture", async () => {
    const seq = [[], [], ["a"], ["a"], []];
    let reads = 0;
    const events: string[][] = [];
    const stop = startTileStateWatch((lit) => events.push(lit), {
      intervalMs: 1,
      connected: () => true,
      read: async () => seq[Math.min(reads++, seq.length - 1)],
    });
    await until(() => reads >= seq.length);
    stop();
    assert.deepEqual(events, [[], ["a"], []]);
  });

  it("ne lit rien sans tablette connectée", async () => {
    let reads = 0;
    const stop = startTileStateWatch(() => assert.fail("aucun ordre attendu"), {
      intervalMs: 1,
      connected: () => false,
      read: async () => (reads++, []),
    });
    await new Promise((resolve) => setTimeout(resolve, 30));
    stop();
    assert.equal(reads, 0);
  });

  it("tablette revenue : l'état lui est redit, même inchangé", async () => {
    let connected = true;
    const events: string[][] = [];
    const stop = startTileStateWatch((lit) => events.push(lit), { intervalMs: 1, connected: () => connected, read: async () => ["a"] });
    await until(() => events.length === 1);
    connected = false;
    await new Promise((resolve) => setTimeout(resolve, 15));
    connected = true;
    await until(() => events.length === 2);
    stop();
    assert.deepEqual(events, [["a"], ["a"]]);
  });
});
