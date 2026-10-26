import assert from "node:assert/strict";
import { after, before, beforeEach, describe, it, mock } from "node:test";

// Appels entrants : Windows seulement. Le faux pilotage lit FAKE_OS à son chargement, d'où les imports dynamiques.
process.env.FAKE_OS = "windows";
await import("./fake-os-hook.ts");
const { calls, state } = await import("./fake-os.ts");
const { answerCall, declineCall, hangUp, startCallWatch } = await import("../src/calls.ts");
type CallEvent = Parameters<Parameters<typeof startCallWatch>[0]>[0];

// Règle Discord de config/calls.json.
const RINGING = "Appel entrant - Discord";
const CALLER = "Hyperlink : Camille (message privé), Appel entrant";
const flush = async () => {
  for (let i = 0; i < 10; i++) await new Promise((r) => setImmediate(r));
};

const events: CallEvent[] = [];
before(async () => {
  // Intervalle de surveillance piloté par le test.
  mock.timers.enable({ apis: ["setInterval"] });
  await startCallWatch((e) => events.push(e));
});
after(() => mock.timers.reset());

const tick = async () => {
  mock.timers.tick(2_000);
  await flush();
};

describe("appels entrants", () => {
  beforeEach(() => {
    events.length = 0;
    calls.length = 0;
    state.windows = {};
    state.buttons = {};
  });

  it("signale la sonnerie, puis l'appelant, puis la fin", async () => {
    state.windows = { Discord: [{ title: RINGING }] };
    state.buttons = { Discord: [CALLER, "Se joindre à l'appel", "Ignorer"] };
    await tick();
    assert.deepEqual(events, [
      { state: "ringing", app: "Discord", caller: null, video: true },
      { state: "ringing", app: "Discord", caller: "Camille", video: true },
    ]);
    await tick();
    assert.equal(events.length, 2, "une seule annonce par sonnerie");

    state.windows = { Discord: [{ title: "Discord" }] };
    await tick();
    assert.deepEqual(events.at(-1), { state: "ended", app: "Discord", caller: "Camille", video: true });
  });

  it("répondre appuie sur le bouton de l'appli et coupe la sonnerie quelques secondes", async () => {
    state.buttons = { Discord: ["Join Call", "Ignore"] };
    await answerCall("discord");
    assert.deepEqual(calls, [{ fn: "pressButton", args: ["Discord", "Join Call"] }]);

    // Le titre « Appel entrant » reste un instant après la réponse : pas de nouvelle sonnerie.
    state.windows = { Discord: [{ title: RINGING }] };
    await tick();
    assert.deepEqual(events, []);
  });

  it("répondre en vidéo, ignorer : les boutons propres à chaque geste", async () => {
    state.buttons = { Discord: ["Rejoindre l'appel vidéo", "Ignorer"] };
    await answerCall("Discord", true);
    await declineCall("Discord");
    assert.deepEqual(
      calls.map((c) => c.args[1]),
      ["Rejoindre l'appel vidéo", "Ignorer"],
    );
  });

  it("erreur claire si le bouton n'est plus là, ou pour une appli sans règle", async () => {
    await assert.rejects(declineCall("Discord"), /Bouton introuvable dans Discord/);
    await assert.rejects(answerCall("Teams"), /Rien à faire pour Teams/);
  });

  it("raccroche l'appli qui a un bouton « raccrocher »", async () => {
    await assert.rejects(hangUp(), /Aucun appel en cours/);
    state.buttons = { Discord: ["Déconnecter"] };
    assert.equal(await hangUp(), "Discord");
    assert.equal(await hangUp("DISCORD"), "Discord");
    await assert.rejects(hangUp("Teams"), /Aucun appel en cours/);
  });
});
