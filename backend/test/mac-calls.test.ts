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

  it("panneau « Partager du contenu » ouvert : toujours en appel, même sans la fenêtre compacte", () => {
    const tray = { title: "Ahmed ATMANE", text: "", buttons: ["Ajouter un arrière-plan", "Écran : Built-in Retina Display"] };
    assert.equal(callStateOf({ windows: [chat, tray] })?.connected, true);
    const compact = { title: "Affichage compact de réunion | Ahmed ATMANE", text: "", buttons: ["Couper le micro", "Partager", "Quitter"] };
    const both = callStateOf({ windows: [chat, tray, compact] });
    assert.deepEqual({ channel: both?.channel, muted: both?.muted }, { channel: "Ahmed ATMANE", muted: false });
  });

  it("partage en cours : plus de « Quitter », toujours en appel, nom et micro lus dans la fenêtre compacte", () => {
    const bar = {
      title: "Barre de contrôle de partage",
      text: "",
      buttons: ["Faire glisser", "Vous partagez votre écran", "Arrêter le partage"],
    };
    const compact = {
      title: "Affichage compact de réunion | Ahmed ATMANE",
      text: "",
      buttons: ["Réduire", "Activer la caméra", "Couper le micro", "Participants", "Arrêter le partage"],
    };
    const state = callStateOf({ windows: [bar, compact, chat] });
    assert.deepEqual(
      { connected: state?.connected, channel: state?.channel, muted: state?.muted, streaming: state?.streaming },
      { connected: true, channel: "Ahmed ATMANE", muted: false, streaming: true },
    );
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

  it("caméra : coupée avec « Activer la caméra », allumée avec « Désactiver la caméra », inconnue sans bouton", () => {
    const call = (camera: string[]) => ({
      title: "Affichage compact de réunion | DSM SQOOL",
      text: "",
      buttons: ["Réduire", ...camera, "Couper le micro", "Quitter"],
    });
    assert.equal(callStateOf({ windows: [chat, call(["Activer la caméra"])] })?.cameraOff, true);
    assert.equal(callStateOf({ windows: [chat, call(["Désactiver la caméra"])] })?.cameraOff, false);
    assert.equal(callStateOf({ windows: [chat, call(["Turn camera on"])] })?.cameraOff, true);
    assert.equal(callStateOf({ windows: [chat, call([])] })?.cameraOff, null);
  });
});
