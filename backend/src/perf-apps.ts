/**
 * Applis qui consomment le plus sur l'ordinateur, pour le widget Performances de la tablette. Module pur, indépendant du
 * système : la liste des processus (`ProcessRaw`, processeur en % d'un cœur, mémoire en octets) est injectée par
 * `AppsSources` (Windows : compteurs PDH `Process(*)` ; Mac : `ps`). Les processus d'une même appli sont regroupés
 * (tous les onglets de Chrome = un seul Chrome). Une appli « abuse » quand elle prend plus de la moitié du processeur
 * pendant 30 s d'affilée, ou plus d'un quart de la mémoire : elle est alors marquée (`warn`) et passe en tête.
 */
export type ProcessRaw = { name: string; cpu: number; memBytes: number };
/**
 * `cpu` : part du processeur de toute la machine, entier de 0 à 100 ; `memMb` : mémoire en Mo entiers ; `memPercent` :
 * part de la mémoire de la machine, entier. `warn` : ce qu'elle prend en trop ; `hogSeconds` : depuis combien de
 * secondes elle prend trop de processeur (avec `warn: "cpu"` seulement).
 */
export type AppPerf = { name: string; cpu: number; memMb: number; memPercent: number; warn?: "cpu" | "ram"; hogSeconds?: number };
/** `apps` : `null` quand la liste des processus est illisible (jamais une liste vide inventée). */
export type AppsPerf = { apps: AppPerf[] | null };

export type AppsSources = {
  /** `null` : pas de lecture possible pour ce relevé. */
  processes: () => Promise<ProcessRaw[] | null>;
  /** Nombre de cœurs logiques : le processeur d'un processus est donné en % d'un cœur. */
  cores: () => number;
  totalMem: () => number;
  now: () => number;
};

/** Nombre d'applis renvoyées. */
export const APP_COUNT = 5;
/** Part du processeur au-delà de laquelle une appli prend trop… */
export const CPU_HOG = 50;
/** …pendant au moins cette durée d'affilée (une compilation ou un chargement de page passe sans alerte). */
export const CPU_HOG_MS = 30_000;
/** Part de la mémoire au-delà de laquelle une appli prend trop, signalée tout de suite. */
export const RAM_HOG = 25;
/** La tablette relit toutes les 2 s : en dessous, la dernière liste resert. */
const CACHE_MS = 1500;
/** Au-delà sans relevé (widget fermé), on ne sait plus si l'appli a pris trop tout ce temps : le compte repart. */
const STALE_MS = 10_000;

const MB = 1024 * 1024;

/** Totaux et processus du système qui ne sont pas des applis (la compression mémoire de Windows, masquée aussi par le Gestionnaire des tâches). */
const IGNORED = new Set(["_total", "idle", "system idle process", "memory compression"]);

// shortcut: quelques noms d'exe Windows courants seulement ; les autres gardent leur nom d'exe, à étendre au besoin.
const FRIENDLY: Record<string, string> = {
  chrome: "Google Chrome",
  msedge: "Microsoft Edge",
  msedgewebview2: "Microsoft Edge WebView",
  firefox: "Firefox",
  explorer: "Explorateur",
  steamwebhelper: "Steam",
  svchost: "Services Windows",
  "ms-teams": "Teams",
};

/**
 * Nom d'appli d'un processus : instance Windows (`chrome#3`), exe (`Code.exe`), aide Mac (`Slack Helper (GPU)`) →
 * nom de l'appli ; `null` pour les totaux et le processus inactif.
 */
export function appName(raw: string): string | null {
  const base = raw
    .trim()
    .replace(/#\d+$/, "")
    .replace(/\.exe$/i, "")
    .replace(/ Helper( \(.*\))?$/, "")
    .trim();
  if (!base || IGNORED.has(base.toLowerCase())) return null;
  const friendly = FRIENDLY[base.toLowerCase()];
  return friendly ?? base[0].toUpperCase() + base.slice(1);
}

const count = (n: number) => (Number.isFinite(n) && n > 0 ? n : 0);

/** Processus regroupés par appli, du plus au moins gourmand (la plus forte part, processeur ou mémoire). */
export function groupApps(list: ProcessRaw[], cores: number, totalMem: number): AppPerf[] {
  const byApp = new Map<string, { cpu: number; mem: number }>();
  for (const p of list) {
    const name = appName(p.name);
    if (!name) continue;
    const app = byApp.get(name) ?? { cpu: 0, mem: 0 };
    app.cpu += count(p.cpu);
    app.mem += count(p.memBytes);
    byApp.set(name, app);
  }
  const apps = [...byApp].map(([name, { cpu, mem }]) => ({
    name,
    cpu: Math.min(100, Math.round(cpu / Math.max(1, cores))),
    memMb: Math.round(mem / MB),
    memPercent: totalMem > 0 ? Math.min(100, Math.round((mem / totalMem) * 100)) : 0,
  }));
  // À égalité de pourcentage, la plus grosse en mémoire d'abord.
  return apps.sort((a, b) => Math.max(b.cpu, b.memPercent) - Math.max(a.cpu, a.memPercent) || b.memMb - a.memMb);
}

/** Renvoie la lecture à demander à chaque requête : au plus une liste par 1,5 s, appels simultanés fusionnés. */
export function appsSampler(sources: AppsSources): () => Promise<AppsPerf> {
  /** Depuis quand chaque appli prend trop de processeur (horloge de `now`). */
  const hogSince = new Map<string, number>();
  let lastAt: number | undefined;
  let cached: { at: number; value: AppsPerf } | undefined;
  let inflight: Promise<AppsPerf> | undefined;

  async function measure(): Promise<AppsPerf> {
    const raw = await sources.processes().catch(() => null);
    const now = sources.now();
    if (lastAt !== undefined && now - lastAt > STALE_MS) hogSince.clear();
    lastAt = now;
    if (!raw) {
      cached = { at: now, value: { apps: null } };
      return cached.value;
    }
    const apps = groupApps(raw, sources.cores(), sources.totalMem());
    for (const name of [...hogSince.keys()]) if (!apps.some((a) => a.name === name && a.cpu >= CPU_HOG)) hogSince.delete(name);
    for (const app of apps) {
      if (app.cpu >= CPU_HOG) {
        const since = hogSince.get(app.name) ?? now;
        hogSince.set(app.name, since);
        if (now - since >= CPU_HOG_MS) {
          app.warn = "cpu";
          app.hogSeconds = Math.floor((now - since) / 1000);
          continue;
        }
      }
      if (app.memPercent >= RAM_HOG) app.warn = "ram";
    }
    // Les applis signalées d'abord (tri stable : l'ordre de gourmandise est gardé entre elles).
    const top = [...apps.filter((a) => a.warn), ...apps.filter((a) => !a.warn)].slice(0, APP_COUNT);
    cached = { at: now, value: { apps: top } };
    return cached.value;
  }

  return async () => {
    if (cached && sources.now() - cached.at < CACHE_MS) return cached.value;
    inflight ??= measure().finally(() => {
      inflight = undefined;
    });
    return inflight;
  };
}
