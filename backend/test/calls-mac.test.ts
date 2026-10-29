import assert from "node:assert/strict";
import { after, before, beforeEach, describe, it, mock } from "node:test";

// Appels entrants sur Mac : la sonnerie se voit aux boutons de Teams (règle `ring` de config/calls.json).
process.env.FAKE_OS = "mac";
await import("./fake-os-hook.ts");
const { calls, state } = await import("./fake-os.ts");
const { answerCall, callWatchSettled, declineCall, hangUp, startCallWatch } = await import("../src/calls.ts");
type CallEvent = Parameters<Parameters<typeof startCallWatch>[0]>[0];

const TEAMS = "Microsoft Teams";

const events: CallEvent[] = [];
before(async () => {
  mock.timers.enable({ apis: ["setInterval"] });
  await startCallWatch((e) => events.push(e));
});
after(() => mock.timers.reset());

/**
 * Un tour de surveillance, attendu jusqu'au bout : il lit des fichiers (choix des plugins), plus lent qu'un nombre fixe de
 * tours de la boucle d'événements sur une machine chargée (CI) ; ses événements arrivaient alors dans le test suivant.
 */
const tick = async () => {
  mock.timers.tick(2_000);
  await callWatchSettled();
};

describe("appels entrants (Mac)", () => {
  beforeEach(() => {
    events.length = 0;
    calls.length = 0;
    state.windows = {};
    state.buttons = {};
    delete state.accessibility;
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

  it("sans Accessibilité, Teams n'est pas lu : macOS ne demande rien au compagnon", async () => {
    state.accessibility = false;
    state.buttons = { [TEAMS]: ["Accepter avec la vidéo", "Accepter avec l'audio", "Refuser l'appel"] };
    await tick();
    assert.deepEqual(events, []);
  });

  it("les règles Windows ne s'appliquent pas : le titre de la fenêtre ne suffit pas", async () => {
    state.windows = { Discord: [{ title: "Appel entrant - Discord" }] };
    state.buttons = { Discord: ["Rejoindre l'appel vocal"] };
    await tick();
    assert.deepEqual(events, []);
  });

  // Relevé le 28/10 pendant un vrai appel de groupe : sur Mac, le titre de la fenêtre ne change pas.
  it("signale la sonnerie de Discord à ses boutons, appelant lu dans le lien de la conversation", async () => {
    state.windows = { Discord: [{ title: "fuck la solo queue - Discord" }] };
    state.buttons = {
      Discord: [
        "Lien : fuck la solo queue (message de groupe), 3 membres, Appel entrant",
        "Rejoindre l'appel vocal",
        "Rejoindre l'appel vidéo",
        "Se joindre à l'appel",
        "Ignorer",
      ],
    };
    await tick();
    assert.deepEqual(events, [
      { state: "ringing", app: "Discord", caller: null, video: true },
      { state: "ringing", app: "Discord", caller: "fuck la solo queue", video: true },
    ]);
    state.buttons = { Discord: ["Lien : fuck la solo queue (message de groupe), 3 membres, Appel en cours", "Déconnecter"] };
    await tick();
    assert.equal(events.at(-1)?.state, "ended");
  });

  it("Discord : un appel de groupe qui continue sans nous n'est pas une sonnerie", async () => {
    state.buttons = {
      Discord: [
        "Lien : fuck la solo queue (message de groupe), 3\u00a0membres, Appel en cours",
        "Rejoindre l'appel vocal",
        "Rejoindre l'appel vidéo",
        "Se joindre à l'appel",
      ],
    };
    await tick();
    assert.deepEqual(events, []);
  });

  it("Discord : répondre, en vidéo, ignorer, raccrocher", async () => {
    state.buttons = { Discord: ["Rejoindre l'appel vidéo", "Se joindre à l'appel", "Ignorer", "Déconnecter"] };
    await answerCall("Discord");
    await answerCall("Discord", true);
    await declineCall("Discord");
    assert.equal(await hangUp("Discord"), "Discord");
    assert.deepEqual(
      calls.map((c) => c.args[1]),
      ["Se joindre à l'appel", "Rejoindre l'appel vidéo", "Ignorer", "Déconnecter"],
    );
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
