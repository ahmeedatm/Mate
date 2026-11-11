import assert from "node:assert/strict";
import { test } from "node:test";
import { parsePsApps } from "../src/mac/system.ts";
import { APP_COUNT, CPU_HOG_MS, appName, appsSampler, groupApps, type AppsSources, type ProcessRaw } from "../src/perf-apps.ts";

const GB = 1024 ** 3;

test("appName : instances Windows, .exe, aides Mac, et processus à écarter", () => {
  assert.equal(appName("chrome#3"), "Google Chrome");
  assert.equal(appName("chrome"), "Google Chrome");
  assert.equal(appName("Code.exe"), "Code");
  assert.equal(appName("Discord#1"), "Discord");
  assert.equal(appName("Google Chrome Helper (Renderer)"), "Google Chrome");
  assert.equal(appName("Slack Helper"), "Slack");
  assert.equal(appName("_Total"), null);
  assert.equal(appName("Idle"), null);
  assert.equal(appName("Memory Compression"), null);
  assert.equal(appName("  "), null);
});

test("groupApps : un seul Chrome pour tous ses processus, processeur rapporté à toute la machine", () => {
  const raw: ProcessRaw[] = [
    { name: "chrome", cpu: 100, memBytes: 1 * GB },
    { name: "chrome#1", cpu: 60, memBytes: 0.5 * GB },
    { name: "Code", cpu: 20, memBytes: 0.25 * GB },
    { name: "_Total", cpu: 800, memBytes: 16 * GB },
  ];
  const apps = groupApps(raw, 4, 16 * GB);
  assert.deepEqual(apps, [
    { name: "Google Chrome", cpu: 40, memMb: 1536, memPercent: 9 },
    { name: "Code", cpu: 5, memMb: 256, memPercent: 2 },
  ]);
});

test("groupApps : valeurs illisibles écartées, processeur borné à 100", () => {
  const apps = groupApps(
    [
      { name: "x", cpu: Number.NaN, memBytes: 10 },
      { name: "y", cpu: -4, memBytes: -1 },
      { name: "z", cpu: 900, memBytes: 0 },
    ],
    2,
    GB,
  );
  assert.deepEqual(
    apps.map((a) => [a.name, a.cpu, a.memMb]),
    [
      ["Z", 100, 0],
      ["X", 0, 0],
      ["Y", 0, 0],
    ],
  );
});

function fakeSources(list: () => ProcessRaw[] | null, clock: { t: number }): AppsSources {
  return { processes: async () => list(), cores: () => 4, totalMem: () => 16 * GB, now: () => clock.t };
}

test("appsSampler : les 5 plus gourmandes, triées par la plus forte de processeur et mémoire", async () => {
  const clock = { t: 0 };
  const raw = Array.from({ length: 8 }, (_, i) => ({ name: `app${i}`, cpu: i * 10, memBytes: (8 - i) * 0.1 * GB }));
  const read = appsSampler(fakeSources(() => raw, clock));
  const { apps } = await read();
  assert.equal(apps?.length, APP_COUNT);
  assert.deepEqual(
    apps?.map((a) => a.name),
    ["App7", "App6", "App5", "App4", "App3"],
  );
});

test("appsSampler : une appli au-dessus de 50 % du processeur n'est signalée qu'après 30 s d'affilée", async () => {
  const clock = { t: 0 };
  let cpu = 300; // 3 cœurs sur 4 : 75 %
  const read = appsSampler(fakeSources(() => [{ name: "chrome", cpu, memBytes: GB }], clock));
  assert.equal((await read()).apps?.[0].warn, undefined);
  for (let t = 2000; t < CPU_HOG_MS; t += 2000) {
    clock.t = t;
    assert.equal((await read()).apps?.[0].warn, undefined, `à ${t} ms`);
  }
  clock.t = CPU_HOG_MS;
  const hog = (await read()).apps?.[0];
  assert.equal(hog?.warn, "cpu");
  assert.equal(hog?.hogSeconds, 30);
  // Redescend une fois : le compte repart de zéro.
  cpu = 40;
  clock.t += 2000;
  assert.equal((await read()).apps?.[0].warn, undefined);
  cpu = 300;
  clock.t += 2000;
  assert.equal((await read()).apps?.[0].warn, undefined);
});

test("appsSampler : un trou de relevés (widget fermé) remet le compte à zéro", async () => {
  const clock = { t: 0 };
  const read = appsSampler(fakeSources(() => [{ name: "chrome", cpu: 300, memBytes: GB }], clock));
  await read();
  clock.t = 60_000;
  assert.equal((await read()).apps?.[0].warn, undefined);
});

test("appsSampler : plus d'un quart de la mémoire signalé tout de suite, appli signalée en tête", async () => {
  const clock = { t: 0 };
  const read = appsSampler(
    fakeSources(
      () => [
        { name: "Code", cpu: 120, memBytes: GB },
        { name: "Teams", cpu: 4, memBytes: 5 * GB },
      ],
      clock,
    ),
  );
  const { apps } = await read();
  assert.equal(apps?.[0].name, "Teams");
  assert.equal(apps?.[0].warn, "ram");
  assert.equal(apps?.[1].warn, undefined);
});

test("appsSampler : liste illisible → null ; erreur du lecteur → null ; mise en cache courte", async () => {
  const clock = { t: 0 };
  let calls = 0;
  const read = appsSampler(
    fakeSources(() => {
      calls++;
      return null;
    }, clock),
  );
  assert.deepEqual(await read(), { apps: null });
  await read();
  assert.equal(calls, 1);
  const failing = appsSampler({
    processes: async () => {
      throw new Error("pilote absent");
    },
    cores: () => 4,
    totalMem: () => GB,
    now: () => 0,
  });
  assert.deepEqual(await failing(), { apps: null });
});

test("parsePsApps : sortie de ps du Mac (% d'un cœur, mémoire en Ko, nom court)", () => {
  const out = ["  12.5 204800 Google Chrome Helper (Renderer)", "   3.0  1024 Finder", "garbage", ""].join("\n");
  assert.deepEqual(parsePsApps(out), [
    { name: "Google Chrome Helper (Renderer)", cpu: 12.5, memBytes: 204800 * 1024 },
    { name: "Finder", cpu: 3, memBytes: 1024 * 1024 },
  ]);
});
