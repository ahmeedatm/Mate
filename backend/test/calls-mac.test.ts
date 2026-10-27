import assert from "node:assert/strict";
import { after, before, beforeEach, describe, it, mock } from "node:test";

// Appels entrants sur Mac : la sonnerie se voit aux boutons de Teams (règle `ring` de config/calls.json).
process.env.FAKE_OS = "mac";
await import("./fake-os-hook.ts");
const { calls, state } = await import("./fake-os.ts");
const { answerCall, declineCall, hangUp, startCallWatch } = await import("../src/calls.ts");
type CallEvent = Parameters<Parameters<typeof startCallWatch>[0]>[0];

const TEAMS = "Microsoft Teams";
const flush = async () => {
  for (let i = 0; i < 10; i++) await new Promise((r) => setImmediate(r));
};

const events: CallEvent[] = [];
before(async () => {
  mock.timers.enable({ apis: ["setInterval"] });
  await startCallWatch((e) => events.push(e));
});
after(() => mock.timers.reset());

const tick = async () => {
  mock.timers.tick(2_000);
  await flush();
};

describe("appels entrants (Mac)", () => {
  beforeEach(() => {
    events.length = 0;
    calls.length = 0;
    state.windows = {};
    state.buttons = {};
  });

  it("signale la sonnerie de Teams à ses boutons, puis la fin", async () => {
    state.buttons = {
      [TEAMS]: ["Envoyer", "Texte : Eugénie HARINIAINA vous appelle", "Accepter avec la vidéo", "Accepter avec l'audio", "Refuser l'appel"],
    };
    await tick();
    assert.deepEqual(events, [
      { state: "ringing", app: TEAMS, caller: null, video: true },
      { state: "ringing", app: TEAMS, caller: "Eugénie HARINIAINA", video: true },
    ]);

    state.buttons = { [TEAMS]: ["Envoyer", "Accepter"] };
    await tick();
    assert.deepEqual(events.at(-1), { state: "ended", app: TEAMS, caller: "Eugénie HARINIAINA", video: true });
  });

  it("les règles Windows ne s'appliquent pas", async () => {
    state.windows = { Discord: [{ title: "Appel entrant - Discord" }] };
    state.buttons = { Discord: ["Se joindre à l'appel", "Ignorer"] };
    await tick();
    assert.deepEqual(events, []);
    await assert.rejects(answerCall("Discord"), /Rien à faire pour Discord/);
  });

  it("répondre en audio ou en vidéo, refuser, raccrocher : les vrais boutons de Teams", async () => {
    state.buttons = { [TEAMS]: ["Accepter avec la vidéo", "Accepter avec l'audio", "Refuser l'appel", "Quitter"] };
    await answerCall(TEAMS);
    await answerCall(TEAMS, true);
    await declineCall(TEAMS);
    assert.equal(await hangUp(), TEAMS);
    assert.deepEqual(
      calls.map((c) => c.args[1]),
      ["Accepter avec l'audio", "Accepter avec la vidéo", "Refuser l'appel", "Quitter"],
    );
  });
});
