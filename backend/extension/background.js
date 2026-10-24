// Extension « Compagnon Unowhy » : envoie la liste des onglets de ce navigateur au compagnon de l'ordinateur
// (http://127.0.0.1:3000, jamais ailleurs), pour le bouton Onglets de « Page de l'ordi » sur la tablette.
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
chrome.tabs.onUpdated.addListener((_id, change) => {
  if (change.url !== undefined || change.title !== undefined) schedule();
});
chrome.windows.onRemoved.addListener(schedule);
chrome.windows.onFocusChanged.addListener(async (windowId) => {
  // Le navigateur passé devant le plus récemment est celui qu'on regarde : il passe en tête sur la tablette.
  if (windowId !== chrome.windows.WINDOW_ID_NONE) await chrome.storage.local.set({ focusedAt: Date.now() });
  schedule();
});

chrome.alarms.create("refresh", { periodInMinutes: 1 });
chrome.alarms.onAlarm.addListener(send);
chrome.runtime.onStartup.addListener(send);
chrome.runtime.onInstalled.addListener(send);
