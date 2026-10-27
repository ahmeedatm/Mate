import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { callStateOf } from "../src/mac/calls.ts";

// Fenêtres de Teams sur Mac telles que lues par l'Accessibilité (relevées pendant de vrais appels).
const chat = { title: "Conversation | MR", text: "", buttons: ["Envoyer", "Démarrer une réunion"] };

describe("état d'un appel Teams (Mac)", () => {
  it("Teams fermé : rien", () => {
    assert.equal(callStateOf(null), null);
  });

  it("écran d'avant-réunion : pas encore en appel, mais lobby", () => {
    const lobby = { title: "Rejoindre la réunion | DSM SQOOL", text: "", buttons: ["Annuler", "Rejoindre maintenant"] };
    const state = callStateOf({ windows: [chat, lobby] });
    assert.equal(state?.connected, false);
    assert.equal(state?.lobby, true);
    assert.equal(callStateOf({ windows: [chat] })?.lobby, false);
  });

  it("en appel : salon, micro coupé, partage", () => {
    const call = {
      title: "Affichage compact de réunion | DSM SQOOL",
      text: "",
      buttons: ["Réduire", "Activer le micro", "Arrêter le partage", "Quitter"],
    };
    const state = callStateOf({ windows: [chat, call] });
    assert.deepEqual(
      { connected: state?.connected, channel: state?.channel, muted: state?.muted, streaming: state?.streaming },
      { connected: true, channel: "DSM SQOOL", muted: true, streaming: true },
    );
  });
});
