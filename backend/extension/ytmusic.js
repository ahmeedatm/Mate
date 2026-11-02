// Connecteur YouTube Music (script de contenu de music.youtube.com) : lit la page (titre joué, pochette, position,
// « J'aime », file d'attente, résultats de recherche) et la pousse à background.js, qui la relaie au compagnon de
// l'ordinateur ; exécute les actions de la tablette (liste fermée, src/ytmusic.ts côté compagnon). Les sélecteurs
// suivent la page de YouTube Music : si elle change, l'état arrive incomplet, les commandes de base restent.
(() => {
  // Une seule instance vivante : celle d'avant un rechargement de l'extension n'a plus de `chrome.runtime.id`.
  if (window.__mateYtMusic?.()) return;
  window.__mateYtMusic = () => !!chrome.runtime?.id;

  const bar = () => document.querySelector("ytmusic-player-bar");
  const video = () => document.querySelector("video");
  const textOf = (el) => (el?.textContent ?? "").replace(/\s+/g, " ").trim();
  const imageOf = (el) => el?.querySelector("img")?.src || null;
  const number = (n) => (Number.isFinite(n) ? n : 0);

  // Éléments des listes lues au dernier relevé : l'index envoyé par la tablette désigne l'un d'eux.
  let queueEls = [];
  let resultEls = [];

  function track() {
    const b = bar();
    const title = textOf(b?.querySelector(".title"));
    if (!title) return null;
    // « Artiste • Album • 2013 » (vidéo : « Chaîne • 12 M vues • … »).
    const [artist = "", album = ""] = textOf(b.querySelector(".byline")).split(" • ");
    const v = video();
    const status = b.querySelector("ytmusic-like-button-renderer")?.getAttribute("like-status");
    return {
      title,
      artist,
      album,
      cover: b.querySelector("img.image")?.src || imageOf(b.querySelector(".thumbnail")) || null,
      position: number(v?.currentTime),
      duration: number(v?.duration),
      like: status === "LIKE" ? "like" : status === "DISLIKE" ? "dislike" : "none",
    };
  }

  function repeat() {
    const mode = (bar()?.getAttribute("repeat-mode_") ?? bar()?.getAttribute("repeat-mode") ?? "").toUpperCase();
    return mode === "NONE" ? "none" : mode === "ALL" ? "all" : mode === "ONE" ? "one" : null;
  }

  function queue() {
    queueEls = [...document.querySelectorAll("ytmusic-player-queue ytmusic-player-queue-item")];
    return queueEls.map((el) => ({
      title: textOf(el.querySelector(".song-title")),
      subtitle: textOf(el.querySelector(".byline")),
      cover: imageOf(el),
      current: el.hasAttribute("selected") || (el.getAttribute("play-button-state") ?? "default") !== "default",
    }));
  }

  function results() {
    if (location.pathname !== "/search") {
      resultEls = [];
      return { query: null, results: [] };
    }
    // Meilleur résultat d'abord, puis les lignes des rayons (titres, vidéos, albums, artistes…).
    resultEls = [
      ...document.querySelectorAll("ytmusic-card-shelf-renderer"),
      ...document.querySelectorAll("ytmusic-shelf-renderer ytmusic-responsive-list-item-renderer"),
    ].slice(0, 50);
    return {
      query: new URLSearchParams(location.search).get("q"),
      results: resultEls.map((el) => ({
        title: textOf(el.querySelector(".title")),
        subtitle: textOf(el.querySelector(".subtitle, .secondary-flex-columns")),
        cover: imageOf(el),
      })),
    };
  }

  function state() {
    const v = video();
    return { playing: !!v && !v.paused, track: track(), repeat: repeat(), queue: queue(), ...results() };
  }

  // --- Envoi : à chaque changement (relevé chaque seconde, position exclue), et toutes les 5 s pour la position.
  let last = "";
  let sentAt = 0;
  function push(force = false) {
    const s = state();
    const sign = JSON.stringify({ ...s, track: s.track && { ...s.track, position: 0 } });
    if (!force && sign === last && Date.now() - sentAt < 5_000) return;
    last = sign;
    sentAt = Date.now();
    try {
      chrome.runtime.sendMessage({ type: "ytmusic-state", state: s }).catch(() => {});
    } catch {
      // Extension rechargée : ce script n'a plus de destinataire (celui injecté à nouveau prend le relais).
      clearInterval(timer);
    }
  }
  const timer = setInterval(push, 1_000);
  document.addEventListener("play", () => push(true), true);
  document.addEventListener("pause", () => push(true), true);

  // --- Actions de la tablette.
  /** Bouton cliquable dans un élément de YouTube Music (le composant lui-même s'il n'en contient pas). */
  const press = (el) => {
    const target = el?.querySelector("button") ?? el;
    if (!target) return false;
    target.click();
    return true;
  };

  function run({ op, index, query, seconds }) {
    const b = bar();
    const v = video();
    const like = b?.querySelector("ytmusic-like-button-renderer");
    switch (op) {
      case "play-pause":
        if (press(document.querySelector("#play-pause-button"))) return "bouton";
        if (!v) return "pas de lecteur";
        v.paused ? v.play() : v.pause();
        return "lecteur";
      case "next":
        return press(b?.querySelector(".next-button")) ? "ok" : "pas de bouton";
      case "previous":
        return press(b?.querySelector(".previous-button")) ? "ok" : "pas de bouton";
      case "seek":
        if (!v) return "pas de lecteur";
        v.currentTime = seconds ?? 0;
        return "ok";
      case "like":
        return press(like?.querySelector("#button-shape-like, .like")) ? "ok" : "pas de bouton";
      case "dislike":
        return press(like?.querySelector("#button-shape-dislike, .dislike")) ? "ok" : "pas de bouton";
      case "shuffle":
        return press(b?.querySelector(".shuffle")) ? "ok" : "pas de bouton";
      case "repeat":
        return press(b?.querySelector(".repeat")) ? "ok" : "pas de bouton";
      case "play-queue": {
        const el = queueEls[index];
        if (!el) return "titre absent";
        return press(el.querySelector("ytmusic-play-button-renderer") ?? el.querySelector(".song-info") ?? el) ? "ok" : "pas de bouton";
      }
      case "search":
        location.assign("/search?q=" + encodeURIComponent(query ?? ""));
        return "recherche";
      case "play-result": {
        const el = resultEls[index];
        if (!el) return "résultat absent";
        const play = el.querySelector("ytmusic-play-button-renderer");
        if (play) return press(play) ? "ok" : "pas de bouton";
        el.querySelector("a[href*='watch?v='], a.yt-simple-endpoint")?.click();
        return "lien";
      }
      default:
        return "action inconnue";
    }
  }

  chrome.runtime.onMessage.addListener((message, _sender, respond) => {
    if (message?.type !== "ytmusic-action") return;
    let result;
    try {
      result = run(message);
    } catch (e) {
      result = `erreur : ${e?.message ?? e}`;
    }
    respond(result);
    setTimeout(() => push(true), 500);
  });

  push(true);
})();
