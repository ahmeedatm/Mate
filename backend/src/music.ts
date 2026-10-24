import { canMedia, isInstalled, launchApp, mediaControl, mediaSessions, openTarget } from "./os.ts";

/**
 * Touches de la page Média : un site qui a son appli de bureau installée (Spotify, Deezer, Netflix…) ouvre
 * l'appli plutôt que le site. Pour la musique, la lecture démarre ensuite
 * toute seule : un onglet tout juste ouvert n'a rien à lire et le navigateur bloque le son sans geste de
 * l'utilisateur, alors l'ordinateur relance la lecture par les contrôles multimédias de Windows dès qu'elle apparaît.
 */
type Service = {
  host: RegExp;
  /** Noms possibles de l'appli dans le menu Démarrer. */
  apps: string[];
  music?: boolean;
  /** Adresse qui lance de la musique (au lieu de la page d'accueil, où rien ne joue). */
  playUrl?: string;
};

const SERVICES: Service[] = [
  { host: /(^|\.)spotify\.com$/i, apps: ["Spotify"], music: true },
  // « Titres aimés » : la page d'accueil de YouTube Music ne joue rien. Pas son appli, installée depuis un navigateur :
  // elle s'ouvrirait sur l'accueil ; le navigateur, connecté au même compte, ouvre directement l'adresse qui joue.
  { host: /^music\.youtube\.com$/i, apps: [], music: true, playUrl: "https://music.youtube.com/watch?list=LM" },
  { host: /(^|\.)deezer\.com$/i, apps: ["Deezer"], music: true },
  { host: /(^|\.)soundcloud\.com$/i, apps: [], music: true },
  { host: /(^|\.)netflix\.com$/i, apps: ["Netflix"] },
  { host: /(^|\.)primevideo\.com$/i, apps: ["Prime Video", "Amazon Prime Video for Windows"] },
  { host: /(^|\.)disneyplus\.com$/i, apps: ["Disney+"] },
  { host: /(^|\.)crunchyroll\.com$/i, apps: ["Crunchyroll"] },
];

const BROWSERS = /chrome|msedge|edge|firefox|opera|brave|vivaldi/i;

function serviceOf(url: string) {
  const host = URL.parse(url)?.host ?? "";
  return SERVICES.find((s) => s.host.test(host));
}

async function installedApp(service: Service | undefined) {
  for (const name of service?.apps ?? []) if (await isInstalled(name).catch(() => false)) return name;
  return undefined;
}

/** Appli qui s'ouvrira pour ce lien, si elle est installée (affichée sur la tablette). */
export async function linkApp(url: string) {
  return installedApp(serviceOf(url));
}

/** Ouvre un lien de la page Média : l'appli si elle est installée, sinon le site ; puis la musique démarre. */
export async function openMediaLink(url: string): Promise<{ opened: "app" | "site"; app?: string }> {
  const service = serviceOf(url);
  const app = await installedApp(service);
  const before = canMedia && service?.music ? await mediaSessions().catch(() => []) : [];
  if (app) await launchApp(app);
  else await openTarget(service?.playUrl ?? url);
  if (canMedia && service?.music) void startWhenReady(app, before.map((s) => `${s.app}\u0000${s.title}`));
  return app ? { opened: "app", app } : { opened: "site" };
}

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

/**
 * Pendant 30 s : la première session de l'appli (ou d'un navigateur, pour un site) qui apparaît en pause est relancée.
 * Navigateur : seulement un titre nouveau, pour ne pas reprendre une vidéo restée en pause dans un autre onglet.
 */
async function startWhenReady(app: string | undefined, before: string[]) {
  const target = app ? new RegExp(app.split(/\s+/)[0].replace(/[.*+?^${}()|[\]\\]/g, "\\$&"), "i") : BROWSERS;
  for (let i = 0; i < 20; i++) {
    await sleep(1500);
    const sessions = await mediaSessions().catch(() => []);
    const mine = sessions.filter((s) => target.test(s.app) || target.test(s.name));
    if (mine.some((s) => s.status === "playing")) return;
    const ready = mine.find((s) => s.status === "paused" && s.title && (app !== undefined || !before.includes(`${s.app}\u0000${s.title}`)));
    if (ready) {
      await mediaControl("play-pause", ready.app).catch(() => undefined);
      return;
    }
  }
}
