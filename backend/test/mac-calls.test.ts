import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { type AxItem, callStateOf, discordStateOf } from "../src/mac/calls.ts";

// Fenêtres de Teams sur Mac telles que lues par l'Accessibilité (relevées pendant de vrais appels).
const chat = { title: "Conversation | MR", text: "", buttons: ["Envoyer", "Démarrer une réunion"] };

describe("état d'un appel Teams (Mac)", () => {
  it("Teams fermé : rien", () => {
    assert.equal(callStateOf(null), null);
  });

  it("écran d'avant-réunion : pas encore en appel, mais lobby", () => {
    const lobby = { title: "Rejoindre la réunion | DSM TECH", text: "", buttons: ["Annuler", "Rejoindre maintenant"] };
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
      title: "Affichage compact de réunion | DSM TECH",
      text: "",
      buttons: ["Réduire", "Activer le micro", "Arrêter le partage", "Quitter"],
    };
    const state = callStateOf({ windows: [chat, call] });
    assert.deepEqual(
      { connected: state?.connected, channel: state?.channel, muted: state?.muted, streaming: state?.streaming },
      { connected: true, channel: "DSM TECH", muted: true, streaming: true },
    );
  });

  it("caméra : coupée avec « Activer la caméra », allumée avec « Désactiver la caméra », inconnue sans bouton", () => {
    const call = (camera: string[]) => ({
      title: "Affichage compact de réunion | DSM TECH",
      text: "",
      buttons: ["Réduire", ...camera, "Couper le micro", "Quitter"],
    });
    assert.equal(callStateOf({ windows: [chat, call(["Activer la caméra"])] })?.cameraOff, true);
    assert.equal(callStateOf({ windows: [chat, call(["Désactiver la caméra"])] })?.cameraOff, false);
    assert.equal(callStateOf({ windows: [chat, call(["Turn camera on"])] })?.cameraOff, true);
    assert.equal(callStateOf({ windows: [chat, call([])] })?.cameraOff, null);
  });
});

// Éléments de Discord sur Mac dans l'ordre de l'écran, relevés le 28/10 (salon de serveur, appel de groupe privé).
const b = (name: string): AxItem => ({ role: "AXButton", name });
const t = (name: string): AxItem => ({ role: "AXStaticText", name });
const panel = (connected: string | null, muted: boolean, deafened = false): AxItem[] => [
  { role: "AXGroup", name: "Statut et paramètres de l'utilisateur" },
  b(""),
  t("8 ms"),
  ...(connected
    ? [b("Détails de la voix Voix connectée"), t("Voix connectée"), { role: "AXLink", name: connected }, b("Déconnecter")]
    : []),
  b("Gérer le profil et le statut"),
  t("Player"),
  t("player_678"),
  t("Invisible"),
  { role: "AXCheckBox", name: "Rendre muet", on: muted },
  t("Ne plus rendre muet"),
  b("Options d'entrée"),
  { role: "AXCheckBox", name: "Mettre en sourdine", on: deafened },
  b("Paramètres utilisateur"),
];
const discord = (items: AxItem[]) => discordStateOf({ windows: [{ title: "Les Chocobons - Discord", items }] });

describe("état d'un appel Discord (Mac)", () => {
  it("Discord fermé : rien ; pas en appel : panneau reconnu, pas connecté", () => {
    assert.equal(discordStateOf(null), null);
    const idle = discord([b("Boîte de réception"), ...panel(null, false)]);
    assert.deepEqual(
      { found: idle?.found, connected: idle?.connected, muted: idle?.muted },
      { found: true, connected: false, muted: false },
    );
  });

  it("salon de serveur, seul et muet : salon, micro coupé, personne d'autre", () => {
    const state = discord([
      b("Player, Muet"),
      ...panel("ça Raid ou quoi ? / Les Chocobons", true),
      { role: "AXHeading", name: "Salon vocal ça Raid ou quoi ?" },
      b("Ne plus rendre muet"),
      b("Déconnecter"),
      b("Passer en lecteur réduit"),
      b("Plein écran"),
      b("Vignette d'appel, player_678"),
      { role: "AXImage", name: "Player" },
      t("Options"),
      b("Inviter dans le salon vocal"),
      b("Choisis une Activité"),
    ]);
    assert.deepEqual(state, {
      found: true,
      connected: true,
      streaming: false,
      channel: "ça Raid ou quoi ? / Les Chocobons",
      people: [],
      muted: true,
      deafened: false,
    });
  });

  it("appel de groupe privé : un bouton par participant, sans soi-même, jusqu'au salon de discussion", () => {
    const state = discord([
      ...panel("fuck la solo queue", false),
      b("Rendre muet"),
      b("Déconnecter"),
      b("ahmeedatm"),
      { role: "AXImage", name: "  " },
      b("madenheaven."),
      b("player_678"),
      { role: "AXGroup", name: "fuck la solo queue (salon)" },
      b("Player"),
    ]);
    assert.deepEqual(state?.people, ["ahmeedatm", "madenheaven."]);
    assert.equal(state?.channel, "fuck la solo queue");
  });

  it("salon de serveur à plusieurs : nom affiché de chaque vignette ; partage d'écran", () => {
    const state = discord([
      ...panel("Général / Les Chocobons", false, true),
      b("Déconnecter"),
      b("Vignette d'appel, player_678"),
      { role: "AXImage", name: "Player" },
      b("Vignette d'appel, ju_42"),
      { role: "AXImage", name: "Ju'" },
      b("Arrêter de streamer"),
    ]);
    assert.deepEqual(
      { people: state?.people, deafened: state?.deafened, streaming: state?.streaming },
      { people: ["Ju'"], deafened: true, streaming: true },
    );
  });
});
