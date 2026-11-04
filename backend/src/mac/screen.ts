import { screenDenied } from "../driver-common.ts";
import { osascript } from "./run.ts";
import { defaultDeps } from "./permissions.ts";

/**
 * Aperçu de l'écran principal du Mac : un JPEG réduit, calculé en mémoire par un script JXA (CoreGraphics) qui rend
 * l'image en base64 sur sa sortie. Rien n'est écrit sur le disque. Sans l'autorisation « Enregistrement de l'écran »,
 * macOS ne donne que le fond d'écran : elle est lue avant chaque capture (utilitaire MatePermissions, 30 ms) et son absence
 * est une erreur claire. Utilitaire absent : la capture se fait, sans pouvoir détecter ce cas.
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

/** Faux : l'autorisation est absente. Null : illisible (utilitaire absent), on tente la capture. */
export async function screenAllowed(): Promise<boolean | null> {
  const status = await defaultDeps().status([]);
  return status ? status.screen === true : null;
}

export async function screenPreview(maxWidth: number): Promise<Buffer> {
  if ((await screenAllowed()) === false) screenDenied();
  try {
    return Buffer.from(await osascript(SCRIPT, [String(maxWidth)], "JavaScript"), "base64");
  } catch (e) {
    // Aucun écran lisible (session sans bureau) : 503 comme le presse-papiers, texte d'osascript gardé dans le journal.
    throw Object.assign(e as Error, { technical: true, statusCode: 503 });
  }
}
