import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { startMeetingWatch, type MeetingEvent } from "../src/meeting-watch.ts";

const until = async (done: () => boolean, ms = 2000) => {
  const end = Date.now() + ms;
  while (!done()) {
    if (Date.now() > end) throw new Error("délai dépassé");
    await new Promise((resolve) => setTimeout(resolve, 2));
  }
};

describe("réunion en cours : l'ordinateur prévient la tablette", () => {
  it("début puis fin d'un appel, une seule fois chacun", async () => {
    const seq: string[][] = [[], ["Microsoft Teams"], ["Microsoft Teams"], ["Microsoft Teams"], [], []];
    let reads = 0;
    const events: MeetingEvent[] = [];
    const stop = startMeetingWatch((e) => events.push(e), {
      intervalMs: 1,
      connected: () => true,
      liveApps: async () => seq[Math.min(reads++, seq.length - 1)],
    });
    await until(() => reads >= seq.length);
    stop();
    assert.deepEqual(events, [
      { state: "start", app: "Microsoft Teams" },
      { state: "end", app: "Microsoft Teams" },
    ]);
  });

  it("ne lit rien tant qu'aucune tablette n'est connectée", async () => {
    let reads = 0;
    const stop = startMeetingWatch(() => assert.fail("aucun ordre attendu"), {
      intervalMs: 1,
      connected: () => false,
      liveApps: async () => (reads++, ["Discord"]),
    });
    await new Promise((resolve) => setTimeout(resolve, 30));
    stop();
    assert.equal(reads, 0);
  });

  it("une erreur de lecture ne coupe pas la veille", async () => {
    let reads = 0;
    const events: MeetingEvent[] = [];
    const stop = startMeetingWatch((e) => events.push(e), {
      intervalMs: 1,
      connected: () => true,
      liveApps: async () => {
        reads++;
        if (reads === 1) throw new Error("pilotage indisponible");
        return ["Discord"];
      },
    });
    await until(() => events.length > 0);
    stop();
    assert.deepEqual(events, [{ state: "start", app: "Discord" }]);
  });

  it("la veille s'arrête", async () => {
    let reads = 0;
    const stop = startMeetingWatch(() => undefined, { intervalMs: 1, connected: () => true, liveApps: async () => (reads++, []) });
    await until(() => reads > 2);
    stop();
    const seen = reads;
    await new Promise((resolve) => setTimeout(resolve, 20));
    assert.ok(reads <= seen + 1, "plus de lecture après l'arrêt");
  });
});
