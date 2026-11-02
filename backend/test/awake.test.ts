import "./fake-os-hook.ts";
import assert from "node:assert/strict";
import { afterEach, beforeEach, describe, it } from "node:test";
import { calls } from "./fake-os.ts";

const { focusAwake } = await import("../src/awake.ts");

const keepAwakeCalls = () => calls.filter((c) => c.fn === "keepAwake").map((c) => c.args[0]);

describe("Écran de l'ordinateur éveillé pendant le Focus", () => {
  beforeEach(() => {
    calls.length = 0;
  });
  // Arrête le minuteur de secours laissé par un test.
  afterEach(() => focusAwake(false));

  it("le garde éveillé pendant la concentration et rend la veille à la fin", async () => {
    assert.deepEqual(await focusAwake(true, 25), { on: true });
    assert.deepEqual(await focusAwake(false), { on: false });
    assert.deepEqual(keepAwakeCalls(), [true, false]);
  });

  it("minuteur de secours : rend la veille 2 min après la fin prévue si la tablette ne dit plus rien", async (t) => {
    t.mock.timers.enable({ apis: ["setTimeout"] });
    await focusAwake(true, 10);
    t.mock.timers.tick(11 * 60_000);
    assert.deepEqual(keepAwakeCalls(), [true]);
    t.mock.timers.tick(60_000);
    await new Promise((r) => setImmediate(r));
    assert.deepEqual(keepAwakeCalls(), [true, false]);
  });

  it("une reprise après la pause repousse le minuteur de secours", async (t) => {
    t.mock.timers.enable({ apis: ["setTimeout"] });
    await focusAwake(true, 10);
    await focusAwake(true, 30);
    t.mock.timers.tick(15 * 60_000);
    assert.deepEqual(keepAwakeCalls(), [true, true]);
  });
});
