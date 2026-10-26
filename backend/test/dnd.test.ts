import "./fake-os-hook.ts";
import assert from "node:assert/strict";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { afterEach, beforeEach, describe, it } from "node:test";
import { calls, state } from "./fake-os.ts";

const dir = await fs.mkdtemp(path.join(os.tmpdir(), "companion-dnd-"));
process.env.DATA_DIR = dir;
const { focusDnd } = await import("../src/dnd.ts");

const file = path.join(dir, "dnd.json");
const ours = async () => JSON.parse(await fs.readFile(file, "utf8")).ours;
const setDndCalls = () => calls.filter((c) => c.fn === "setDnd").map((c) => c.args[0]);

describe("Ne pas déranger pendant le Focus", () => {
  beforeEach(async () => {
    calls.length = 0;
    await fs.rm(file, { force: true });
  });
  // Arrête le minuteur de secours laissé par un test.
  afterEach(() => focusDnd(false));

  it("l'active au début de la concentration et le rend à la fin", async () => {
    state.dnd = false;
    assert.deepEqual(await focusDnd(true, 25), { on: true });
    assert.equal(await ours(), true);
    assert.deepEqual(await focusDnd(true, 20), { on: true }, "reprise après une pause : rien de plus");
    assert.deepEqual(await focusDnd(false), { on: false });
    assert.equal(await ours(), false);
    assert.deepEqual(setDndCalls(), [true, false]);
  });

  it("déjà mis par l'utilisateur : n'y touche pas, ni au début ni à la fin", async () => {
    state.dnd = true;
    assert.deepEqual(await focusDnd(true), { on: true });
    assert.deepEqual(await focusDnd(false), { on: true });
    assert.deepEqual(setDndCalls(), []);
  });

  it("après un redémarrage du compagnon, rend celui qu'il avait activé", async () => {
    state.dnd = true;
    await fs.writeFile(file, JSON.stringify({ ours: true }));
    assert.deepEqual(await focusDnd(false), { on: false });
    assert.deepEqual(setDndCalls(), [false]);
  });

  it("minuteur de secours : rendu 2 min après la fin prévue si la tablette ne dit plus rien", async (t) => {
    t.mock.timers.enable({ apis: ["setTimeout"] });
    state.dnd = false;
    await focusDnd(true, 10);
    t.mock.timers.tick(11 * 60_000);
    await new Promise((r) => setImmediate(r));
    assert.deepEqual(setDndCalls(), [true]);
    t.mock.timers.tick(60_000);
    for (let i = 0; i < 20 && setDndCalls().length < 2; i++) await new Promise((r) => setImmediate(r));
    assert.deepEqual(setDndCalls(), [true, false]);
    assert.equal(state.dnd, false);
  });
});
