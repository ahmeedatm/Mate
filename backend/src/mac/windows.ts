import { run, osascript } from "./run.ts";
import { findAppBundle } from "./apps.ts";
import {
  labelDisplays,
  type AppWindow,
  type Layout,
  type OpenAction,
  type OpenApp,
  type OpenWindows,
  type Page,
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

// \`strict\` (fermeture) : seulement une fenêtre de ce titre ; sans lui, le rang ou la fin du titre pouvaient désigner une
// autre fenêtre (onglet de Chrome qui a changé de titre entre la lecture et l'action) et la fermer à sa place.
function find(list, target, strict) {
  let named = list.filter((e) => e.w.name() === target.title);
  if (!named.length) named = list.filter((e) => sameTitle(e.w.name(), target.title));
  const same = named.find((e) => e.index === target.index) || named[0];
  if (same || strict) return same;
  return list.find((e) => e.index === target.index && target.index > 0) ||
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
      // Couche 0 et assez grande : pas les barres d'outils ni les fenêtres techniques. Une appli qui n'a que de
      // petites fenêtres garde une liste vide : sans entrée du tout, elle n'a aucune fenêtre (réduites comprises).
      if (o.kCGWindowLayer !== 0) return;
      out[o.kCGWindowOwnerPID] = out[o.kCGWindowOwnerPID] || [];
      if (b.Width < 200 || b.Height < 200) return;
      out[o.kCGWindowOwnerPID].push({ pos: [b.X, b.Y], size: [b.Width, b.Height], name: o.kCGWindowName || null, wid: o.kCGWindowNumber });
    });
    return out;
  } catch (e) {
    return null;
  }
}

function sameFrame(a, pos, size) {
  return Math.abs(a.pos[0] - pos[0]) < 4 && Math.abs(a.pos[1] - pos[1]) < 4 && Math.abs(a.size[0] - size[0]) < 4 && Math.abs(a.size[1] - size[1]) < 4;
}
// Fenêtres du serveur de fenêtres qu'aucune fenêtre de l'Accessibilité ne couvre. Chacune n'en couvre qu'une, la plus
// proche : une fenêtre agrandie et une fenêtre plein écran de la même appli ont presque le même cadre (à un point près
// sous l'encoche d'un MacBook), sinon la plein écran passait pour visible et n'était pas cherchée dans le menu.
function unmatched(cg, frames) {
  const left = cg.slice();
  frames.forEach((f) => {
    let best = -1, gap = Infinity;
    left.forEach((c, i) => {
      if (!sameFrame(c, f.pos, f.size)) return;
      const d = Math.abs(c.pos[0] - f.pos[0]) + Math.abs(c.pos[1] - f.pos[1]) + Math.abs(c.size[0] - f.size[0]) + Math.abs(c.size[1] - f.size[1]);
      if (d < gap) { gap = d; best = i; }
    });
    if (best >= 0) left.splice(best, 1);
  });
  return left;
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
  const spare = unmatched(cg, list.map((e) => ({ pos: e.w.position(), size: e.w.size() })));
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
function reach(proc, all, title, strict) {
  const entries = elsewhereWindows(proc, windowsOf(proc), all);
  const entry = entries.find((e) => e.title === title) ||
    entries.find((e) => sameTitle(title, e.title)) ||
    (strict ? undefined : entries.length === 1 ? entries[0] : entries.find((e) => suffix(e.title) === suffix(title)));
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

// Fermer la dernière fenêtre d'une appli la quitte, comme sous Windows : sur Mac l'appli restait lancée (point dans le
// Dock), et Discord, qui se masque sans fermer sa fenêtre, « se rouvrait » dans la liste. Quittée comme par Cmd-Q (une
// appli qui a quelque chose à enregistrer le demande encore) seulement s'il ne lui reste aucune fenêtre, ni ici ni sur
// un autre bureau (menu Fenêtre). Une fenêtre fermée encore là dans une appli visible attend une réponse (enregistrer ?) :
// rien n'est quitté. Le Finder n'est jamais quitté.
function quitIfLastWindow(proc, titles) {
  if (proc.bundleIdentifier() === 'com.apple.finder') return false;
  delay(0.3);
  try {
    const list = windowsOf(proc);
    const names = list.map((e) => e.w.name() || '');
    if (names.some((n) => titles.includes(n)) && proc.visible()) return false;
    const others = names.filter((n) => !titles.includes(n));
    const away = menuWindows(proc, names).filter((e) => !titles.some((t) => sameTitle(t, e.title)) && !others.some((t) => sameTitle(t, e.title)));
    if (others.length || away.length) return false;
    return !!$.NSRunningApplication.runningApplicationWithProcessIdentifier(proc.unixId()).terminate;
  } catch (e) {
    return false;
  }
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
// connaît une fenêtre que l'Accessibilité ne voit pas. Chaque question à System Events coûte un aller-retour
// vers l'appli : nom, bundle et premier plan viennent de NSWorkspace, et une appli sans aucune fenêtre au
// serveur de fenêtres n'est pas interrogée. skip : applis muettes (voir slow), sautées un moment.
function allApps(se, all, cg, skip, slow) {
  const ws = $.NSWorkspace.sharedWorkspace;
  const frontPid = ws.frontmostApplication.processIdentifier;
  const info = {};
  ws.runningApplications.js.forEach((a) => {
    // Nom de l'appli (Visual Studio Code), pas celui du processus (Code) : il sert à retrouver son icône.
    const file = ObjC.unwrap(a.bundleURL.lastPathComponent);
    info[a.processIdentifier] = { name: file ? file.replace(/[.]app$/, '') : ObjC.unwrap(a.localizedName), bundle: ObjC.unwrap(a.bundleIdentifier) };
  });
  const apps = [];
  let failed = null;
  const query = se.applicationProcesses.whose({ backgroundOnly: false });
  const pids = query.unixId();
  query().forEach((p, i) => {
    const pid = pids[i];
    if ((cg && !cg[pid]) || skip.includes(pid)) return;
    // Fenêtre ou appli disparue pendant la lecture (plein écran qui s'anime, appli qui quitte : -1728, -1719) :
    // appli sautée pour cette fois, la liste suivante la reprend.
    try {
    let list, away = [];
    const t = Date.now();
    try { list = windowsOf(p); } catch (e) { return; }
    // Aucune fenêtre au bout d'une seconde (appli iOS sur Mac…) : chaque question attend la fin du délai de
    // l'Accessibilité, le menu Fenêtre compris. Signalée pour être sautée aux lectures suivantes.
    if (!list.length && Date.now() - t > 1000) return slow.push(pid);
    try {
      const mine = cg ? cg[pid] : [];
      const frames = list.map((e) => ({ pos: e.w.position(), size: e.w.size() }));
      if (unmatched(mine, frames).length) away = elsewhereWindows(p, list, all, mine);
    } catch (e) {}
    if (!list.length && !away.length) return;
    const meta = info[pid] || { name: p.name() };
    // Sans identifiant de bundle (émulateur…) : désignée par son processus.
    const front = pid === frontPid;
    apps.push({ key: meta.bundle || 'pid:' + pid, name: meta.name, front: front, pid: pid, windows: list.map((e) => describe(all, e, front)).concat(away.map((e) => ({
      index: 0, title: e.title, minimized: false, fullscreen: false, display: e.display, elsewhere: true, front: false, maximized: false,
    }))) });
    } catch (e) {
      failed = failed || e;
    }
  });
  // Rien de lisible du tout : vraie erreur (Accessibilité refusée…), pas un aléa.
  if (!apps.length && failed) throw failed;
  return apps;
}

// Pages du balayage à trois doigts (Spaces), dans l'ordre, par écran : API privée de SkyLight, en lecture seule et
// sans autorisation (comme yabai ou AltTab). Illisible : null, la tablette garde la vue sans pages.
function spaces(all) {
  try {
    ObjC.bindFunction('CGSMainConnectionID', ['int', []]);
    ObjC.bindFunction('CGSCopyManagedDisplaySpaces', ['void *', ['int']]);
    ObjC.bindFunction('CGDisplayCreateUUIDFromDisplayID', ['void *', ['unsigned int']]);
    ObjC.bindFunction('CFUUIDCreateString', ['void *', ['void *', 'void *']]);
    const uuid = (id) => ObjC.castRefToObject($.CFUUIDCreateString(null, $.CGDisplayCreateUUIDFromDisplayID(id))).js;
    const pages = [];
    ObjC.deepUnwrap(ObjC.castRefToObject($.CGSCopyManagedDisplaySpaces($.CGSMainConnectionID()))).forEach((d) => {
      // « Main » quand les écrans partagent leurs pages (réglage « Les écrans disposent de Spaces distincts » coupé).
      const s = all.length === 1 ? all[0] : all.find((x) => uuid(x.id) === d['Display Identifier']);
      const current = d['Current Space'] && d['Current Space'].ManagedSpaceID;
      // Écran partagé (deux applis sur une page plein écran) : pid est une liste, une fenêtre par appli.
      d.Spaces.forEach((sp) => pages.push({
        id: sp.ManagedSpaceID, display: s ? s.id : null, fullscreen: sp.type === 4, current: sp.ManagedSpaceID === current,
        pids: [].concat(sp.pid || []),
        wids: sp.TileLayoutManager && sp.TileLayoutManager.TileSpaces ? sp.TileLayoutManager.TileSpaces.map((t) => t.TileWindowID) : [sp.fs_wid],
      }));
    });
    return pages;
  } catch (e) {
    return null;
  }
}

// Fenêtre de chaque page plein écran : une fenêtre de son appli en plein écran ou sur un autre bureau, du même titre
// que celle du serveur de fenêtres quand il est lisible (Enregistrement de l'écran), sinon la seule ou la première.
function assignPages(apps, pages, cg) {
  pages.filter((pg) => pg.fullscreen).forEach((pg) => {
    const names = [];
    pg.pids.forEach((pid) => {
      const app = apps.find((a) => a.pid === pid);
      if (!app) return;
      names.push(app.name);
      const free = app.windows.filter((w) => w.page === undefined && (w.fullscreen || w.elsewhere));
      const known = ((cg && cg[pid]) || []).find((c) => pg.wids.includes(c.wid));
      const name = known && known.name;
      const w = (name && free.find((x) => sameTitle(x.title, name) || sameTitle(name, x.title))) ||
        free.find((x) => x.fullscreen) || free[0];
      if (w) w.page = pg.id;
    });
    if (names.length) pg.app = names.join(' & ');
  });
  pages.forEach((pg) => { delete pg.pids; delete pg.wids; });
}

function run(argv) {
  const req = JSON.parse(argv[0]);
  const all = screens();
  if (req.op === 'pages') {
    const pages = spaces(all);
    if (pages) pages.forEach((pg) => { delete pg.pids; delete pg.wids; });
    return JSON.stringify(pages);
  }
  const se = Application('System Events');
  if (req.op === 'all') {
    const cg = cgAll();
    const slow = [];
    const apps = allApps(se, all, cg, req.skip || [], slow);
    const pages = spaces(all);
    if (pages) assignPages(apps, pages, cg);
    return JSON.stringify({ apps: apps, displays: displaysOf(all), pages: pages, slow: slow });
  }
  // Sans appli (écrans seulement) : System Events n'est pas interrogé, macOS ne demande aucune autorisation.
  const procs = req.pid ? se.applicationProcesses.whose({ unixId: req.pid })() : req.bundleId ? se.applicationProcesses.whose({ bundleIdentifier: req.bundleId })() : [];
  const proc = procs[0];

  if (proc && req.op !== 'list') {
    const list = windowsOf(proc);
    const strict = req.op === 'close';
    let targets = req.window ? [find(list, req.window, strict)].filter(Boolean) : list;
    if (req.window && !targets.length) targets = [reach(proc, all, req.window.title, strict)].filter(Boolean);
    if (req.window && !targets.length) throw new Error('Fenêtre introuvable');
    if (req.op === 'place') targets.forEach((e) => place(e.w, all, req.display, req.layout));
    if (req.op === 'zoom' && targets.length) zoom(targets[0].w, all);
    if (req.op === 'minimize') targets.forEach((e) => { e.w.attributes.byName('AXMinimized').value = true; });
    else if (req.op === 'close') {
      const titles = targets.map((e) => e.w.name());
      targets.forEach((e) => closeWindow(e.w));
      // Appli quittée : plus rien à relire chez elle.
      if (quitIfLastWindow(proc, titles)) return JSON.stringify({ running: false, windows: [], displays: displaysOf(all) });
    }
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

/**
 * Applis qui ne répondent pas à l'Accessibilité (appli iOS sur Mac : plus d'une seconde par question, sans fenêtre lisible) :
 * sautées pendant une minute, sinon chaque relecture d'« Ouvertes » (toutes les 5 s, après chaque geste) prend 3 à 8 s.
 */
const slowApps = new Map<number, number>();
const SLOW_APP_SKIP_MS = 60_000;

/** Toutes les applis qui ont une fenêtre affichée (vue « Ouvertes »), la plus en avant d'abord ; `key` : identifiant de bundle. */
export async function listOpenWindows(): Promise<OpenWindows> {
  const now = Date.now();
  for (const [pid, until] of slowApps) if (until < now) slowApps.delete(pid);
  const raw = await osascript(SCRIPT, [JSON.stringify({ op: "all", skip: [...slowApps.keys()] })], "JavaScript");
  const parsed = JSON.parse(raw) as {
    apps: (OpenApp & { front: boolean; pid: number })[];
    displays: RawScreen[];
    pages: Page[] | null;
    slow: number[];
  };
  for (const pid of parsed.slow) slowApps.set(pid, now + SLOW_APP_SKIP_MS);
  const apps = parsed.apps.sort((a, b) => Number(b.front) - Number(a.front)).map(({ front: _, pid: __, ...app }) => app);
  return { apps, displays: labelDisplays(parsed.displays), ...(parsed.pages ? { pages: parsed.pages } : {}) };
}

// Ctrl + flèche de Mission Control : macOS n'y réagit que si la flèche porte, comme celles du clavier, les drapeaux
// fn et pavé numérique (sans eux, l'événement est ignoré). Un pas = Ctrl appuyé, flèche, Ctrl relâché.
const PAGE_SCRIPT = `ObjC.import('CoreGraphics');
ObjC.bindFunction('CGSMainConnectionID', ['int', []]);
ObjC.bindFunction('CGSGetActiveSpace', ['unsigned long long', ['int']]);
function post(key, down, flags) {
  const ev = $.CGEventCreateKeyboardEvent(null, key, down);
  $.CGEventSetFlags(ev, flags);
  $.CGEventPost(0, ev);
  delay(0.02);
}
function run(argv) {
  const arrow = argv[0] === 'right' ? 124 : 123;
  const CTRL = 0x40000, ARROW = CTRL | 0x800000 | 0x200000;
  const cid = $.CGSMainConnectionID();
  for (let i = 0; i < parseInt(argv[1], 10); i++) {
    const before = $.CGSGetActiveSpace(cid);
    post(59, true, CTRL);
    post(arrow, true, ARROW);
    post(arrow, false, ARROW);
    post(59, false, 0);
    // Pas suivant une fois la page changée (un pas envoyé pendant l'animation était perdu), 1 s au plus.
    for (let k = 0; k < 20 && $.CGSGetActiveSpace(cid) === before; k++) delay(0.05);
  }
}`;

// Ordre des pages : aucune API (yabai lui-même demande de couper le SIP). On fait le geste de l'utilisateur dans
// Mission Control : glisser la vignette de la page dans la barre du haut, puis refermer.
const MOVE_PAGE_SCRIPT = `ObjC.import('CoreGraphics');
function mouse(type, x, y) {
  $.CGEventPost(0, $.CGEventCreateMouseEvent(null, type, $.CGPointMake(x, y), 0));
}
// Mission Control ouvert : le Dock n'a de groupe que pendant Mission Control. Ouvrir ou fermer se fait par la même
// bascule : jamais sans regarder d'abord (un Mission Control resté ouvert inversait tous les essais suivants).
function isOpen() {
  try { return Application('System Events').processes.byName('Dock').groups.length > 0; } catch (e) { return false; }
}
function toggle(app, open) {
  if (isOpen() === open) return;
  app.doShellScript('open -b com.apple.exposelauncher');
  for (let i = 0; i < 40 && isOpen() !== open; i++) delay(0.05);
}
// Barre des pages de Mission Control (nom traduit selon la langue : trouvée par sa forme, une liste de boutons).
function bar() {
  const mc = Application('System Events').processes.byName('Dock').groups[0];
  const group = mc.groups[0].groups().find((g) => { try { return g.lists.length > 0; } catch (e) { return false; } });
  if (!group) throw new Error('Mission Control ne montre pas ses pages');
  return group.lists[0].buttons().map((b) => ({ name: b.name(), pos: b.position(), size: b.size() }));
}
function run(argv) {
  const from = parseInt(argv[0], 10), to = parseInt(argv[1], 10), count = parseInt(argv[2], 10);
  const shown = parseInt(argv[3], 10);
  let closed = false;
  const app = Application.currentApplication();
  app.includeStandardAdditions = true;
  const back = $.CGEventGetLocation($.CGEventCreate(null));
  toggle(app, true);
  try {
    // Vignettes en place : deux lectures identiques de suite, 4 s au plus (ouverture plus lente depuis le Bureau). Lue pendant l'animation d'ouverture,
    // la barre répond en plus d'une seconde : première lecture une fois l'animation passée.
    const start = Date.now();
    let b = null, last = '';
    delay(0.3);
    while (Date.now() - start < 4000) {
      try { b = bar(); } catch (e) { delay(0.05); continue; }
      const now = JSON.stringify(b);
      if (b.length === count && b[0].pos[1] >= 0 && now === last) break;
      last = now;
      delay(0.05);
    }
    if (!b || b.length !== count || b[0].pos[1] < 0) throw new Error('Mission Control ne montre pas les mêmes pages');
    const sx = b[from].pos[0] + b[from].size[0] / 2, y = b[from].pos[1] + b[from].size[1] / 2;
    // Dans l'espace entre deux vignettes, du côté de la destination (lâchée sur une vignette, elle fusionnerait).
    const tx = to > from ? b[to].pos[0] + b[to].size[0] + 12 : b[to].pos[0] - 12;
    mouse(5, sx, y);
    mouse(1, sx, y);
    delay(0.1);
    // Assez lentement pour que Mission Control suive vignette après vignette.
    const steps = 6 * Math.max(1, Math.abs(to - from));
    for (let i = 1; i <= steps; i++) { mouse(6, sx + (tx - sx) * i / steps, y); delay(0.035); }
    // Vers la gauche, lâcher seulement une fois la place ouverte (la barre montre déjà le nouvel ordre) : lâchée trop
    // tôt près du Bureau, une page plein écran y retombait en simple fenêtre. Sinon, retour au départ, rien ne change.
    const want = b.map((x) => x.name);
    want.splice(to, 0, want.splice(from, 1)[0]);
    // Vers la droite, Mission Control ne montre l'ordre qu'au lâcher : seul le côté gauche (celui du Bureau) attend.
    let ready = to > from;
    for (let k = 0; k < 12 && !ready; k++) {
      delay(0.08);
      mouse(6, tx + (k % 2 ? 2 : -2), y);
      try { ready = bar().map((x) => x.name).join('|') === want.join('|'); } catch (e) {}
    }
    if (!ready) {
      for (let i = 1; i <= 8; i++) { mouse(6, tx + (sx - tx) * i / 8, y); delay(0.015); }
      delay(0.15);
      mouse(2, sx, y);
      throw new Error('Mission Control n’a pas ouvert la place');
    }
    mouse(2, tx, y);
    // Laisser macOS poser la page, puis refermer en touchant la page qui était affichée : refermé par la bascule,
    // Mission Control restait sur une autre page (le Bureau).
    delay(0.2);
    // Parti du Bureau, Mission Control se referme seul au lâcher : rien à toucher (movePage vérifie la page).
    if (shown >= 0 && isOpen()) {
      try {
        const order = [...Array(count).keys()];
        order.splice(to, 0, order.splice(from, 1)[0]);
        const after = bar()[order.indexOf(shown)];
        const cx = after.pos[0] + after.size[0] / 2, cy = after.pos[1] + after.size[1] / 2;
        mouse(5, cx, cy);
        mouse(1, cx, cy);
        mouse(2, cx, cy);
        // Le toucher referme Mission Control sur cette page ; sinon, la bascule le referme.
        for (let i = 0; i < 20 && isOpen(); i++) delay(0.05);
      } catch (e) {}
    }
    closed = !isOpen();
  } finally {
    if (!closed) toggle(app, false);
    mouse(5, back.x, back.y);
  }
}`;

/**
 * Déplace une page du balayage à trois doigts à la place `to` (rang parmi les pages de son écran), par Mission
 * Control. Un seul écran à pages : avec plusieurs, la barre à prendre n'est pas sûre.
 */
export async function movePage(id: number, to: number) {
  const read = async () => JSON.parse(await osascript(SCRIPT, [JSON.stringify({ op: "pages" })], "JavaScript")) as Page[] | null;
  let pages = await read();
  const target = pages?.find((p) => p.id === id);
  if (!pages || !target) throw new Error("Page introuvable sur le Mac");
  if (new Set(pages.map((p) => p.display)).size > 1) throw new Error("Pages de plusieurs écrans : à ranger sur le Mac");
  if (to < 0 || to >= pages.length) throw new Error("Place introuvable sur le Mac");
  if (to === pages.indexOf(target)) return;
  const shown = pages.find((p) => p.current);
  // Depuis le Bureau, la barre de Mission Control est repliée et le glisser n'est pas fiable (une page lâchée sur une
  // autre les a fusionnées) : on part d'une page plein écran, puis on revient.
  if (shown && !shown.fullscreen) {
    const start = pages.find((p) => p.fullscreen && p.id !== id) ?? pages.find((p) => p.fullscreen);
    if (!start) throw new Error("Pas de page plein écran sur le Mac");
    await showPage(start.id);
    pages = (await read()) ?? pages;
  }
  const order = pages.map((p) => p.id);
  const args = [order.indexOf(id), to, order.length, pages.findIndex((p) => p.current)].map(String);
  await run("osascript", ["-l", "JavaScript", "-e", MOVE_PAGE_SCRIPT, ...args], 20_000);
  // Toujours sur la page affichée avant : Mission Control a pu se refermer ailleurs.
  if (shown) await showPage(shown.id);
  if (((await read()) ?? pages).length < pages.length) throw new Error("Deux pages se sont réunies en écran partagé sur le Mac");
}

/**
 * Passe sur une page du balayage à trois doigts comme le ferait Ctrl + flèche (raccourci de Mission Control, actif
 * par défaut) : autant de pas que de pages d'écart sur son écran.
 */
export async function showPage(id: number) {
  const pages = JSON.parse(await osascript(SCRIPT, [JSON.stringify({ op: "pages" })], "JavaScript")) as Page[] | null;
  const target = pages?.find((p) => p.id === id);
  if (!pages || !target) throw new Error("Page introuvable sur le Mac");
  const same = pages.filter((p) => p.display === target.display);
  const from = same.findIndex((p) => p.current);
  const to = same.indexOf(target);
  if (from < 0 || to === from) return;
  await osascript(PAGE_SCRIPT, [to > from ? "right" : "left", String(Math.abs(to - from))], "JavaScript");
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
