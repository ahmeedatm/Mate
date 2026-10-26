import type { BrowserTab } from "../screens.ts";
import { osascript, run } from "./run.ts";

/**
 * Page ouverte dans le navigateur du Mac (bouton « Page de l'ordi » de la tablette). Le navigateur choisi est celui
 * dont une fenêtre est la plus en avant à l'écran (CoreGraphics, de l'avant vers l'arrière), sinon le premier
 * navigateur lancé. Safari et les navigateurs Chromium répondent en AppleScript ; Firefox ne donne pas son adresse.
 * La première fois, macOS demande d'autoriser le backend à piloter le navigateur.
 */
/** Navigateurs lancés, du plus en avant à l'écran au plus en arrière (variables BROWSERS et order). */
const BROWSER_ORDER = `
ObjC.import("CoreGraphics");
const BROWSERS = { "Safari": "Safari", "Google Chrome": "Chrome", "Arc": "Arc", "Microsoft Edge": "Edge",
  "Brave Browser": "Brave", "Opera": "Opera", "Vivaldi": "Vivaldi" };
const windows = ObjC.castRefToObject($.CGWindowListCopyWindowInfo($.kCGWindowListOptionOnScreenOnly | $.kCGWindowListExcludeDesktopElements, $.kCGNullWindowID));
const order = [];
for (let i = 0; i < windows.count; i++) {
  const w = windows.objectAtIndex(i);
  if (ObjC.unwrap(w.objectForKey("kCGWindowLayer")) !== 0) continue;
  const owner = ObjC.unwrap(w.objectForKey("kCGWindowOwnerName"));
  if (BROWSERS[owner] && !order.includes(owner)) order.push(owner);
}
for (const name of Object.keys(BROWSERS)) if (!order.includes(name)) order.push(name);
`;

const SCRIPT =
  BROWSER_ORDER +
  `
let page = { found: false };
for (const name of order) {
  const app = Application(name);
  // running() ne lance pas l'appli, contrairement à tout autre appel.
  if (!app.running()) continue;
  try {
    const win = app.windows[0];
    const tab = name === "Safari" ? win.currentTab : win.activeTab;
    const url = tab.url();
    if (!url) continue;
    page = { found: true, url, title: name === "Safari" ? tab.name() : tab.title(), browser: BROWSERS[name] };
    break;
  } catch (e) {}
}
JSON.stringify(page);
`;

export async function browserPage(): Promise<{ found: boolean; url?: string; title?: string; browser?: string }> {
  const page = JSON.parse(await osascript(SCRIPT, [], "JavaScript"));
  if (page.found) return page;
  // Secours (Firefox, autorisation refusée) : lien copié dans le navigateur (⌘L puis ⌘C).
  const copied = (await run("pbpaste", []).catch(() => "")).trim();
  return /^https?:\/\/\S+$/.test(copied) ? { found: true, url: copied, title: "", browser: "clipboard" } : page;
}

/**
 * Onglets ouverts dans les navigateurs (choix de la page sur la tablette), du navigateur le plus en avant au plus en
 * arrière, fenêtre par fenêtre. `active` : onglet affiché dans sa fenêtre. Rien ne bouge à l'écran.
 */
const TABS_SCRIPT =
  BROWSER_ORDER +
  `
const tabs = [];
for (const name of order) {
  let app;
  // Application() échoue si le navigateur n'est pas installé ; running() ne le lance pas.
  try { app = Application(name); if (!app.running()) continue; } catch (e) { continue; }
  const safari = name === "Safari";
  let wins = [];
  try { wins = app.windows(); } catch (e) {}
  wins.forEach((win, w) => {
    try {
      const urls = win.tabs.url();
      const titles = safari ? win.tabs.name() : win.tabs.title();
      const active = safari ? win.currentTab.index() : win.activeTabIndex();
      urls.forEach((url, t) => {
        if (url) tabs.push({ id: name + "|" + w + "|" + t, url, title: titles[t] || "", browser: BROWSERS[name], active: active === t + 1 });
      });
    } catch (e) {}
  });
}
JSON.stringify(tabs);
`;

export async function browserTabs(): Promise<BrowserTab[]> {
  return JSON.parse(await osascript(TABS_SCRIPT, [], "JavaScript"));
}
