import { clipboardUnavailable } from "../driver-common.ts";
import type { ClipboardContent } from "../screens.ts";
import { pbcopy } from "./keys.ts";
import { osascript } from "./run.ts";

/**
 * Presse-papiers du Mac (NSPasteboard, via JavaScript for Automation). Priorité de lecture : type sensible
 * (gestionnaire de mots de passe) → `protected`, sans jamais lire le texte ; sinon texte ; sinon image ; vide ; reste.
 */

// Types posés par les gestionnaires de mots de passe (convention nspasteboard.org, 1Password).
const SENSITIVE = ["org.nspasteboard.ConcealedType", "org.nspasteboard.TransientType", "com.agilebits.onepassword"];

// Le texte est coupé à 100 001 caractères : assez pour que la route voie qu'il dépasse 100 000, sans dépasser la
// taille maximale de sortie d'un processus. Réponse en JSON : stdout est rogné (run), le texte garde ses espaces.
const READ_SCRIPT = `ObjC.import('AppKit');
function run(argv) {
  var pb, types;
  // Session sans bureau : le presse-papiers n'est pas joignable, ce que le compagnon signale à part (503).
  try {
    pb = $.NSPasteboard.generalPasteboard;
    types = ObjC.deepUnwrap(pb.types) || [];
  } catch (e) {
    return JSON.stringify({ kind: 'unavailable' });
  }
  var sensitive = JSON.parse(argv[0]);
  if (types.some(function (t) { return sensitive.indexOf(t) >= 0; })) return JSON.stringify({ kind: 'protected' });
  var text = null;
  if (types.length > 0) {
    try { text = ObjC.unwrap(pb.stringForType($.NSPasteboardTypeString)); } catch (e) {}
  }
  if (typeof text === 'string' && text.length > 0) {
    return JSON.stringify({ kind: 'text', text: text.length > 100000 ? text.slice(0, 100001) : text });
  }
  if (types.indexOf('public.png') >= 0 || types.indexOf('public.tiff') >= 0) return JSON.stringify({ kind: 'image' });
  if (types.length === 0 || typeof text === 'string') return JSON.stringify({ kind: 'empty' });
  return JSON.stringify({ kind: 'other' });
}`;

const STAMP_SCRIPT = `ObjC.import('AppKit');
function run() {
  return String($.NSPasteboard.generalPasteboard.changeCount);
}`;

// Image : le fichier devient un NSImage, déposé comme image (le presse-papiers en dérive png et tiff).
const IMAGE_SCRIPT = `ObjC.import('AppKit');
function run(argv) {
  var image = $.NSImage.alloc.initWithContentsOfFile(argv[0]);
  var ok = false;
  try { ok = image.size.width > 0; } catch (e) {}
  if (!ok) throw new Error('Image illisible');
  var pb = $.NSPasteboard.generalPasteboard;
  pb.clearContents();
  if (!pb.writeObjects($.NSArray.arrayWithObject(image))) throw new Error('Presse-papiers inaccessible');
}`;

export async function getClipboard(): Promise<ClipboardContent> {
  const out = await osascript(READ_SCRIPT, [JSON.stringify(SENSITIVE)], "JavaScript");
  const content = JSON.parse(out) as ClipboardContent | { kind: "unavailable" };
  if (content.kind === "unavailable") clipboardUnavailable();
  return content;
}

/** changeCount du presse-papiers : change à chaque copie, sans lire le contenu. */
export async function clipboardStamp(): Promise<string> {
  return osascript(STAMP_SCRIPT, [], "JavaScript");
}

export async function setClipboardText(text: string) {
  await pbcopy(text);
}

export async function setClipboardImage(fullPath: string) {
  await osascript(IMAGE_SCRIPT, [fullPath], "JavaScript");
}
