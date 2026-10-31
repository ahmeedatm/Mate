import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { startForegroundWatch, type ForegroundEvent } from "../src/foreground-watch.ts";

const until = async (done: () => boolean, ms = 2000) => {
  const end = Date.now() + ms;
  while (!done()) {
    if (Date.now() > end) throw new Error("délai dépassé");
    await new Promise((resolve) => setTimeout(resolve, 2));
  }
};

const front = (name: string): ForegroundEvent => ({ app: name, bundleId: name.toLowerCase() });
const named = (name: string) => ({ name, bundleId: name.toLowerCase() });

describe("appli au premier plan : l'ordinateur prévient la tablette", () => {
  it("un ordre par changement d'appli, pas un par lecture", async () => {
    const seq = ["Finder", "Finder", "Steam", "Steam", "Steam", "Finder"];
    let reads = 0;
    const events: ForegroundEvent[] = [];
    const stop = startForegroundWatch((e) => events.push(e), {
      intervalMs: 1,
      connected: () => true,
      front: async () => named(seq[Math.min(reads++, seq.length - 1)]),
    });
    await until(() => reads >= seq.length);
    stop();
    assert.deepEqual(events, [front("Finder"), front("Steam"), front("Finder")]);
  });

  it("ne lit rien tant qu'aucune tablette n'est connectée", async () => {
    let reads = 0;
    const stop = startForegroundWatch(() => assert.fail("aucun ordre attendu"), {
      intervalMs: 1,
      connected: () => false,
      front: async () => (reads++, named("Steam")),
    });
    await new Promise((resolve) => setTimeout(resolve, 30));
    stop();
    assert.equal(reads, 0);
  });

  it("tablette revenue : l'appli devant lui est redite, même inchangée", async () => {
    let connected = true;
    let reads = 0;
    const events: ForegroundEvent[] = [];
    const stop = startForegroundWatch((e) => events.push(e), {
      intervalMs: 1,
      connected: () => connected,
      front: async () => (reads++, named("Steam")),
    });
    await until(() => events.length === 1);
    connected = false;
    await new Promise((resolve) => setTimeout(resolve, 15));
    connected = true;
    await until(() => events.length === 2);
    stop();
    assert.deepEqual(events, [front("Steam"), front("Steam")]);
  });

  it("une erreur de lecture ne coupe pas la veille et garde l'état", async () => {
    let reads = 0;
    const events: ForegroundEvent[] = [];
    const stop = startForegroundWatch((e) => events.push(e), {
      intervalMs: 1,
      connected: () => true,
      front: async () => {
        reads++;
        if (reads === 2) throw new Error("pilotage indisponible");
        return named(reads < 5 ? "Steam" : "Finder");
      },
    });
    await until(() => reads >= 6);
    stop();
    assert.deepEqual(events, [front("Steam"), front("Finder")]);
  });

  it("garde le nom du fichier de l'appli quand le système le donne (Mac en français)", async () => {
    const events: ForegroundEvent[] = [];
    let reads = 0;
    const stop = startForegroundWatch((e) => events.push(e), {
      intervalMs: 1,
      connected: () => true,
      front: async () => (reads++, { name: "Moniteur d'activité", bundleId: "com.apple.ActivityMonitor", file: "Activity Monitor" }),
    });
    await until(() => reads >= 3);
    stop();
    assert.deepEqual(events, [{ app: "Moniteur d'activité", bundleId: "com.apple.ActivityMonitor", file: "Activity Monitor" }]);
  });
});
