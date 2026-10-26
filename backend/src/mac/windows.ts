import { run, osascript } from "./run.ts";
import { findAppBundle } from "./apps.ts";
import {
  labelDisplays,
  type AppWindow,
  type Layout,
  type OpenAction,
  type OpenApp,
  type OpenWindows,
  type RawScreen,
  type WindowsInfo,
  type WindowTarget,
} from "../screens.ts";

export type { AppWindow, Display, Layout, WindowsInfo, WindowTarget } from "../screens.ts";

// Un seul script JXA : écrans via NSScreen (AppKit), fenêtres via System Events (Accessibilité).
// Les cadres NSScreen partent du coin bas-gauche de l'écran principal, ceux de l'Accessibilité
// du coin haut-gauche : conversion dans screens().
// L'Accessibilité ne voit que les fenêtres des bureaux affichés : celles des autres bureaux (dont le
// bureau caché derrière une appli plein écran) viennent du menu Fenêtre de l'appli, que macOS tient
// à jour, et choisir une de ces entrées fait passer sur son bureau. Leur écran vient du serveur de
// fenêtres (CoreGraphics), qui connaît les cadres de toutes les fenêtres mais pas leurs titres.
const SCRIPT = `ObjC.import('AppKit');
ObjC.import('CoreGraphics');

function screens() {
  const all = $.NSScreen.screens.js;
  const mainH = all[0].frame.size.height;
  const ax = (r) => ({ x: r.origin.x, y: mainH - (r.origin.y + r.size.height), w: r.size.width, h: r.size.height });
  return all.map((s, i) => {
    const id = ObjC.unwrap(s.deviceDescription.objectForKey('NSScreenNumber'));
    return { id: id, name: s.localizedName.js, builtin: !!$.CGDisplayIsBuiltin(id), main: i === 0, frame: ax(s.frame), visible: ax(s.visibleFrame) };
  });
}

function screenOf(all, pos, size) {
  const cx = pos[0] + size[0] / 2, cy = pos[1] + size[1] / 2;
  return all.find((s) => cx >= s.frame.x && cx < s.frame.x + s.frame.w && cy >= s.frame.y && cy < s.frame.y + s.frame.h) || null;
}

function attr(w, name) {
  try { return w.attributes.byName(name).value(); } catch (e) { return null; }
}

function windowsOf(proc) {
  const all = proc.windows();
  // Les fenêtres utiles sont "standard" ; les autres (barres d'outils, bulles) sont ignorées.
  const standard = all.filter((w) => { try { return w.subrole() === 'AXStandardWindow'; } catch (e) { return false; } });
  return (standard.length ? standard : all).map((w) => ({ w: w, index: all.indexOf(w) + 1 }));
}

// Les titres changent (VS Code y met le fichier ouvert) : la fin du titre (dossier, appli) départage.
function suffix(title) {
  return String(title || '').split(' — ').pop();
}

// Le menu Fenêtre peut donner un titre plus court que l'Accessibilité (Chrome : « Page » contre
// « Page - Google Chrome – Profil ») ou coupé par « … ».
function sameTitle(ax, menu) {
  ax = String(ax || ''); menu = String(menu || '');
  if (ax === menu) return true;
  if (!menu) return false;
  if (ax.startsWith(menu + ' ')) return true;
  return menu.endsWith('…') && menu.length > 1 && ax.startsWith(menu.slice(0, -1));
}

function find(list, target) {
  let named = list.filter((e) => e.w.name() === target.title);
  if (!named.length) named = list.filter((e) => sameTitle(e.w.name(), target.title));
  return named.find((e) => e.index === target.index) || named[0] ||
    list.find((e) => e.index === target.index && target.index > 0) ||
    list.find((e) => suffix(e.w.name()) === suffix(target.title));
}

// Commandes du menu Fenêtre sans raccourci (pas des fenêtres) : macOS en ajoute selon les versions.
const MENU_COMMANDS = [
  'Bring All to Front', 'Tout ramener au premier plan', 'Tout amener à l’avant', 'Arrange in Front', 'Organiser au premier plan',
  'Remove Window from Set', 'Supprimer la fenêtre du groupe', 'Close All Windows', 'Fermer toutes les fenêtres',
];

/** Dernière section du menu Fenêtre (Window) : les titres des fenêtres ouvertes, tous bureaux confondus. */
function menuWindows(proc, axTitles) {
  try {
    const bar = proc.menuBars[0];
    const names = bar.menuBarItems.name();
    const i = names.findIndex((n) => n === 'Window' || n === 'Fenêtre');
    if (i < 0) return [];
    const items = bar.menuBarItems[i].menus[0].menuItems;
    const titles = items.name();
    const start = titles.lastIndexOf(null) + 1;
    const section = [];
    for (let k = start; k < titles.length; k++) {
      if (!titles[k] || MENU_COMMANDS.includes(titles[k])) continue;
      let cmd = null;
      try { cmd = items[k].attributes.byName('AXMenuItemCmdChar').value(); } catch (e) {}
      if (!cmd) section.push({ title: titles[k], item: items[k] });
    }
    // Pas une liste de fenêtres si aucune des fenêtres visibles n'y figure.
    if (axTitles.length && !section.some((e) => axTitles.some((t) => sameTitle(t, e.title)))) return [];
    return section;
  } catch (e) {
    return [];
  }
}

/**
 * Fenêtres de l'appli pour le serveur de fenêtres (tous bureaux) : cadres sans titre (le titre
 * demande l'autorisation Enregistrement de l'écran), null si la liste est illisible.
 */
function cgWindows(proc) {
  const byPid = cgAll();
  return byPid && (byPid[proc.unixId()] || []);
}

/** Même chose pour toutes les applis en un seul appel (vue « Ouvertes ») : { pid: fenêtres }. */
function cgAll() {
  try {
    const out = {};
    ObjC.castRefToObject($.CGWindowListCopyWindowInfo($.kCGWindowListOptionAll | $.kCGWindowListExcludeDesktopElements, 0)).js.forEach((d) => {
      const o = ObjC.deepUnwrap(d);
      const b = o.kCGWindowBounds;
      // Couche 0 et assez grande : pas les barres d'outils ni les fenêtres techniques.
      if (o.kCGWindowLayer !== 0 || b.Width < 200 || b.Height < 200) return;
      (out[o.kCGWindowOwnerPID] = out[o.kCGWindowOwnerPID] || []).push({ pos: [b.X, b.Y], size: [b.Width, b.Height], name: o.kCGWindowName || null });
    });
    return out;
  } catch (e) {
    return null;
  }
}

function sameFrame(a, pos, size) {
  return Math.abs(a.pos[0] - pos[0]) < 4 && Math.abs(a.pos[1] - pos[1]) < 4 && Math.abs(a.size[0] - size[0]) < 4 && Math.abs(a.size[1] - size[1]) < 4;
}

/**
 * Fenêtres des autres bureaux (derrière une fenêtre plein écran, Spaces) : entrées du menu absentes
 * de l'Accessibilité, avec leur écran quand le serveur de fenêtres permet de le savoir.
 */
function elsewhereWindows(proc, list, all, cg = cgWindows(proc)) {
  const left = list.map((e) => e.w.name() || '');
  let away = menuWindows(proc, left).filter((e) => {
    const at = left.findIndex((t) => sameTitle(t, e.title));
    if (at < 0) return true;
    left.splice(at, 1);
    return false;
  });
  if (!cg) return away.map((e) => ({ title: e.title, item: e.item, display: null }));
  const spare = cg.filter((c) => !list.some((e) => sameFrame(c, e.w.position(), e.w.size())));
  // Pas plus d'entrées que de fenêtres réelles cachées : le reste du menu, ce sont des commandes.
  away = away.slice(Math.max(0, away.length - spare.length));
  const screensOf = spare.map((c) => (screenOf(all, c.pos, c.size) || {}).id);
  const oneScreen = screensOf.length > 0 && screensOf.every((id) => id !== undefined && id === screensOf[0]);
  return away.map((e) => {
    const named = spare.find((c) => c.name === e.title);
    const s = named ? screenOf(all, named.pos, named.size) : null;
    return { title: e.title, item: e.item, display: s ? s.id : oneScreen ? screensOf[0] : null };
  });
}

/** Passe sur le bureau d'une fenêtre par le menu Fenêtre, puis la retrouve par l'Accessibilité. */
function reach(proc, all, title) {
  const entries = elsewhereWindows(proc, windowsOf(proc), all);
  const entry = entries.find((e) => e.title === title) ||
    (entries.length === 1 ? entries[0] : entries.find((e) => suffix(e.title) === suffix(title)));
  if (!entry) return undefined;
  entry.item.click();
  proc.frontmost = true;
  // Le passage d'un bureau à l'autre est animé : la fenêtre n'apparaît qu'au bout d'une à deux secondes.
  for (let i = 0; i < 25; i++) {
    delay(0.15);
    const found = windowsOf(proc).find((e) => sameTitle(e.w.name(), entry.title));
    if (found) return found;
  }
  return undefined;
}

/** Sortie du plein écran : animée, la fenêtre revient sur son bureau d'origine ; on attend qu'elle ne bouge plus. */
function leaveFullscreen(w) {
  w.attributes.byName('AXFullScreen').value = false;
  let last = '';
  for (let i = 0; i < 40; i++) {
    delay(0.1);
    try {
      if (attr(w, 'AXFullScreen')) continue;
      const frame = JSON.stringify([w.position(), w.size()]);
      if (frame === last) return;
      last = frame;
    } catch (e) {}
  }
}

/** Disposition actuelle d'une fenêtre sur son écran (à quelques points près) : la garder en changeant d'écran. */
function layoutOf(pos, size, v) {
  const near = (a, b) => Math.abs(a - b) <= 16;
  if (!near(pos[1], v.y) || !near(size[1], v.h)) return 'keep';
  if (near(pos[0], v.x) && near(size[0], v.w)) return 'max';
  if (near(pos[0], v.x) && near(size[0], v.w / 2)) return 'left';
  if (near(pos[0] + size[0], v.x + v.w) && near(size[0], v.w / 2)) return 'right';
  return 'keep';
}

function place(w, all, displayId, layout) {
  if (attr(w, 'AXMinimized')) w.attributes.byName('AXMinimized').value = false;
  // Plein écran déplacé sans disposition choisie : il reste aussi grand que possible.
  if (attr(w, 'AXFullScreen')) { leaveFullscreen(w); if (layout === 'keep') layout = 'max'; }
  const pos = w.position(), size = w.size();
  const from = screenOf(all, pos, size) || all[0];
  const to = all.find((s) => s.id === displayId) || from;
  if (layout === 'keep' && to !== from) layout = layoutOf(pos, size, from.visible);
  const v = to.visible;
  let x, y, width, height;
  if (layout === 'max') { x = v.x; y = v.y; width = v.w; height = v.h; }
  else if (layout === 'left') { x = v.x; y = v.y; width = Math.round(v.w / 2); height = v.h; }
  else if (layout === 'right') { width = Math.round(v.w / 2); x = v.x + v.w - width; y = v.y; height = v.h; }
  else {
    const f = from.visible;
    width = Math.min(size[0], v.w); height = Math.min(size[1], v.h);
    x = v.x + ((pos[0] - f.x) / f.w) * v.w; y = v.y + ((pos[1] - f.y) / f.h) * v.h;
    x = Math.max(v.x, Math.min(x, v.x + v.w - width)); y = Math.max(v.y, Math.min(y, v.y + v.h - height));
  }
  // Position, taille puis position : certaines applis refusent une taille qui déborde de l'écran d'origine.
  w.position = [Math.round(x), Math.round(y)];
  w.size = [Math.round(width), Math.round(height)];
  w.position = [Math.round(x), Math.round(y)];
}

function focus(proc, w) {
  if (attr(w, 'AXMinimized')) w.attributes.byName('AXMinimized').value = false;
  proc.frontmost = true;
  w.actions.byName('AXRaise').perform();
}

// frontProc : l'appli est au premier plan (sa fenêtre principale est alors celle de devant).
function describe(all, e, frontProc) {
  const pos = e.w.position(), size = e.w.size();
  const s = screenOf(all, pos, size);
  const fullscreen = !!attr(e.w, 'AXFullScreen');
  const minimized = !!attr(e.w, 'AXMinimized');
  return {
    index: e.index, title: e.w.name() || '', minimized: minimized, fullscreen: fullscreen, display: s ? s.id : null, elsewhere: false,
    front: frontProc && !minimized && !!attr(e.w, 'AXMain'), maximized: fullscreen || (!!s && layoutOf(pos, size, s.visible) === 'max'),
  };
}

// Agrandie (remplit l'écran) : ramenée aux deux tiers de l'écran, centrée (macOS ne garde pas la taille d'avant) ; sinon agrandie.
/** Comme un clic sur le bouton rouge : l'appli peut demander d'enregistrer. */
function closeWindow(w) {
  const close = w.buttons.whose({ subrole: 'AXCloseButton' })();
  if (!close.length) throw new Error("Cette fenêtre ne peut pas être fermée d’ici");
  close[0].click();
  delay(0.3);
}

function zoom(w, all) {
  if (attr(w, 'AXMinimized')) w.attributes.byName('AXMinimized').value = false;
  if (attr(w, 'AXFullScreen')) { leaveFullscreen(w); return; }
  const pos = w.position(), size = w.size();
  const s = screenOf(all, pos, size) || all[0];
  const v = s.visible;
  if (layoutOf(pos, size, v) !== 'max') { place(w, all, s.id, 'max'); return; }
  const width = Math.round(v.w * 2 / 3), height = Math.round(v.h * 2 / 3);
  const x = Math.round(v.x + (v.w - width) / 2), y = Math.round(v.y + (v.h - height) / 2);
  w.position = [x, y];
  w.size = [width, height];
  w.position = [x, y];
}

/** Plein écran macOS (bouton vert) : la fenêtre passe sur son propre bureau ; en sortir la ramène. */
function toggleFullscreen(proc, w) {
  if (attr(w, 'AXFullScreen')) { leaveFullscreen(w); return; }
  if (attr(w, 'AXMinimized')) w.attributes.byName('AXMinimized').value = false;
  focus(proc, w);
  w.attributes.byName('AXFullScreen').value = true;
}

function displaysOf(all) {
  return all.map((s) => ({ id: s.id, name: s.name, builtin: s.builtin, main: s.main, frame: s.frame }));
}

// Vue « Ouvertes » : toutes les applis qui ont une fenêtre, y compris sur les autres bureaux (plein
// écran…). Le menu Fenêtre est lent à lire : seulement pour les applis dont le serveur de fenêtres
// connaît une fenêtre que l'Accessibilité ne voit pas.
function allApps(se, all) {
  const apps = [];
  const cg = cgAll();
  se.applicationProcesses.whose({ backgroundOnly: false })().forEach((p) => {
    let list, away = [];
    try { list = windowsOf(p); } catch (e) { return; }
    try {
      const mine = cg ? cg[p.unixId()] || [] : [];
      const frames = list.map((e) => ({ pos: e.w.position(), size: e.w.size() }));
      if (mine.some((c) => !frames.some((f) => sameFrame(c, f.pos, f.size)))) away = elsewhereWindows(p, list, all, mine);
    } catch (e) {}
    if (!list.length && !away.length) return;
    // Nom de l'appli (Visual Studio Code), pas celui du processus (Code) : il sert à retrouver son icône.
    let name;
    try { name = String(p.applicationFile().name()).replace(/[.]app$/, ''); } catch (e) { name = p.name(); }
    // Sans identifiant de bundle (émulateur…) : désignée par son processus.
    const front = p.frontmost();
    apps.push({ key: p.bundleIdentifier() || 'pid:' + p.unixId(), name: name, front: front, windows: list.map((e) => describe(all, e, front)).concat(away.map((e) => ({
      index: 0, title: e.title, minimized: false, fullscreen: false, display: e.display, elsewhere: true, front: false, maximized: false,
    }))) });
  });
  return apps;
}

function run(argv) {
  const req = JSON.parse(argv[0]);
  const all = screens();
  const se = Application('System Events');
  if (req.op === 'all') return JSON.stringify({ apps: allApps(se, all), displays: displaysOf(all) });
  const procs = req.pid ? se.applicationProcesses.whose({ unixId: req.pid })() : se.applicationProcesses.whose({ bundleIdentifier: req.bundleId })();
  const proc = procs[0];

  if (proc && req.op !== 'list') {
    const list = windowsOf(proc);
    let targets = req.window ? [find(list, req.window)].filter(Boolean) : list;
    if (req.window && !targets.length) targets = [reach(proc, all, req.window.title)].filter(Boolean);
    if (req.window && !targets.length) throw new Error('Fenêtre introuvable');
    if (req.op === 'place') targets.forEach((e) => place(e.w, all, req.display, req.layout));
    if (req.op === 'zoom' && targets.length) zoom(targets[0].w, all);
    if (req.op === 'minimize') targets.forEach((e) => { e.w.attributes.byName('AXMinimized').value = true; });
    else if (req.op === 'close') targets.forEach((e) => closeWindow(e.w));
    else if (req.op === 'fullscreen') { if (targets.length) toggleFullscreen(proc, targets[0].w); }
    else if (targets.length) {
      const title = targets[0].w.name();
      focus(proc, targets[0].w);
      // Écran occupé par une appli plein écran : macOS range la fenêtre sur le bureau caché derrière,
      // où l'Accessibilité la perd. On passe sur ce bureau pour qu'elle reste visible.
      if (req.op === 'place') delay(0.3);
      if (!windowsOf(proc).some((e) => e.w.name() === title)) reach(proc, all, title);
    }
  }

  const list = proc ? windowsOf(proc) : [];
  const windows = list.map((e) => describe(all, e, !!proc && proc.frontmost()));
  if (proc) elsewhereWindows(proc, list, all).forEach((e) => {
    windows.push({ index: 0, title: e.title, minimized: false, fullscreen: false, display: e.display, elsewhere: true });
  });
  return JSON.stringify({
    running: !!proc,
    windows: windows,
    displays: displaysOf(all),
  });
}`;

const bundleIds = new Map<string, string>();

async function bundleId(app: string): Promise<string> {
  const cached = bundleIds.get(app);
  if (cached) return cached;
  const bundle = await findAppBundle(app);
  if (!bundle) throw new Error(`Appli introuvable : ${app}`);
  const id = await run("plutil", ["-extract", "CFBundleIdentifier", "raw", "-o", "-", `${bundle}/Contents/Info.plist`]);
  bundleIds.set(app, id);
  return id;
}

/** `id` : identifiant de l'appli déjà connu (vue « Ouvertes »), sinon tiré du nom. */
async function call(app: string, req: Record<string, unknown>, id?: string): Promise<WindowsInfo> {
  id ??= app ? await bundleId(app) : "";
  const pid = id.startsWith("pid:") ? Number(id.slice(4)) : undefined;
  const raw = await osascript(SCRIPT, [JSON.stringify({ ...req, ...(pid ? { pid } : { bundleId: id }) })], "JavaScript");
  const parsed = JSON.parse(raw) as { running: boolean; windows: AppWindow[]; displays: RawScreen[] };
  return { app, running: parsed.running, windows: parsed.windows, displays: labelDisplays(parsed.displays) };
}

/** Écrans branchés (sans Accessibilité : aucune appli interrogée). */
export async function listDisplays() {
  return (await call("", { op: "list" })).displays;
}

export function listWindows(app: string) {
  return call(app, { op: "list" });
}

/** Met une fenêtre (ou toutes, sans `window`) au premier plan. */
export function focusWindow(app: string, window?: WindowTarget) {
  return call(app, { op: "focus", window });
}

/** Déplace une fenêtre (ou toutes) vers un écran et/ou une moitié d'écran, puis la met au premier plan. */
export function placeWindow(app: string, opts: { window?: WindowTarget; display?: number; layout: Layout }) {
  return call(app, { op: "place", ...opts });
}

/** Toutes les applis qui ont une fenêtre affichée (vue « Ouvertes »), la plus en avant d'abord ; `key` : identifiant de bundle. */
export async function listOpenWindows(): Promise<OpenWindows> {
  const raw = await osascript(SCRIPT, [JSON.stringify({ op: "all" })], "JavaScript");
  const parsed = JSON.parse(raw) as { apps: (OpenApp & { front: boolean })[]; displays: RawScreen[] };
  const apps = parsed.apps.sort((a, b) => Number(b.front) - Number(a.front)).map(({ front: _, ...app }) => app);
  return { apps, displays: labelDisplays(parsed.displays) };
}

/** Pilote une fenêtre de la vue « Ouvertes », désignée par l'identifiant de son appli. */
export async function controlOpenWindow(
  key: string,
  window: WindowTarget,
  action: OpenAction,
  opts?: { display?: number; layout: Layout },
) {
  if (!key) throw new Error("Fenêtre introuvable");
  await call("", { op: action, window, ...opts }, key);
}
