// Extension « Compagnon Unowhy » : envoie la liste des onglets de ce navigateur au compagnon de l'ordinateur
// (http://127.0.0.1:3000, jamais ailleurs), pour le bouton Onglets de « Page de l'ordi » sur la tablette, et
// exécute les ordres de lecture de la page Média (Lecture/Pause, Suivant, Précédent, position) dans l'onglet qui joue.
// À chaque changement d'onglet ou de fenêtre (regroupés sur 300 ms), et toutes les minutes : le compagnon oublie
// un navigateur qui ne donne plus de nouvelles (fermé).

const COMPANION = "http://127.0.0.1:3000/browser/extension";

/** Nom affiché sur la tablette : marque du navigateur (Opera GX, Edge, Brave…), Chrome sinon. */
function browserName() {
  const brands = (navigator.userAgentData?.brands ?? []).map((b) => b.brand);
  const known = ["Opera GX", "Opera", "Microsoft Edge", "Brave", "Vivaldi", "Google Chrome"];
  const name = known.find((k) => brands.includes(k)) ?? (/OPR\//.test(navigator.userAgent) ? "Opera" : "Chrome");
  return name.replace(/^(Microsoft|Google) /, "");
}

async function send() {
  const [tabs, focused, stored] = await Promise.all([
    chrome.tabs.query({}),
    chrome.windows.getLastFocused().catch(() => null),
    chrome.storage.local.get("focusedAt"),
  ]);
  const body = {
    browser: browserName(),
    focusedAt: stored.focusedAt ?? 0,
    focusedWindow: focused?.id ?? null,
    tabs: tabs.map((t) => ({ id: t.id, window: t.windowId, index: t.index, title: t.title ?? "", url: t.url ?? "", active: t.active })),
  };
  await fetch(COMPANION, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(body) }).catch(() => {
    // Compagnon arrêté : nouvel essai au prochain changement ou à la prochaine minute.
  });
}

let timer;
function schedule() {
  clearTimeout(timer);
  timer = setTimeout(send, 300);
}

chrome.tabs.onCreated.addListener(schedule);
chrome.tabs.onRemoved.addListener(schedule);
chrome.tabs.onActivated.addListener(schedule);
chrome.tabs.onMoved.addListener(schedule);
chrome.tabs.onAttached.addListener(schedule);
chrome.tabs.onUpdated.addListener((id, change) => {
  if (change.url !== undefined || change.title !== undefined) schedule();
  // Dernier onglet qui a joué du son : celui à relancer après une pause.
  if (change.audible) chrome.storage.local.set({ lastAudible: id });
});
chrome.windows.onRemoved.addListener(schedule);
chrome.windows.onFocusChanged.addListener(async (windowId) => {
  // Le navigateur passé devant le plus récemment est celui qu'on regarde : il passe en tête sur la tablette.
  if (windowId !== chrome.windows.WINDOW_ID_NONE) await chrome.storage.local.set({ focusedAt: Date.now() });
  schedule();
});

// --- Ordres de lecture : demandés au compagnon, qui garde la demande ouverte jusqu'à ce qu'il y en ait un (20 s au
// plus). L'appel à chrome.storage après chaque réponse garde le service worker éveillé ; s'il est arrêté quand même,
// l'alarme de la minute le relance.

let polling = false;
async function poll() {
  if (polling) return;
  polling = true;
  // Résultat du dernier ordre, envoyé avec la demande suivante (journal du compagnon).
  let report;
  try {
    for (;;) {
      const res = await fetch(COMPANION + "/commands", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ browser: browserName(), report }),
        signal: AbortSignal.timeout(25_000),
      });
      if (!res.ok) break;
      const { commands } = await res.json();
      report = undefined;
      for (const command of commands) report = await runMedia(command).catch((e) => `${command.action} : ${e?.message ?? e}`);
      await chrome.storage.local.get("lastAudible");
    }
  } catch {
    // Compagnon arrêté : nouvel essai à la prochaine minute.
  } finally {
    polling = false;
  }
}

/** Onglet visé : celui qui joue du son, sinon celui du titre lu par l'ordinateur, sinon le dernier qui a joué. */
async function mediaTab(title) {
  const [sounding] = await chrome.tabs.query({ audible: true });
  if (sounding) return sounding.id;
  const tabs = await chrome.tabs.query({});
  const wanted = (title ?? "").toLowerCase();
  const named = wanted && tabs.find((t) => (t.title ?? "").toLowerCase().includes(wanted));
  if (named) return named.id;
  const { lastAudible } = await chrome.storage.local.get("lastAudible");
  return tabs.some((t) => t.id === lastAudible) ? lastAudible : undefined;
}

async function runMedia(command) {
  const tabId = await mediaTab(command.title);
  if (tabId === undefined) return `${command.action} : aucun onglet`;
  const [done] = await chrome.scripting.executeScript({
    target: { tabId },
    world: "MAIN",
    func: controlPage,
    args: [command.action, command.seconds ?? 0],
  });
  return `${command.action} : ${done?.result}`;
}

/** Exécuté dans la page : le lecteur vidéo/audio, et les boutons Suivant/Précédent des sites connus. */
function controlPage(action, seconds) {
  const all = [...document.querySelectorAll("video, audio")];
  const media = all.find((e) => !e.paused) || all.find((e) => e.duration > 0) || all[0];
  const buttons = {
    next: "ytmusic-player-bar .next-button, .ytp-next-button, [data-testid=control-button-skip-forward], [data-testid=next_track_button], .skipControl__next",
    previous:
      "ytmusic-player-bar .previous-button, .ytp-prev-button, [data-testid=control-button-skip-back], [data-testid=previous_track_button], .skipControl__previous",
  };
  if (action === "next" || action === "previous") {
    // Action déclarée par le site (media-session.js), sinon son bouton.
    const name = action === "next" ? "nexttrack" : "previoustrack";
    const handler = navigator.mediaSession?.__unowhy?.[name];
    if (handler) {
      handler({ action: name });
      return "media session";
    }
    const button = document.querySelector(buttons[action]);
    button?.click();
    const seen = navigator.mediaSession?.__unowhy ? Object.keys(navigator.mediaSession.__unowhy).join(",") : "script absent";
    return button ? "bouton" : `pas de bouton (actions : ${seen})`;
  }
  if (!media) return "pas de lecteur";
  if (action === "seek") media.currentTime = seconds;
  else if (media.paused) media.play();
  else media.pause();
  return `${location.host} ${media.tagName} ${media.paused ? "en pause" : "lu"}`;
}

chrome.alarms.create("refresh", { periodInMinutes: 1 });
chrome.alarms.onAlarm.addListener(() => {
  send();
  poll();
});
chrome.runtime.onStartup.addListener(send);
chrome.runtime.onInstalled.addListener(send);
poll();
