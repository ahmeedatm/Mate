import assert from "node:assert/strict";
import { test } from "node:test";
import { parseMacmonPower, parseMacmonTemp } from "../src/mac/temperature.ts";
import { CAPTEURS_MAX_AGE_MS, parseCapteursReading } from "../src/win/capteurs.ts";

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
  assert.equal(parseCapteursReading(text, 1_000_000 + CAPTEURS_MAX_AGE_MS)?.cpuTempC, 40.4);
});

test("capteur Windows : fichier trop ancien (capteur arrêté) ou daté du futur → null", () => {
  const text = JSON.stringify({ cpuTempC: 40.4, at: 1_000_000 });
  assert.equal(parseCapteursReading(text, 1_000_000 + CAPTEURS_MAX_AGE_MS + 1), null);
  assert.equal(parseCapteursReading(text, 1_000_000 - 60_000), null);
});

test("capteur Windows : fichier vide, illisible ou sans température (0 = lecture ratée) → null", () => {
  for (const text of ["", "{", "{}", '{"at":1}', '{"cpuTempC":0,"at":1}', '{"cpuTempC":null,"at":1}', '{"cpuTempC":"x","at":1}', "null"]) {
    assert.equal(parseCapteursReading(text, 1), null, text);
  }
});

test("macmon : puissance du processeur et de la carte graphique en watts", () => {
  assert.deepEqual(parseMacmonPower('{"cpu_power":4.46,"gpu_power":0.12,"all_power":4.5}'), { cpuWatts: 4.46, gpuWatts: 0.12 });
  assert.deepEqual(parseMacmonPower('{"cpu_power":4.46}'), { cpuWatts: 4.46, gpuWatts: null });
});

test("macmon : puissance absente ou illisible → null partout", () => {
  for (const out of ["", "pas du json", "{}", "[]", "null", '{"cpu_power":"fort","gpu_power":null}']) {
    assert.deepEqual(parseMacmonPower(out), { cpuWatts: null, gpuWatts: null }, out);
  }
});

test("capteur Windows : watts du processeur et de la carte, ventilateur du processeur lus dans le fichier récent", () => {
  const text = JSON.stringify({ cpuTempC: 40.4, cpuWatts: 31.5, cpuFanRpm: 1180, gpuWatts: 95.2, at: 1_000_000 });
  assert.deepEqual(parseCapteursReading(text, 1_000_000), { cpuTempC: 40.4, cpuWatts: 31.5, cpuFanRpm: 1180, gpuWatts: 95.2 });
});

test("capteur Windows : fichier sans température mais avec des watts → les watts sont lus ; périmé ou illisible → null", () => {
  const text = JSON.stringify({ cpuWatts: 20, at: 1_000_000 });
  assert.deepEqual(parseCapteursReading(text, 1_000_000), { cpuWatts: 20 });
  assert.equal(parseCapteursReading(text, 1_000_000 + CAPTEURS_MAX_AGE_MS + 1), null);
  for (const bad of ["", "{", "{}", '{"at":1}', '{"cpuWatts":"x","at":1}', "null"]) assert.equal(parseCapteursReading(bad, 1), null, bad);
});
