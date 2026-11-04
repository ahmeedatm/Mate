import { screenDenied } from "../driver-common.ts";
import { automation } from "./permissions.ts";
import { osascript } from "./run.ts";

/**
 * Aperçu de l'écran principal du Mac : un JPEG réduit, calculé en mémoire par un script JXA (CoreGraphics) qui rend
 * l'image en base64 sur sa sortie. Rien n'est écrit sur le disque. Sans l'autorisation « Enregistrement de l'écran »,
 * macOS ne donne que le fond d'écran, voire ouvre une fenêtre d'autorisation : la capture n'est lancée que si elle est
 * accordée, lue par le filtre de permissions.ts (état gardé 10 s). Fausse ou illisible : erreur dite sur la tablette.
 */
const SCRIPT = `ObjC.import('CoreGraphics');
ObjC.import('AppKit');
function run(argv) {
  var maxWidth = parseInt(argv[0], 10);
  var image = $.CGDisplayCreateImage($.CGMainDisplayID());
  var w = $.CGImageGetWidth(image), h = $.CGImageGetHeight(image);
  if (!w || !h) throw new Error('Aucun écran à lire');
  var scale = Math.min(1, maxWidth / w);
  var nw = Math.max(1, Math.round(w * scale)), nh = Math.max(1, Math.round(h * scale));
  // 6 | 8192 : sans transparence, octets en ordre natif (kCGImageAlphaNoneSkipFirst | kCGBitmapByteOrder32Little).
  var context = $.CGBitmapContextCreate(null, nw, nh, 8, 0, $.CGColorSpaceCreateWithName($.kCGColorSpaceSRGB), 6 | 8192);
  $.CGContextSetInterpolationQuality(context, 3);
  $.CGContextDrawImage(context, $.CGRectMake(0, 0, nw, nh), image);
  var bitmap = $.NSBitmapImageRep.alloc.initWithCGImage($.CGBitmapContextCreateImage(context));
  var jpeg = bitmap.representationUsingTypeProperties($.NSBitmapImageFileTypeJPEG, $({ NSImageCompressionFactor: 0.6 }));
  return ObjC.unwrap(jpeg.base64EncodedStringWithOptions(0));
}`;

/** Ce dont la capture dépend, remplaçable dans les tests. */
export type ScreenDeps = {
  /** Enregistrement de l'écran accordé ; null : illisible. */
  allowed: () => Promise<boolean | null>;
  /** Lance le script ; rend le JPEG en base64. */
  capture: (maxWidth: number) => Promise<string>;
};

const defaultDeps: ScreenDeps = {
  allowed: () => automation.screenAllowed(),
  capture: (maxWidth) => osascript(SCRIPT, [String(maxWidth)], "JavaScript"),
};

export async function screenPreview(maxWidth: number, deps: ScreenDeps = defaultDeps): Promise<Buffer> {
  const allowed = await deps.allowed();
  if (allowed === false) screenDenied();
  if (allowed === null) {
    throw Object.assign(new Error("Les autorisations du Mac sont illisibles : relance Mate, ou mets-le à jour"), { statusCode: 503 });
  }
  try {
    return Buffer.from(await deps.capture(maxWidth), "base64");
  } catch (e) {
    // Aucun écran lisible (session sans bureau) : 503 comme le presse-papiers, texte d'osascript gardé dans le journal.
    throw Object.assign(e as Error, { technical: true, statusCode: 503 });
  }
}
