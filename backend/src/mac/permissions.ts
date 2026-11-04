import { execFile, spawn } from "node:child_process";
import dgram from "node:dgram";
import fs from "node:fs";
import net from "node:net";
import os from "node:os";
import path from "node:path";
import { backendRoot } from "../paths.ts";
import { portable } from "../portable.ts";
import { openAccessibilitySettings } from "./access.ts";
import { notificationsDiskAccess } from "./notifications.ts";
import { run } from "./run.ts";

/**
 * Autorisations du Mac (TCC) dont le compagnon a besoin, pour l'écran « Autorisations du Mac » de la page locale
 * (routes/permissions.ts). macOS les attribue au « processus responsable » : l'agent Mate (portable/mac/agent), dont
 * node est l'enfant. Les fenêtres de macOS disent donc « Mate ». Les états viennent de l'utilitaire
 * `src/mac/MatePermissions` (Permissions.swift) ; sans lui (lancé depuis le dépôt sur un autre système, binaire absent),
 * tout est « unknown » et rien ne plante.
 */

export type PermissionState = "granted" | "denied" | "unknown" | "not-asked";

export type Permission = {
  id: string;
  title: string;
  /** Pourquoi le compagnon en a besoin, en une phrase. */
  why: string;
  state: PermissionState;
  /** Sans elle, le compagnon ne sert à rien : l'écran s'ouvre seul tant qu'elle manque. */
  essential: boolean;
  /** `request` : une fenêtre de macOS va s'ouvrir ; `settings` : seulement Réglages Système ; null : rien à faire. */
  action: "request" | "settings" | null;
  /** Ce qui va apparaître, dit avant d'appuyer sur « Autoriser ». */
  appear: string;
};

type App = {
  id: string;
  name: string;
  /** Nom du dossier .app et de l'appli pour AppleScript. */ folder: string;
  bundle: string;
  why: string;
};

/**
 * Navigateurs (src/mac/browser.ts) et lecteurs (src/mac/media.ts) pilotés en AppleScript. `name` : nom de l'appli, celui
 * de ces deux fichiers et de son dossier .app.
 */
const BROWSERS = [
  ["Safari", "com.apple.Safari"],
  ["Google Chrome", "com.google.Chrome"],
  ["Arc", "company.thebrowser.Browser"],
  ["Microsoft Edge", "com.microsoft.edgemac"],
  ["Brave Browser", "com.brave.Browser"],
  ["Opera", "com.operasoftware.Opera"],
  ["Vivaldi", "com.vivaldi.Vivaldi"],
] as const;
const PLAYERS = [
  ["Spotify", "com.spotify.client"],
  ["Music", "com.apple.Music"],
] as const;
export const OPTIONAL_APPS: App[] = [
  ...BROWSERS.map(([name, bundle]) => ({
    id: `automation-${bundle}`,
    name,
    folder: name,
    bundle,
    why: "Pour lire la page ouverte et les onglets sur la tablette.",
  })),
  ...PLAYERS.map(([name, bundle]) => ({
    id: `automation-${bundle}`,
    name: name === "Music" ? "Musique" : name,
    folder: name,
    bundle,
    why: "Pour afficher et piloter la lecture en cours sur la tablette.",
  })),
];
const SYSTEM_EVENTS = "com.apple.systemevents";

/** Dossiers où chercher « <nom>.app » : Applications de l'ordinateur, du système et de l'utilisateur. */
const APP_FOLDERS = [
  "/Applications",
  "/System/Applications",
  "/System/Cryptexes/App/System/Applications",
  path.join(os.homedir(), "Applications"),
];

/** Panneaux de Réglages Système (x-apple.systempreferences:com.apple.preference.security?<panneau>). */
const PANES = {
  accessibility: "Privacy_Accessibility",
  automation: "Privacy_Automation",
  disk: "Privacy_AllFiles",
  screen: "Privacy_ScreenCapture",
  network: "Privacy_LocalNetwork",
} as const;

export type Deps = {
  /** Mac seulement : ailleurs, la liste est vide. */
  supported: boolean;
  /** Version majeure de Darwin (24 = macOS 15). Le Réseau local n'est une autorisation que depuis macOS 15. */
  darwinMajor: number;
  /** `MatePermissions status …` : sa réponse JSON, ou null si l'utilitaire est absent ou plante. */
  status: (bundles: string[]) => Promise<{ accessibility?: boolean; screen?: boolean; automation?: Record<string, string> } | null>;
  /** Lance une demande (fenêtre de macOS) sans attendre le choix de l'utilisateur. Faux si l'utilitaire est absent. */
  request: (args: string[]) => Promise<boolean>;
  installed: (name: string) => boolean;
  /** Issue de la dernière lecture de la base des notifications (la seule chose qui demande l'accès complet au disque) : l'état n'ouvre jamais cette base. */
  fullDisk: () => PermissionState;
  probeLocalNetwork: () => Promise<PermissionState>;
  openAccessibility: () => Promise<void>;
  openSettings: (pane: string) => Promise<void>;
  /** Faux si l'utilitaire est absent (rien à lire, donc aucun filtre) ; vrai s'il est là, même quand une lecture échoue. */
  available: () => boolean;
  /** Lance une appli en arrière-plan, sans rien demander : macOS ne dit l'état d'une Automation que si l'appli tourne. */
  wake: (bundle: string) => Promise<void>;
};

const UTILITY = path.join(backendRoot, "src", "mac", "MatePermissions");

export function defaultDeps(): Deps {
  return {
    // Appli portable seulement : depuis le dépôt (npm run dev, tests), les autorisations vont au terminal, pas à l'agent.
    supported: process.platform === "darwin" && portable,
    darwinMajor: Number.parseInt(os.release(), 10),
    status: (bundles) =>
      new Promise((resolve) => {
        if (!fs.existsSync(UTILITY)) return resolve(null);
        execFile(UTILITY, ["status", ...bundles], { timeout: 8_000 }, (err, stdout) => {
          if (err) return resolve(null);
          try {
            resolve(JSON.parse(stdout));
          } catch {
            resolve(null);
          }
        });
      }),
    request: (args) =>
      new Promise((resolve) => {
        if (!fs.existsSync(UTILITY)) return resolve(false);
        // Attend le choix de l'utilisateur : détaché, borné à 2 minutes.
        const child = spawn(UTILITY, args, { stdio: "ignore", timeout: 120_000 });
        child.once("error", () => resolve(false));
        child.once("spawn", () => {
          child.unref();
          resolve(true);
        });
      }),
    installed: (name) => APP_FOLDERS.some((dir) => fs.existsSync(path.join(dir, `${name}.app`))),
    fullDisk: notificationsDiskAccess,
    probeLocalNetwork: async () => {
      const { tabletAddress } = await import("../tablet.ts");
      const ip = tabletAddress();
      return ip ? tcpProbe(ip, 8080) : udpProbe();
    },
    openAccessibility: openAccessibilitySettings,
    openSettings: async (pane) => void (await run("open", [`x-apple.systempreferences:com.apple.preference.security?${pane}`])),
    available: () => fs.existsSync(UTILITY),
    wake: async (bundle) => void (await run("open", ["-g", "-j", "-b", bundle]).catch(() => "")),
  };
}

/**
 * Réseau local refusé : macOS coupe tout de suite la route (« No route to host »). Une machine joignable qui refuse
 * le port, ou qui répond, prouve que l'accès est donné. Un délai dépassé ne prouve rien.
 */
export function classifyConnectError(code: string | undefined): PermissionState {
  if (code === "ECONNREFUSED" || code === "ECONNRESET") return "granted";
  if (code === "EHOSTUNREACH" || code === "ENETUNREACH" || code === "EPERM") return "denied";
  return "unknown";
}

function tcpProbe(host: string, port: number): Promise<PermissionState> {
  return new Promise((resolve) => {
    const socket = net.connect({ host, port, timeout: 3_000 });
    socket.once("connect", () => (socket.destroy(), resolve("granted")));
    socket.once("timeout", () => (socket.destroy(), resolve("unknown")));
    socket.once("error", (e: NodeJS.ErrnoException) => (socket.destroy(), resolve(classifyConnectError(e.code))));
  });
}

/** Adresse de diffusion d'un sous-réseau IPv4 (adresse | ~masque). */
export function broadcastOf(address: string, netmask: string): string | null {
  const a = address.split(".").map(Number);
  const m = netmask.split(".").map(Number);
  if (a.length !== 4 || m.length !== 4 || [...a, ...m].some((n) => !Number.isInteger(n) || n < 0 || n > 255)) return null;
  return a.map((n, i) => (n | (~m[i] & 255)) & 255).join(".");
}

/** Sans tablette connectée : un octet en diffusion sur le sous-réseau (port 9, « discard »), rien n'y répond. */
function udpProbe(): Promise<PermissionState> {
  const target = Object.values(os.networkInterfaces())
    .flat()
    .map((i) => (i && i.family === "IPv4" && !i.internal ? broadcastOf(i.address, i.netmask) : null))
    .find((b) => b !== null);
  if (!target) return Promise.resolve("unknown");
  return new Promise((resolve) => {
    const socket = dgram.createSocket("udp4");
    const done = (state: PermissionState) => (socket.close(), resolve(state));
    socket.once("error", (e: NodeJS.ErrnoException) => done(classifyConnectError(e.code)));
    socket.bind(0, () => {
      socket.setBroadcast(true);
      socket.send(Buffer.from([0]), 9, target, (e: (Error & { code?: string }) | null) =>
        done(e ? classifyConnectError(e.code) : "granted"),
      );
    });
  });
}

/** Une instance garde en mémoire ce que l'utilitaire ne sait pas dire : demandes faites, et résultat du test réseau. */
export function createPermissions(deps: Deps, onRequested: () => void = () => automation.refresh()) {
  const asked = new Set<string>();
  let localNetwork: PermissionState = "not-asked";

  /** Ce que l'utilitaire ne distingue pas : faux = « pas demandée » avant la première demande, « refusée » après. */
  const falseState = (id: string): PermissionState => (asked.has(id) ? "denied" : "not-asked");

  async function list(): Promise<Permission[]> {
    if (!deps.supported) return [];
    const apps = OPTIONAL_APPS.filter((a) => deps.installed(a.folder));
    const status = await deps.status([SYSTEM_EVENTS, ...apps.map((a) => a.bundle)]);
    const unknown: PermissionState = "unknown";
    const automation = (bundle: string, id: string): PermissionState => {
      if (!status) return unknown;
      const value = status.automation?.[bundle];
      // « closed » : l'appli n'est pas ouverte, macOS ne répond pas ; la demande la lance.
      return value === "granted" || value === "denied" ? value : value === "not-asked" || value === "closed" ? falseState(id) : unknown;
    };
    const mac = (id: string, state: PermissionState, rest: Omit<Permission, "id" | "state" | "action">): Permission => ({
      id,
      state,
      // Refusée, macOS ne redemande plus (sauf Accessibilité et écran, dont la demande rouvre les Réglages) ; le disque n'a jamais de fenêtre.
      action:
        state === "granted"
          ? null
          : id === "full-disk" || (state === "denied" && id !== "accessibility" && id !== "screen")
            ? "settings"
            : "request",
      ...rest,
    });

    const out: Permission[] = [
      mac("accessibility", status ? (status.accessibility ? "granted" : falseState("accessibility")) : unknown, {
        title: "Accessibilité",
        why: "Pour placer les fenêtres, envoyer les raccourcis du deck et taper du texte depuis la tablette.",
        essential: true,
        appear: "macOS ouvre Réglages Système, Confidentialité et sécurité, Accessibilité : active l'interrupteur « Mate » dans la liste.",
      }),
      mac(`automation-${SYSTEM_EVENTS}`, automation(SYSTEM_EVENTS, `automation-${SYSTEM_EVENTS}`), {
        title: "Automatisation de System Events",
        why: "Pour lister les fenêtres et les déplacer, et lire l'état du Mac.",
        essential: true,
        appear: "macOS affiche une fenêtre : « Mate » souhaite contrôler « System Events ». Clique sur OK.",
      }),
      ...apps.map((a) =>
        mac(a.id, automation(a.bundle, a.id), {
          title: `Automatisation de ${a.name}`,
          why: a.why,
          essential: false,
          appear: `macOS affiche une fenêtre : « Mate » souhaite contrôler « ${a.name} ». Mate ouvre ${a.name} un instant en arrière-plan pour la poser. Clique sur OK.`,
        }),
      ),
    ];
    if (deps.darwinMajor >= 24) {
      out.push(
        mac("local-network", localNetwork, {
          title: "Réseau local",
          why: "Pour joindre la tablette sur le WiFi.",
          essential: false,
          appear:
            "macOS affiche une fenêtre : « Mate » souhaite trouver et se connecter aux appareils de votre réseau local. Clique sur Autoriser.",
        }),
      );
    }
    out.push(
      mac("full-disk", deps.fullDisk(), {
        title: "Accès complet au disque",
        why: "Pour afficher les notifications de ce Mac sur la tablette.",
        essential: false,
        appear: "macOS ouvre Réglages Système, Confidentialité et sécurité, Accès complet au disque : active « Mate » dans la liste.",
      }),
      mac("screen", status ? (status.screen ? "granted" : falseState("screen")) : unknown, {
        title: "Enregistrement de l'écran",
        why: "Pour lire le titre des fenêtres ouvertes et les montrer sur la tablette. Aucune image de l'écran n'est enregistrée.",
        essential: false,
        appear:
          "macOS affiche une fenêtre : « Mate » souhaite enregistrer l'écran. Clique sur Ouvrir les Réglages Système, puis active « Mate ».",
      }),
    );
    return out;
  }

  /** Déclenche la fenêtre de macOS (ou ouvre les Réglages) pour l'autorisation `id`. Rend tout de suite la main. */
  async function request(id: string) {
    const result = await requestOne(id);
    // L'accord se donne dans la fenêtre de macOS : les ordres AppleScript relisent l'état sans attendre les 10 s.
    if (result.ok) onRequested();
    return result;
  }

  async function requestOne(id: string): Promise<{ ok: boolean; action?: "request" | "settings"; error?: string; unknownId?: boolean }> {
    const item = (await list()).find((p) => p.id === id);
    if (!item) return { ok: false, error: "Autorisation inconnue", unknownId: true };
    if (item.action === null) return { ok: true };
    asked.add(id);
    const missing = { ok: false, error: "L'outil des autorisations est absent de ce compagnon : mets-le à jour." };
    if (id === "accessibility") {
      await deps.openAccessibility();
      return { ok: true, action: "request" };
    }
    if (id === "local-network") {
      if (item.action === "settings") await deps.openSettings(PANES.network);
      else localNetwork = await deps.probeLocalNetwork();
      return { ok: true, action: item.action };
    }
    if (id === "full-disk") {
      await deps.openSettings(PANES.disk);
      return { ok: true, action: "settings" };
    }
    if (id === "screen") {
      if (!(await deps.request(["request-screen"]))) return missing;
      await deps.openSettings(PANES.screen);
      return { ok: true, action: "request" };
    }
    // automation-<identifiant de l'appli> : l'identifiant vient de la liste ci-dessus, jamais de la requête.
    if (item.action === "settings") {
      await deps.openSettings(PANES.automation);
      return { ok: true, action: "settings" };
    }
    const bundle = id.slice("automation-".length);
    return (await deps.request(["request-automation", bundle])) ? { ok: true, action: "request" } : missing;
  }

  return { list, request };
}

/**
 * Filtre des ordres AppleScript : un ordre à une appli dont l'Automation n'est pas accordée fait surgir une fenêtre de
 * macOS. Les lectures de fond (lecture en cours, onglets, fenêtres, toutes les 5 s) en faisaient surgir en rafale, même
 * l'écran des autorisations passé. Seul « Autoriser » de cet écran demande ; le reste du compagnon passe par ici.
 * État lu sans rien demander, gardé 10 s (relu tout de suite après une demande : `refresh`). Appli fermée (macOS ne
 * répond pas) ou lecture en échec : dernier état connu, sinon refusé ; System Events, jamais connu, est lancé en
 * arrière-plan pour être lu. Hors appli portable (le terminal porte les autorisations) ou utilitaire absent : pas de filtre.
 */
export function createAutomationGate(deps: Pick<Deps, "supported" | "status" | "available" | "wake">, now = Date.now) {
  const bundles = [SYSTEM_EVENTS, ...OPTIONAL_APPS.map((a) => a.bundle)];
  const known = new Map<string, string>();
  let unfiltered = false;
  let readAt = -Infinity;
  let pending: Promise<void> | undefined;

  async function read() {
    let status = await deps.status(bundles);
    if (status && status.automation?.[SYSTEM_EVENTS] === "closed" && !known.has(SYSTEM_EVENTS)) {
      await deps.wake(SYSTEM_EVENTS);
      status = (await deps.status(bundles)) ?? status;
    }
    unfiltered = !status && !deps.available();
    for (const [bundle, value] of Object.entries(status?.automation ?? {})) if (value !== "closed") known.set(bundle, value);
    readAt = now();
  }

  const denied = (name: string) =>
    Object.assign(new Error(`Autorise Mate à piloter ${name} : page de l'ordinateur, Autorisations du Mac.`), { statusCode: 409 });

  // Les méthodes passent par `gate.allowed` : un test qui le remplace ferme ou ouvre la porte partout.
  const gate = {
    /** Vrai si le compagnon peut envoyer un ordre AppleScript à l'appli `bundle` sans fenêtre de macOS. */
    async allowed(bundle: string): Promise<boolean> {
      if (!deps.supported) return true;
      if (now() - readAt > 10_000) await (pending ??= read().finally(() => (pending = undefined)));
      return unfiltered || known.get(bundle) === "granted";
    },

    /** Noms AppleScript (« Spotify », « Google Chrome ») parmi `names` qui peuvent être pilotés. Nom inconnu : écarté. */
    async names(list: readonly string[]): Promise<string[]> {
      const out: string[] = [];
      for (const name of list) {
        const app = OPTIONAL_APPS.find((a) => a.folder === name);
        if (app ? await gate.allowed(app.bundle) : !deps.supported) out.push(name);
      }
      return out;
    },

    systemEvents: () => gate.allowed(SYSTEM_EVENTS),

    /** System Events (fenêtres, Ne pas déranger, mode sombre) : refusé = erreur dite sur la tablette, sans fenêtre de macOS. */
    async requireSystemEvents() {
      if (!(await gate.allowed(SYSTEM_EVENTS))) throw denied("System Events");
    },

    /** Spotify ou Musique (nom AppleScript) : refusé = erreur dite sur la tablette, sans fenêtre de macOS. */
    async requireApp(folder: string) {
      const app = OPTIONAL_APPS.find((a) => a.folder === folder);
      if (!app || !(await gate.allowed(app.bundle))) throw denied(app?.name ?? folder);
    },

    /** Prochain contrôle : l'état est relu, sans attendre les 10 s. */
    refresh() {
      readAt = -Infinity;
    },
  };
  return gate;
}

export const automation = createAutomationGate(defaultDeps());
