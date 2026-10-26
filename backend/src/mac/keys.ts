import { spawn } from "node:child_process";
import { parseCombo } from "../driver-common.ts";
import type { MediaCommand } from "../screens.ts";
import { osascript, run } from "./run.ts";

// Modificateurs : code clavier de la touche et drapeau CGEvent correspondant.
const MODIFIERS: Record<string, string> = {
  cmd: "cmd",
  command: "cmd",
  shift: "shift",
  alt: "alt",
  option: "alt",
  ctrl: "ctrl",
  control: "ctrl",
};

// Touches sans caractère imprimable : envoyées par code clavier.
const KEY_CODES: Record<string, number> = {
  return: 36,
  enter: 36,
  tab: 48,
  space: 49,
  delete: 51,
  esc: 53,
  escape: 53,
  left: 123,
  right: 124,
  down: 125,
  up: 126,
  f1: 122,
  f2: 120,
  f3: 99,
  f4: 118,
  f5: 96,
  f6: 97,
  f7: 98,
  f8: 100,
  f9: 101,
  f10: 109,
  f11: 103,
  f12: 111,
  // Chiffres : position sur la rangée du haut, comme les raccourcis du Mac (⌘⇧4 en AZERTY aussi).
  "1": 18,
  "2": 19,
  "3": 20,
  "4": 21,
  "5": 23,
  "6": 22,
  "7": 26,
  "8": 28,
  "9": 25,
  "0": 29,
};

// Événements clavier postés directement (CGEventPost) : System Events refuse les saisies
// (erreur 1002) même quand l'appli du backend a l'accès Accessibilité. Un caractère est
// cherché dans la disposition active (AZERTY : "a" n'est pas sur la même touche qu'en QWERTY).
const KEY_SCRIPT = `ObjC.import('AppKit');
var MODS = { cmd: [55, 0x100000], shift: [56, 0x20000], alt: [58, 0x80000], ctrl: [59, 0x40000] };
function post(code, down, flags) {
  var ev = $.CGEventCreateKeyboardEvent(null, code, down);
  $.CGEventSetFlags(ev, flags);
  $.CGEventPost(0, ev);
  delay(0.01);
}
function run(argv) {
  var key = argv[0];
  var code = /^#\\d+$/.test(key) ? parseInt(key.slice(1), 10) : -1;
  for (var k = 0; k < 128 && code < 0; k++) {
    // Les touches de modification (54 à 63) n'ont pas de caractère : NSEvent lève une exception.
    try {
      var probe = $.NSEvent.eventWithCGEvent($.CGEventCreateKeyboardEvent(null, k, true));
      if (probe.charactersIgnoringModifiers.js === key) code = k;
    } catch (e) {}
  }
  if (code < 0) throw new Error('Touche absente du clavier du Mac : ' + key);
  var mods = argv.slice(1).map(function (m) { return MODS[m]; });
  var flags = 0;
  mods.forEach(function (m) { flags |= m[1]; post(m[0], true, flags); });
  post(code, true, flags);
  post(code, false, flags);
  mods.reverse().forEach(function (m) { flags &= ~m[1]; post(m[0], false, flags); });
}`;

/** Raccourci au format "cmd+shift+m", envoyé à `app` (mise au premier plan avant) ou à l'appli active. */
export async function sendKeys(combo: string, app?: string) {
  const { key, mods } = parseCombo(combo, MODIFIERS);
  const code = KEY_CODES[key];
  if (code === undefined && key.length !== 1) throw new Error(`Touche inconnue : ${key}`);
  if (app) {
    // `open -a` plutôt que `activate` en AppleScript : pas d'autorisation Automatisation par appli.
    await run("open", ["-a", app]);
    await new Promise((resolve) => setTimeout(resolve, 400));
  }
  await osascript(KEY_SCRIPT, [code !== undefined ? `#${code}` : key, ...new Set(mods)], "JavaScript");
}

// Touches multimédia (play/pause, suivant, précédent) : événements système NX_KEYTYPE_*,
// postés via le pont ObjC de JavaScript for Automation.
const MEDIA_SCRIPT = `ObjC.import('AppKit');
function run(argv) {
  const key = parseInt(argv[0], 10);
  [true, false].forEach(function (down) {
    const flags = down ? 0xa00 : 0xb00;
    const data1 = (key << 16) | ((down ? 0xa : 0xb) << 8);
    const ev = $.NSEvent.otherEventWithTypeLocationModifierFlagsTimestampWindowNumberContextSubtypeData1Data2(
      14, $.NSMakePoint(0, 0), flags, 0, 0, null, 8, data1, -1);
    $.CGEventPost(0, ev.CGEvent);
  });
}`;

const MEDIA_KEYS: Record<MediaCommand, number> = { "play-pause": 16, next: 17, previous: 18 };

export async function sendMediaKey(command: MediaCommand) {
  await osascript(MEDIA_SCRIPT, [String(MEDIA_KEYS[command])], "JavaScript");
}

/** Colle un texte dans l'appli au premier plan, puis remet l'ancien texte du presse-papiers. */
export async function typeText(text: string) {
  const previous = await run("pbpaste", []).catch(() => "");
  await pbcopy(text);
  await sendKeys("cmd+v");
  // Laisser l'appli lire le presse-papiers avant de le restaurer.
  await new Promise((resolve) => setTimeout(resolve, 400));
  await pbcopy(previous);
}

function pbcopy(text: string) {
  return new Promise<void>((resolve, reject) => {
    const child = spawn("pbcopy");
    child.on("error", reject);
    child.on("close", (code) => (code === 0 ? resolve() : reject(new Error(`pbcopy : code ${code}`))));
    child.stdin.end(text);
  });
}
