import { osascript } from "./run.ts";

/**
 * Appel en cours dans une appli de visio (Teams) lu par l'API d'Accessibilité, comme Discord sous Windows.
 * Teams (moteur web Edge) n'expose ses boutons qu'une fois `AXEnhancedUserInterface` posé sur l'appli : le script
 * le pose à chaque lecture (sans effet visible), l'arbre arrive à la lecture suivante. Les boutons de l'appel
 * (« Couper le micro », « Partager », « Quitter »…) changent de nom selon l'état : c'est ce qui donne micro et partage.
 * Appelé par l'API C (AXUIElement…) plutôt que System Events : 34 ms au lieu de 2 s pour la fenêtre d'appel.
 * La sonnerie (« Eugénie … vous appelle », Accepter / Refuser) est une boîte native d'un autre processus,
 * « Microsoft Teams (Notification Center) » : les processus « <appli> (…) » sont lus avec l'appli, et le texte des
 * boîtes de dialogue est gardé (`text`) pour le nom de l'appelant.
 */
const LEAVE_SOURCE = String.raw`^(Quitter|Raccrocher|Leave|Hang up)\b`;
/** Fenêtre de sonnerie : ses boutons passent avant ceux du reste de Teams (« Refuser » d'une invitation…). */
const RING_SOURCE = String.raw`^(Accepter|Accept)\b`;
/**
 * Panneau « Partager du contenu » de Teams : boîte modale qui cache à l'Accessibilité le reste de la fenêtre d'appel
 * (micro, Quitter…). Ses écrans (liste « Choisir un écran ») deviennent des boutons « Écran : <nom> » ; la fenêtre
 * qui les montre est une fenêtre d'appel.
 */
const SCREEN_SOURCE = String.raw`^Écran : `;
/** Pendant un partage, la fenêtre compacte n'a plus « Quitter » mais « Arrêter le partage » : c'est aussi l'appel. */
const SHARING_SOURCE = String.raw`^Arrêter (le partage|la présentation)|^Stop (sharing|presenting)`;

const SCRIPT = `ObjC.import("AppKit");
ObjC.import("ApplicationServices");
function attr(el, name) {
  const r = Ref();
  return $.AXUIElementCopyAttributeValue(el, $(name), r) === 0 ? r[0] : null;
}
function str(el, name) {
  const v = attr(el, name);
  if (!v) return "";
  const o = ObjC.castRefToObject(v);
  return typeof o.js === "string" ? o.js : "";
}
function list(el, name) {
  const v = attr(el, name);
  if (!v) return [];
  const a = ObjC.castRefToObject(v), out = [];
  for (let i = 0; i < a.count; i++) out.push(a.objectAtIndex(i));
  return out;
}
function run(argv) {
  const [appName, pressJson] = argv;
  const apps = $.NSWorkspace.sharedWorkspace.runningApplications;
  let main = false;
  const pids = [];
  for (let i = 0; i < apps.count; i++) {
    // Certains processus n'ont pas de nom.
    const a = apps.objectAtIndex(i), name = a.localizedName.js || "";
    if (name === appName) main = true;
    if (name === appName || name.startsWith(appName + " (")) pids.push(a.processIdentifier);
  }
  if (!main) return JSON.stringify(null);
  const press = pressJson ? JSON.parse(pressJson) : null;
  const windows = [], shareDialogs = [];
  for (const pid of pids) {
    const app = $.AXUIElementCreateApplication(pid);
    $.AXUIElementSetAttributeValue(app, $("AXEnhancedUserInterface"), $.kCFBooleanTrue);
    for (const w of list(app, "AXWindows")) {
      const dialog = str(w, "AXSubrole") === "AXDialog";
      const buttons = [], texts = [], stack = [[w, false]];
      while (stack.length) {
        const [e, inScreens] = stack.pop(), role = str(e, "AXRole");
        if (str(e, "AXSubrole") === "AXApplicationDialog" && /^(Partager du contenu|Share content)$/i.test(str(e, "AXDescription") || str(e, "AXTitle")))
          shareDialogs.push({ e, w, pid });
        const screens = inScreens || (role === "AXList" && /^(Choisir un écran|Choose a screen)$/i.test(str(e, "AXDescription") || str(e, "AXTitle")));
        if (role === "AXButton" || (inScreens && role === "AXMenuItem")) {
          // Les écrans du panneau de partage portent leur nom en valeur (« Built-in Retina Display »).
          const name = str(e, "AXDescription") || str(e, "AXTitle") || (inScreens ? str(e, "AXValue") : "");
          if (name) buttons.push({ name: inScreens ? "Écran : " + name : name, e });
        } else if (dialog && role === "AXStaticText") {
          const value = str(e, "AXValue");
          if (value) texts.push(value);
        }
        // À l'envers : la pile rend les enfants dans l'ordre de l'écran (appelant avant « vous appelle »).
        for (const k of list(e, "AXChildren").reverse()) stack.push([k, screens]);
      }
      windows.push({ title: str(w, "AXTitle"), text: texts.join(" "), buttons });
    }
  }
  if (!press) return JSON.stringify({ windows: windows.map((w) => ({ title: w.title, text: w.text, buttons: w.buttons.map((b) => b.name) })) });
  // Un bouton « Partager » existe aussi dans les conversations : d'abord la fenêtre de l'appel ou de la sonnerie.
  const call = /${LEAVE_SOURCE}|${RING_SOURCE}|${SCREEN_SOURCE}|${SHARING_SOURCE}/i;
  const order = windows.filter((w) => w.buttons.some((b) => call.test(b.name))).concat(windows);
  // Panneau de partage ouvert et autre chose demandé (micro…) : Teams ignore ses autres boutons, on le ferme d'abord.
  // Le panneau n'a pas d'action « fermer » et Échap envoyé au processus ne l'atteint pas : Teams mis devant, focus
  // sur un bouton du panneau, puis Échap (vérifié). L'appelant recommence une fois le panneau parti.
  if (shareDialogs.length && !press.some((p) => /^Écran :/.test(p))) {
    const { e, w, pid } = shareDialogs[0];
    let target = null;
    const inside = [e];
    while (inside.length && !target) {
      const x = inside.pop();
      if (str(x, "AXRole") === "AXButton") target = x;
      for (const k of list(x, "AXChildren")) inside.push(k);
    }
    const running = $.NSRunningApplication.runningApplicationWithProcessIdentifier(pid);
    running.activateWithOptions(0);
    $.AXUIElementPerformAction(w, $("AXRaise"));
    if (target) $.AXUIElementSetAttributeValue(target, $("AXFocused"), $.kCFBooleanTrue);
    delay(0.3);
    // Teams pas devant malgré tout : Échap à son seul processus plutôt qu'à l'appli de devant.
    for (const down of [true, false]) {
      const key = $.CGEventCreateKeyboardEvent(null, 53, down);
      if (running.active) $.CGEventPost(0, key);
      else $.CGEventPostToPid(pid, key);
    }
    return JSON.stringify({ pressed: null, closed: true });
  }
  // Noms dans l'ordre de préférence : « Accepter avec audio » avant « Accepter », quel que soit l'ordre à l'écran.
  for (const w of order) {
    for (const p of press) {
      const b = w.buttons.find((b) => b.name === p || b.name.startsWith(p + " "));
      if (b) {
        $.AXUIElementPerformAction(b.e, $("AXPress"));
        return JSON.stringify({ pressed: b.name });
      }
    }
  }
  return JSON.stringify({ pressed: null });
}`;

const LEAVE = new RegExp(LEAVE_SOURCE, "i");
const MIC_ON = /^(Couper|Désactiver) (le )?micro|^Mute\b/i;
const MIC_OFF = /^(Activer|Réactiver|Rétablir) (le )?micro|^Unmute\b/i;
const CAMERA_ON = /^(Désactiver|Couper|Arrêter) (la )?(caméra|vidéo)|^Turn (camera|video) off\b/i;
const CAMERA_OFF = /^(Activer|Démarrer|Allumer) (la )?(caméra|vidéo)|^Turn (camera|video) on\b/i;
const JOIN_NOW = /^(Rejoindre maintenant|Join now)$/i;
const SCREEN = new RegExp(SCREEN_SOURCE);
const SHARING = new RegExp(SHARING_SOURCE, "i");

export type CallState = {
  found: boolean;
  connected: boolean;
  streaming: boolean;
  channel: string | null;
  people: string[];
  muted: boolean | null;
  deafened: boolean | null;
  /** Caméra coupée (bouton « Activer la caméra ») ; null si le bouton n'est pas lu. */
  cameraOff?: boolean | null;
  /** Écran d'avant-réunion de Teams (« Rejoindre maintenant ») : pas encore dans la réunion. */
  lobby?: boolean;
};

export type AppWindows = { windows: { title: string; text: string; buttons: string[] }[] } | null;

// /deck/live, /deck/voice et la surveillance des appels entrants lisent la même chose à quelques ms d'écart :
// une lecture sert à tous.
const cache = new Map<string, { at: number; value: Promise<AppWindows> }>();

function readWindows(app: string): Promise<AppWindows> {
  const hit = cache.get(app);
  if (hit && Date.now() - hit.at < 1500) return hit.value;
  const value = osascript(SCRIPT, [app], "JavaScript").then((out) => JSON.parse(out) as AppWindows);
  cache.set(app, { at: Date.now(), value });
  value.catch(() => cache.delete(app));
  return value;
}

/**
 * Dernier appel vu par appli. Teams mis devant (raccourci « Lever la main ») passe de l'affichage compact à la fenêtre
 * complète, dont l'arbre n'arrive qu'à la lecture suivante : l'appel semble fini un instant. On le garde 8 s, sauf
 * après avoir appuyé sur Quitter.
 */
const lastCall = new Map<string, { at: number; state: CallState }>();
const CALL_GRACE_MS = 8_000;

export async function callState(app: string): Promise<CallState | null> {
  const state = await readCall(app);
  if (state?.connected) lastCall.set(app, { at: Date.now(), state });
  else {
    const last = lastCall.get(app);
    if (state && last && Date.now() - last.at < CALL_GRACE_MS) return last.state;
    lastCall.delete(app);
  }
  return state;
}

async function readCall(app: string): Promise<CallState | null> {
  return callStateOf(await readWindows(app));
}

/** État de l'appel d'après les fenêtres lues (exporté pour les tests). */
export function callStateOf(raw: AppWindows): CallState | null {
  if (!raw) return null;
  // Fenêtres d'appel : avec « Quitter », ou « Arrêter le partage » (partage en cours, plus de « Quitter »), ou le
  // panneau de partage ouvert. La principale (nom, micro) : celle qui a « Quitter », sinon celle qui a le micro
  // (pas la « Barre de contrôle de partage »).
  const has = (w: { buttons: string[] }, re: RegExp) => w.buttons.some((b) => re.test(b));
  const calls = raw.windows.filter((w) => has(w, LEAVE) || has(w, SHARING) || has(w, SCREEN));
  const mic = (w: { buttons: string[] }) => has(w, MIC_ON) || has(w, MIC_OFF);
  const call = calls.find((w) => has(w, LEAVE)) ?? calls.find(mic) ?? calls[0];
  if (!call) {
    const lobby = raw.windows.some((w) => w.buttons.some((b) => JOIN_NOW.test(b)));
    return {
      found: raw.windows.length > 0,
      connected: false,
      streaming: false,
      channel: null,
      people: [],
      muted: null,
      deafened: null,
      cameraOff: null,
      lobby,
    };
  }
  // « Affichage compact de réunion | Ahmed ATMANE » → « Ahmed ATMANE ».
  const channel =
    call.title
      .replace(/\s*\|\s*Microsoft Teams$/i, "")
      .split(" | ")
      .pop()
      ?.trim() || null;
  const micWindow = mic(call) ? call : calls.find(mic);
  const muted = micWindow ? has(micWindow, MIC_OFF) : null;
  const camera = (w: { buttons: string[] }) => has(w, CAMERA_ON) || has(w, CAMERA_OFF);
  const cameraWindow = camera(call) ? call : calls.find(camera);
  const cameraOff = cameraWindow ? has(cameraWindow, CAMERA_OFF) : null;
  const streaming = calls.some((w) => has(w, SHARING));
  return { found: true, connected: true, streaming, channel, people: [], muted, deafened: null, cameraOff };
}

/** Boutons de toutes les fenêtres de l'appli (sonnerie, diagnostic) ; `detail` ajoute titres et textes des boîtes. */
export async function listButtons(app: string, detail = false): Promise<{ buttons: string[]; total: number }> {
  const raw = await readWindows(app);
  const buttons = (raw?.windows ?? []).flatMap((w) =>
    detail ? [`Fenêtre : ${w.title}`, ...(w.text ? [`Texte : ${w.text}`] : []), ...w.buttons] : w.buttons,
  );
  return { buttons, total: buttons.length };
}

/** Actionne le premier bouton dont le nom (ou son début) est dans `names` ; null si aucun n'est là. */
export async function pressButton(app: string, names: string[]): Promise<string | null> {
  // Panneau de partage fermé par le script : on réessaie quand Teams a remis ses boutons (2 s au plus).
  for (let attempt = 0; attempt < 5; attempt++) {
    const raw = JSON.parse(await osascript(SCRIPT, [app, JSON.stringify(names)], "JavaScript")) as {
      pressed: string | null;
      closed?: boolean;
    } | null;
    cache.delete(app);
    if (raw?.closed) {
      await new Promise((r) => setTimeout(r, 400));
      continue;
    }
    if (raw?.pressed && LEAVE.test(raw.pressed)) lastCall.delete(app);
    return raw?.pressed ?? null;
  }
  return null;
}
