import assert from "node:assert/strict";
import { after, before, describe, it } from "node:test";
import { startServer, type TestServer } from "./server.ts";

let server: TestServer;
before(async () => {
  server = await startServer();
});
after(async () => {
  await server?.stop();
});

describe("GET /system/perf", () => {
  it("renvoie cpu et ram", async () => {
    const { status, json } = await server.call("GET", "/system/perf");
    assert.equal(status, 200);
    assert.equal(typeof json.cpu, "number");
    assert.ok(json.ram.total > 0);
  });

  it("demande le jeton", async () => {
    assert.equal((await server.call("GET", "/system/perf", undefined, false)).status, 401);
  });

  it("garde les champs gpu fournis", async () => {
    const perf = {
      cpu: 40,
      ram: { used: 8, total: 16, percent: 50 },
      gpu: { name: "Carte de test", usage: 70, dedicated: true, tempC: 81, fanRpm: 1850 },
    };
    server.setOs({ perf });
    try {
      const { status, json } = await server.call("GET", "/system/perf");
      assert.equal(status, 200);
      assert.deepEqual(json, perf);
    } finally {
      server.setOs({});
    }
  });

  it("garde les champs facultatifs fournis (fréquence, température du processeur, mémoire vidéo, disque, réseau)", async () => {
    const perf = {
      cpu: 40,
      cpuMhz: 3200,
      cpuTempC: 64,
      cpuWatts: 42.5,
      cpuFanRpm: 1180,
      gpuWatts: 95.2,
      ram: { used: 8, total: 16, percent: 50 },
      gpu: { name: "Carte de test", usage: 70, dedicated: true, vramUsedMb: 2048, vramTotalMb: 8192 },
      disk: { freeGb: 120.5, totalGb: 476.9, activity: 12 },
      net: { upKBps: 30, downKBps: 1200 },
    };
    server.setOs({ perf });
    try {
      const { status, json } = await server.call("GET", "/system/perf");
      assert.equal(status, 200);
      assert.deepEqual(json, perf);
    } finally {
      server.setOs({});
    }
  });

  it("garde fps : le chiffre du jeu, ou null quand aucun jeu n'est mesuré", async () => {
    for (const fps of [144, null]) {
      const perf = { cpu: 40, fps, ram: { used: 8, total: 16, percent: 50 } };
      server.setOs({ perf });
      try {
        const { status, json } = await server.call("GET", "/system/perf");
        assert.equal(status, 200);
        assert.deepEqual(json, perf);
      } finally {
        server.setOs({});
      }
    }
  });

  it("répond 501 hors Mac et Windows", async () => {
    const linux = await startServer({ os: "other" });
    try {
      const { status, json } = await linux.call("GET", "/system/perf");
      assert.equal(status, 501);
      assert.equal(json.error, "Pilotage disponible sur macOS et Windows seulement");
    } finally {
      await linux.stop();
    }
  });
});

describe("GET /system/perf/detail", () => {
  it("renvoie le diagnostic du pilote", async () => {
    const { status, json } = await server.call("GET", "/system/perf/detail");
    assert.equal(status, 200);
    assert.deepEqual(json, { gpu: null });
  });
});
