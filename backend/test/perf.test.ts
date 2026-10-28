import assert from "node:assert/strict";
import type os from "node:os";
import { test } from "node:test";
import { parseVmStat } from "../src/mac/system.ts";
import {
  CPU_WAIT_MS,
  FanMemory,
  GPU_WAIT_MS,
  cpuPercent,
  cpuTimes,
  normalizeGpu,
  perfSampler,
  ramUsage,
  type GpuPerf,
  type PerfSources,
} from "../src/perf.ts";

const g = 1024 ** 3;

/** Un cœur factice : seuls les temps comptent. */
function core(idle: number, busy: number): os.CpuInfo {
  return {
    model: "x",
    speed: 0,
    times: { user: busy, nice: 0, sys: 0, idle, irq: 0 },
  };
}

test("cpuTimes : somme sur tous les cœurs", () => {
  assert.deepEqual(cpuTimes([core(10, 30), core(20, 40)]), { idle: 30, total: 100 });
});

test("cpuPercent : 25 % d'activité", () => {
  assert.equal(cpuPercent({ idle: 100, total: 200 }, { idle: 175, total: 300 }), 25);
});

test("cpuPercent : aucun écart → 0, jamais NaN", () => {
  assert.equal(cpuPercent({ idle: 5, total: 10 }, { idle: 5, total: 10 }), 0);
});

test("ramUsage : 20,1 / 32 Go, 63 %", () => {
  assert.deepEqual(ramUsage(32 * g, 11.9 * g), { used: 20.1, total: 32, percent: 63 });
});

test("normalizeGpu borne l'usage et rejette une température aberrante", () => {
  assert.deepEqual(normalizeGpu({ name: "X", usage: 134.6, dedicated: true, tempC: 810, fanRpm: -1 }), {
    name: "X",
    usage: 100,
    dedicated: true,
  });
  assert.equal(normalizeGpu({ name: "X", usage: 40, dedicated: true, tempC: 0 }).tempC, undefined);
  assert.equal(normalizeGpu({ name: "X", usage: -3, dedicated: true, tempC: 62 }).usage, 0);
  assert.equal(normalizeGpu({ name: "X", usage: 40.4, dedicated: true, tempC: 62 }).tempC, 62);
});

test("normalizeGpu : température gardée seulement entre 10 et 150 °C", () => {
  const temp = (tempC: number) => normalizeGpu({ name: "X", usage: 10, dedicated: true, tempC }).tempC;
  assert.equal(temp(1), undefined);
  assert.equal(temp(9), undefined);
  assert.equal(temp(10), 10);
  assert.equal(temp(150), 150);
  assert.equal(temp(151), undefined);
});

test("FanMemory : 0 absent tant qu'aucune vitesse vue, puis « Arrêté »", () => {
  const fans = new FanMemory();
  const gpu = { name: "RTX", usage: 5, dedicated: true };
  assert.equal(fans.apply({ ...gpu, fanRpm: 0 }).fanRpm, undefined);
  assert.equal(fans.apply({ ...gpu, fanRpm: 1850 }).fanRpm, 1850);
  assert.equal(fans.apply({ ...gpu, fanRpm: 0 }).fanRpm, 0);
});

test("FanMemory : une mémoire par carte", () => {
  const fans = new FanMemory();
  fans.apply({ name: "A", usage: 5, dedicated: true, fanRpm: 1200 });
  assert.equal(fans.apply({ name: "B", usage: 5, dedicated: true, fanRpm: 0 }).fanRpm, undefined);
});

/** Sources factices : les cœurs avancent de 100 unités (25 d'inactivité) à chaque lecture. */
function fakeSources(over: Partial<PerfSources> = {}) {
  const calls = { cpus: 0, memory: 0, gpu: 0 };
  let clock = 1_000_000;
  const sources: PerfSources = {
    cpus: () => {
      calls.cpus++;
      return [core(25 * calls.cpus, 75 * calls.cpus)];
    },
    memory: async () => {
      calls.memory++;
      return { total: 16 * g, available: 8 * g };
    },
    cpuPercent: null,
    gpu: null,
    now: () => clock,
    ...over,
  };
  return { sources, calls, advance: (ms: number) => (clock += ms) };
}

test("getPerf premier appel : pourcentage réel entre 0 et 100", async () => {
  const { sources, calls } = fakeSources();
  const perf = await perfSampler(sources)();
  assert.equal(calls.cpus, 2);
  assert.equal(perf.cpu, 75);
  assert.deepEqual(perf.ram, { used: 8, total: 16, percent: 50 });
  assert.equal(perf.gpu, undefined);
});

test("cache : deux appels à moins d'1 s ne relisent pas les sources", async () => {
  const { sources, calls, advance } = fakeSources();
  const get = perfSampler(sources);
  const first = await get();
  advance(900);
  assert.deepEqual(await get(), first);
  assert.equal(calls.memory, 1);
  advance(200);
  await get();
  assert.equal(calls.memory, 2);
});

test("appels simultanés : une seule mesure", async () => {
  const { sources, calls } = fakeSources();
  const get = perfSampler(sources);
  await Promise.all([get(), get()]);
  assert.equal(calls.memory, 1);
});

test("gpu en échec : cpu et ram présents, pas de gpu", async () => {
  const { sources } = fakeSources({
    gpu: async () => {
      throw new Error("pilote");
    },
  });
  const perf = await perfSampler(sources)();
  assert.equal(perf.cpu, 75);
  assert.equal(perf.ram.total, 16);
  assert.equal(perf.gpu, undefined);
});

test("gpu null : pas de gpu", async () => {
  const { sources } = fakeSources({ gpu: async () => null });
  assert.equal((await perfSampler(sources)()).gpu, undefined);
});

test("gpu : normalisé puis passé par la mémoire des ventilateurs", async () => {
  const raw: GpuPerf = { name: "RTX", usage: 140, dedicated: true, tempC: 55, fanRpm: 0 };
  const { sources } = fakeSources({ gpu: async () => ({ ...raw }) });
  const perf = await perfSampler(sources)();
  assert.deepEqual(perf.gpu, { name: "RTX", usage: 100, dedicated: true, tempC: 55 });
});

test("normalizeGpu retire une température ou une vitesse non finie", () => {
  const gpu = normalizeGpu({ name: "X", usage: 10, dedicated: true, tempC: NaN, fanRpm: Infinity });
  assert.deepEqual(gpu, { name: "X", usage: 10, dedicated: true });
  assert.equal(normalizeGpu({ name: "X", usage: 10, dedicated: true, tempC: Infinity }).tempC, undefined);
  assert.equal(normalizeGpu({ name: "X", usage: 10, dedicated: true, fanRpm: NaN }).fanRpm, undefined);
});

test("gpu à l'usage illisible (NaN) : pas de gpu", async () => {
  const { sources } = fakeSources({ gpu: async () => ({ name: "X", usage: NaN, dedicated: true }) });
  const perf = await perfSampler(sources)();
  assert.equal(perf.gpu, undefined);
  assert.equal(perf.cpu, 75);
});

test("cpu : une lecture ancienne ne sert pas de base, on remesure sur une fenêtre courte", async () => {
  // lectures successives : [0,0] puis [25,75] (75 %) ; longue pause au repos ; puis [6025,75] et [6025,175] (100 %).
  const readings = [core(0, 0), core(25, 75), core(6025, 75), core(6025, 175)];
  let i = 0;
  const { sources, advance } = fakeSources({ cpus: () => [readings[Math.min(i++, readings.length - 1)]!] });
  const get = perfSampler(sources);
  assert.equal((await get()).cpu, 75);
  advance(10 * 60_000);
  // Sans remesure, on comparerait [25,75] à la seule lecture suivante [6025,75] : 0 % (moyenne de la pause, au repos).
  assert.equal((await get()).cpu, 100);
});

test("gpu qui ne répond jamais : cpu et ram arrivent quand même, sans gpu, puis la lecture n'est pas redemandée", async () => {
  let asked = 0;
  const { sources, advance } = fakeSources({
    gpu: () => {
      asked++;
      return new Promise<GpuPerf | null>(() => {});
    },
  });
  const get = perfSampler(sources);
  const started = performance.now();
  const perf = await get();
  const waited = performance.now() - started;
  assert.equal(perf.cpu, 75);
  assert.deepEqual(perf.ram, { used: 8, total: 16, percent: 50 });
  assert.equal(perf.gpu, undefined);
  assert.ok(waited >= GPU_WAIT_MS - 50 && waited < GPU_WAIT_MS + 1000, `attente ${waited} ms`);
  advance(2000);
  assert.equal((await get()).gpu, undefined);
  assert.equal(asked, 1);
});

test("cpu lu par le système : utilisé, arrondi et borné", async () => {
  assert.equal((await perfSampler(fakeSources({ cpuPercent: async () => 66.6 }).sources)()).cpu, 67);
  assert.equal((await perfSampler(fakeSources({ cpuPercent: async () => 134 }).sources)()).cpu, 100);
  assert.equal((await perfSampler(fakeSources({ cpuPercent: async () => -2 }).sources)()).cpu, 0);
});

test("cpu lu par le système : les temps des cœurs restent relevés pour le repli", async () => {
  const { sources, calls } = fakeSources({ cpuPercent: async () => 90 });
  assert.equal((await perfSampler(sources)()).cpu, 90);
  assert.equal(calls.cpus, 2);
});

for (const [name, cpuPercent] of [
  ["null", async () => null],
  ["NaN", async () => NaN],
  ["infini", async () => Infinity],
  [
    "en échec",
    async () => {
      throw new Error("pilote");
    },
  ],
] as [string, () => Promise<number | null>][]) {
  test(`cpu lu par le système ${name} : repli sur les temps des cœurs`, async () => {
    const perf = await perfSampler(fakeSources({ cpuPercent }).sources)();
    assert.equal(perf.cpu, 75);
    assert.deepEqual(perf.ram, { used: 8, total: 16, percent: 50 });
  });
}

test("cpu lu par le système qui ne répond jamais : repli sur les temps des cœurs, puis la lecture n'est pas redemandée", async () => {
  let asked = 0;
  const { sources, advance } = fakeSources({
    cpuPercent: () => {
      asked++;
      return new Promise<number | null>(() => {});
    },
  });
  const get = perfSampler(sources);
  const started = performance.now();
  const perf = await get();
  const waited = performance.now() - started;
  assert.equal(perf.cpu, 75);
  assert.ok(waited >= CPU_WAIT_MS - 50 && waited < CPU_WAIT_MS + 1000, `attente ${waited} ms`);
  advance(2000);
  assert.equal((await get()).cpu, 75);
  assert.equal(asked, 1);
});

test("parseVmStat : libre + inactive + spéculative + purgeable, à la taille de page annoncée", () => {
  const out = [
    "Mach Virtual Memory Statistics: (page size of 16384 bytes)",
    "Pages free:                               12345.",
    "Pages active:                            400000.",
    "Pages inactive:                          200000.",
    "Pages speculative:                         3000.",
    "Pages throttled:                              0.",
    "Pages wired down:                        150000.",
    "Pages purgeable:                           5000.",
    '"Translation faults":                 123456789.',
    "Pages copy-on-write:                    1234567.",
  ].join("\n");
  assert.equal(parseVmStat(out), (12345 + 200000 + 3000 + 5000) * 16384);
});

test("parseVmStat : sans taille de page, 4096 ; ligne absente, 0", () => {
  assert.equal(parseVmStat("Pages free: 10.\nPages inactive: 5."), 15 * 4096);
});
