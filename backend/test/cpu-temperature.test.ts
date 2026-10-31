import assert from "node:assert/strict";
import { test } from "node:test";
import { parseMacmonTemp } from "../src/mac/temperature.ts";
import { CAPTEURS_MAX_AGE_MS, parseCapteursFile } from "../src/win/capteurs.ts";

test("macmon : température moyenne du processeur lue dans la sortie JSON", () => {
  assert.equal(parseMacmonTemp('{"cpu_usage_pct":0.2,"temp":{"cpu_temp_avg":64.05,"gpu_temp_avg":66.2}}'), 64.05);
});

test("macmon : sortie vide, illisible ou sans température → null (rien d'affiché)", () => {
  for (const out of [
    "",
    "pas du json",
    "{}",
    '{"temp":{}}',
    '{"temp":{"cpu_temp_avg":null}}',
    '{"temp":{"cpu_temp_avg":"chaud"}}',
    "[]",
    "null",
  ]) {
    assert.equal(parseMacmonTemp(out), null, out);
  }
});

test("capteur Windows : valeur lue si le fichier est récent", () => {
  const text = JSON.stringify({ cpuTempC: 40.4, at: 1_000_000 });
  assert.equal(parseCapteursFile(text, 1_000_000 + CAPTEURS_MAX_AGE_MS), 40.4);
});

test("capteur Windows : fichier trop ancien (capteur arrêté) ou daté du futur → null", () => {
  const text = JSON.stringify({ cpuTempC: 40.4, at: 1_000_000 });
  assert.equal(parseCapteursFile(text, 1_000_000 + CAPTEURS_MAX_AGE_MS + 1), null);
  assert.equal(parseCapteursFile(text, 1_000_000 - 60_000), null);
});

test("capteur Windows : fichier vide, illisible ou sans température (0 = lecture ratée) → null", () => {
  for (const text of ["", "{", "{}", '{"at":1}', '{"cpuTempC":0,"at":1}', '{"cpuTempC":null,"at":1}', '{"cpuTempC":"x","at":1}', "null"]) {
    assert.equal(parseCapteursFile(text, 1), null, text);
  }
});
