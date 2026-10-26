import assert from "node:assert/strict";
import { test } from "node:test";
import { parseCombo, planAudio, sortedNames } from "../src/driver-common.ts";

const state = { output: 40, muted: true, input: 50, micMuted: false };

test("planAudio : bornes, delta, monter réactive le son", () => {
  assert.deepEqual(planAudio({ output: 150 }, state), { output: 100, muted: false });
  assert.deepEqual(planAudio({ outputDelta: -60 }, state), { output: 0, muted: undefined });
  assert.deepEqual(planAudio({ outputDelta: 10 }, state), { output: 50, muted: false });
  assert.deepEqual(planAudio({ output: 20, muted: true }, state), { output: 20, muted: true });
  assert.deepEqual(planAudio({ outputDelta: 5 }, { ...state, output: null }), { output: undefined, muted: undefined });
});

test("parseCombo : touche, modificateurs, erreurs", () => {
  const mods = { cmd: 1, shift: 2 };
  assert.deepEqual(parseCombo("Cmd + Shift+M", mods), { key: "m", mods: [1, 2] });
  assert.deepEqual(parseCombo("enter", mods), { key: "enter", mods: [] });
  assert.throws(() => parseCombo("", mods), /Raccourci invalide/);
  assert.throws(() => parseCombo("foo+m", mods), /Modificateur inconnu : foo/);
});

test("sortedNames : sans doublon, ordre français", () => {
  assert.deepEqual(sortedNames(["zoom", "Écran", "Word", "zoom", "Alpha"]), ["Alpha", "Écran", "Word", "zoom"]);
});
