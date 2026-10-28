import { spawn, type ChildProcess } from "node:child_process";
import type { PointerAction, PointerButton } from "../pointer.ts";

/**
 * Pavé tactile sur Mac : un processus JXA résident (osascript lancé une fois, commandes en JSON, une par ligne sur son
 * entrée standard) plutôt qu'un osascript par paquet, trop lent pour suivre un doigt. Relancé s'il s'arrête.
 * Écrit sans Mac sous la main : non vérifié, à essayer avant de s'y fier.
 */
const SCRIPT = `ObjC.import('AppKit');
var held = { left: false, right: false };
var lastClick = { t: 0, x: 0, y: 0, n: 0 };
// Zone de tous les écrans, en coordonnées CoreGraphics (origine en haut à gauche de l'écran principal).
function bounds() {
  var screens = $.NSScreen.screens;
  var top = screens.objectAtIndex(0).frame.size.height;
  var x0 = 1e9, y0 = 1e9, x1 = -1e9, y1 = -1e9;
  for (var i = 0; i < screens.count; i++) {
    var f = screens.objectAtIndex(i).frame;
    x0 = Math.min(x0, f.origin.x);
    x1 = Math.max(x1, f.origin.x + f.size.width);
    y0 = Math.min(y0, top - f.origin.y - f.size.height);
    y1 = Math.max(y1, top - f.origin.y);
  }
  return { x0: x0, y0: y0, x1: x1 - 1, y1: y1 - 1 };
}
function here() { return $.CGEventGetLocation($.CGEventCreate(null)); }
function mouse(type, x, y, button, clicks) {
  var ev = $.CGEventCreateMouseEvent(null, type, { x: x, y: y }, button);
  if (clicks) $.CGEventSetIntegerValueField(ev, 1, clicks);
  $.CGEventPost(0, ev);
}
function move(dx, dy) {
  var p = here(), b = bounds();
  var x = Math.min(b.x1, Math.max(b.x0, p.x + dx)), y = Math.min(b.y1, Math.max(b.y0, p.y + dy));
  // Bouton maintenu : événement de glissement, sans quoi le glisser-déposer ne suit pas.
  mouse(held.left ? 6 : held.right ? 7 : 5, x, y, held.right && !held.left ? 1 : 0, 0);
}
function scroll(sx, sy) {
  // Version non variadique (JXA ne sait pas appeler CGEventCreateScrollWheelEvent) ; 1 = unité « ligne ».
  $.CGEventPost(0, $.CGEventCreateScrollWheelEvent2(null, 1, 2, sy, sx, 0));
}
function button(name, action) {
  var p = here(), right = name === 'right';
  var down = right ? 3 : 1, up = right ? 4 : 2, id = right ? 1 : 0;
  if (action === 'up') { mouse(up, p.x, p.y, id, 1); held[name] = false; return; }
  var now = Date.now();
  var again = now - lastClick.t < 500 && Math.abs(p.x - lastClick.x) < 5 && Math.abs(p.y - lastClick.y) < 5;
  lastClick = { t: now, x: p.x, y: p.y, n: again ? lastClick.n + 1 : 1 };
  mouse(down, p.x, p.y, id, lastClick.n);
  if (action === 'down') { held[name] = true; return; }
  mouse(up, p.x, p.y, id, lastClick.n);
}
var input = $.NSFileHandle.fileHandleWithStandardInput;
var pending = '';
for (;;) {
  var data = input.availableData;
  if (data.length === 0) break;
  pending += $.NSString.alloc.initWithDataEncoding(data, 4).js;
  var lines = pending.split('\\n');
  pending = lines.pop();
  lines.forEach(function (line) {
    try {
      var c = JSON.parse(line);
      if (c.k === 'move') move(c.dx, c.dy);
      else if (c.k === 'scroll') scroll(c.sx, c.sy);
      else if (c.k === 'button') button(c.b, c.a);
    } catch (e) {}
  });
}`;

let child: ChildProcess | undefined;

function send(command: Record<string, unknown>) {
  if (!child || child.exitCode !== null || child.killed) {
    const started = spawn("osascript", ["-l", "JavaScript", "-e", SCRIPT], { stdio: ["pipe", "ignore", "ignore"] });
    // Mort ou introuvable : le prochain paquet le relance.
    started.on("exit", () => child === started && (child = undefined));
    started.on("error", () => child === started && (child = undefined));
    started.stdin!.on("error", () => {});
    child = started;
  }
  child.stdin!.write(JSON.stringify(command) + "\n");
}

export async function pointerMove(dx: number, dy: number) {
  send({ k: "move", dx, dy });
}

export async function pointerScroll(sx: number, sy: number) {
  send({ k: "scroll", sx, sy });
}

export async function pointerButton(button: PointerButton, action: PointerAction) {
  send({ k: "button", b: button, a: action });
}
