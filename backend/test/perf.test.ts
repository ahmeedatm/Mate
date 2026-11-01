import assert from "node:assert/strict";
import type os from "node:os";
import { test } from "node:test";
import { kbps, parseNetstat, parseVmStat } from "../src/mac/system.ts";
import {
  CPU_WAIT_MS,
  FanMemory,
  GPU_WAIT_MS,
  cpuPercent,
  cpuTimes,
  EXTRA_WAIT_MS,
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

const perfOf = (over: Partial<PerfSources>) => perfSampler(fakeSources(over).sources)();

test("cpuMhz : entier ; absent sans source, en NaN, nul, négatif ou en échec", async () => {
  assert.equal((await perfOf({ cpuMhz: async () => 2994.6 })).cpuMhz, 2995);
  assert.equal((await perfOf({})).cpuMhz, undefined);
  for (const value of [NaN, Infinity, 0, -5, null]) {
    assert.equal((await perfOf({ cpuMhz: async () => value })).cpuMhz, undefined, String(value));
  }
  const failing = await perfOf({
    cpuMhz: async () => {
      throw new Error("pilote");
    },
  });
  assert.equal(failing.cpuMhz, undefined);
  assert.equal(failing.cpu, 75);
});

test("disque : Go à une décimale, activité entière bornée 0-100", async () => {
  const disk = (activity?: number | null) => async () => ({ freeBytes: 120.26 * g, totalBytes: 476.94 * g, activity });
  assert.deepEqual((await perfOf({ disk: disk(41.6) })).disk, { freeGb: 120.3, totalGb: 476.9, activity: 42 });
  assert.equal((await perfOf({ disk: disk(240) })).disk?.activity, 100);
  assert.equal((await perfOf({ disk: disk(-3) })).disk?.activity, 0);
  for (const value of [undefined, null, NaN]) {
    assert.deepEqual((await perfOf({ disk: disk(value) })).disk, { freeGb: 120.3, totalGb: 476.9 }, String(value));
  }
  assert.equal((await perfOf({})).disk, undefined);
});

test("disque : tailles illisibles, négatives ou libre > total, ou lecture en échec : pas de disque", async () => {
  for (const [freeBytes, totalBytes] of [
    [NaN, 10 * g],
    [-1, 10 * g],
    [1 * g, 0],
    [1 * g, NaN],
    [20 * g, 10 * g],
  ] as const) {
    assert.equal((await perfOf({ disk: async () => ({ freeBytes, totalBytes }) })).disk, undefined, `${freeBytes}/${totalBytes}`);
  }
  assert.equal((await perfOf({ disk: async () => null })).disk, undefined);
  assert.equal(
    (
      await perfOf({
        disk: async () => {
          throw new Error("statfs");
        },
      })
    ).disk,
    undefined,
  );
});

test("réseau : Ko/s entiers ; absent en NaN, négatif, null ou en échec", async () => {
  assert.deepEqual((await perfOf({ net: async () => ({ upKBps: 12.4, downKBps: 1500.6 }) })).net, { upKBps: 12, downKBps: 1501 });
  assert.deepEqual((await perfOf({ net: async () => ({ upKBps: 0, downKBps: 0 }) })).net, { upKBps: 0, downKBps: 0 });
  assert.equal((await perfOf({})).net, undefined);
  for (const raw of [{ upKBps: NaN, downKBps: 1 }, { upKBps: 1, downKBps: Infinity }, { upKBps: -1, downKBps: 1 }, null]) {
    assert.equal((await perfOf({ net: async () => raw })).net, undefined);
  }
  assert.equal(
    (
      await perfOf({
        net: async () => {
          throw new Error("netstat");
        },
      })
    ).net,
    undefined,
  );
});

test("gpu : mémoire vidéo en Mo entiers ; illisible, négative ou utilisée > totale, elle est omise sans perdre le gpu", async () => {
  const gpu = (extra: Partial<GpuPerf>) => async () => ({ name: "RTX", usage: 10, dedicated: true, ...extra });
  const both = (await perfOf({ gpu: gpu({ vramUsedMb: 3072.4, vramTotalMb: 8191.6 }) })).gpu;
  assert.equal(both?.vramUsedMb, 3072);
  assert.equal(both?.vramTotalMb, 8192);
  const bad = await perfOf({ gpu: gpu({ vramUsedMb: NaN, vramTotalMb: -1 }) });
  assert.deepEqual(bad.gpu, { name: "RTX", usage: 10, dedicated: true });
  const over = (await perfOf({ gpu: gpu({ vramUsedMb: 9000, vramTotalMb: 8192 }) })).gpu;
  assert.equal(over?.vramUsedMb, undefined);
  assert.equal(over?.vramTotalMb, 8192);
  const onlyTotal = (await perfOf({ gpu: gpu({ vramTotalMb: 4096 }) })).gpu;
  assert.equal(onlyTotal?.vramTotalMb, 4096);
});

test("lecture optionnelle (fréquence, disque, réseau) qui ne répond jamais : champs absents, cpu et ram arrivent", async () => {
  const never = () => new Promise<never>(() => {});
  const started = performance.now();
  const perf = await perfOf({ cpuMhz: never, disk: never, net: never });
  const waited = performance.now() - started;
  assert.equal(perf.cpu, 75);
  assert.deepEqual([perf.cpuMhz, perf.disk, perf.net], [undefined, undefined, undefined]);
  assert.ok(waited >= EXTRA_WAIT_MS - 50 && waited < EXTRA_WAIT_MS + 1000, `attente ${waited} ms`);
});

test("parseNetstat : somme des octets des lignes <Link#> des interfaces en*", () => {
  const out = [
    "Name       Mtu   Network       Address            Ipkts Ierrs     Ibytes    Opkts Oerrs     Obytes  Coll",
    "lo0        16384 <Link#1>                          1000     0     500000     1000     0     500000     0",
    "lo0        16384 127           127.0.0.1           1000     -     500000     1000     -     500000     -",
    "en0        1500  <Link#14>     aa:bb:cc:dd:ee:ff  2000     0    1000000     900     0     200000     0",
    "en0        1500  192.168.1     192.168.1.20        2000     -    1000000     900     -     200000     -",
    "en5        1500  <Link#15>                          10     0        300      20     0        400     0",
    "utun3      1380  <Link#16>                           5     0       9999       5     0       9999     0",
  ].join("\n");
  assert.deepEqual(parseNetstat(out), { rx: 1000300, tx: 200400 });
  assert.equal(parseNetstat("rien"), null);
});

test("kbps : octets par seconde en Ko/s ; compteur remis à zéro ou sans écart, absent", () => {
  assert.deepEqual(kbps({ rx: 0, tx: 0, at: 0 }, { rx: 2048000, tx: 10240, at: 2000 }), { upKBps: 5, downKBps: 1000 });
  assert.equal(kbps({ rx: 10, tx: 10, at: 0 }, { rx: 5, tx: 20, at: 1000 }), null);
  assert.equal(kbps({ rx: 0, tx: 0, at: 5 }, { rx: 5, tx: 5, at: 5 }), null);
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

test("cpuTempC : arrondie à l'entier ; absente sans source, hors 10–150 °C, en NaN, nulle ou en échec", async () => {
  assert.equal((await perfOf({ cpuTemp: async () => 64.4 })).cpuTempC, 64);
  assert.equal((await perfOf({ cpuTemp: async () => 150 })).cpuTempC, 150);
  assert.equal((await perfOf({})).cpuTempC, undefined);
  for (const value of [NaN, Infinity, 0, 9, 151, -5, null]) {
    assert.equal((await perfOf({ cpuTemp: async () => value })).cpuTempC, undefined, String(value));
  }
  const failing = await perfOf({
    cpuTemp: async () => {
      throw new Error("lecteur");
    },
  });
  assert.equal(failing.cpuTempC, undefined);
  assert.equal(failing.cpu, 75);
});

test("fps : entier du jeu en cours ; null quand aucun jeu n'est mesuré ; absent sans lecteur, illisible ou en échec", async () => {
  assert.equal((await perfOf({ fps: async () => ({ fps: 143.6 }) })).fps, 144);
  const noGame = await perfOf({ fps: async () => ({ fps: null }) });
  assert.equal(noGame.fps, null);
  assert.ok("fps" in noGame);
  assert.ok(!("fps" in (await perfOf({}))));
  for (const value of [{ fps: 0 }, { fps: -3 }, { fps: NaN }, { fps: Infinity }]) {
    assert.equal((await perfOf({ fps: async () => value })).fps, null, JSON.stringify(value));
  }
  assert.ok(!("fps" in (await perfOf({ fps: async () => null }))));
  const failing = await perfOf({
    fps: async () => {
      throw new Error("lecteur");
    },
  });
  assert.ok(!("fps" in failing));
  assert.equal(failing.cpu, 75);
});

test("cpuWatts, gpuWatts : une décimale ; absents sans source, nuls, négatifs, au-delà de 1000 W, en NaN ou en échec", async () => {
  const perf = await perfOf({ cpuWatts: async () => 42.46, gpuWatts: async () => 180.04 });
  assert.equal(perf.cpuWatts, 42.5);
  assert.equal(perf.gpuWatts, 180);
  const none = await perfOf({});
  assert.ok(!("cpuWatts" in none) && !("gpuWatts" in none));
  for (const value of [NaN, Infinity, 0, -5, 1001, null]) {
    const perfBad = await perfOf({ cpuWatts: async () => value, gpuWatts: async () => value });
    assert.ok(!("cpuWatts" in perfBad) && !("gpuWatts" in perfBad), String(value));
  }
  const failing = await perfOf({
    cpuWatts: async () => {
      throw new Error("lecteur");
    },
  });
  assert.ok(!("cpuWatts" in failing));
  assert.equal(failing.cpu, 75);
});

test("cpuFanRpm : entier ; absent sans source, à 0 (capteur sans ventilateur), négatif, en NaN ou en échec", async () => {
  assert.equal((await perfOf({ cpuFanRpm: async () => 1204.6 })).cpuFanRpm, 1205);
  assert.ok(!("cpuFanRpm" in (await perfOf({}))));
  for (const value of [NaN, Infinity, 0, -5, null]) {
    assert.ok(!("cpuFanRpm" in (await perfOf({ cpuFanRpm: async () => value }))), String(value));
  }
  const failing = await perfOf({
    cpuFanRpm: async () => {
      throw new Error("lecteur");
    },
  });
  assert.ok(!("cpuFanRpm" in failing));
  assert.equal(failing.cpu, 75);
});
