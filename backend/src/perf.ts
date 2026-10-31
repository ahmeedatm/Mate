import os from "node:os";

/**
 * Mesure des performances de l'ordinateur pour le widget de la tablette : processeur, mémoire, carte graphique,
 * disque, réseau. Module pur, indépendant du système : les lectures (cœurs, activité du processeur, mémoire, GPU,
 * fréquence, disque, réseau, horloge) sont
 * injectées par `PerfSources`, ce qui permet de le tester sans matériel. Les mesures sont mises en cache 1 s : la
 * tablette peut interroger plus souvent sans multiplier les lectures sur le PC.
 */
export type GpuPerf = {
  name: string;
  usage: number;
  dedicated: boolean;
  tempC?: number;
  fanRpm?: number;
  /** Mémoire vidéo en Mo entiers (carte dédiée seulement). */
  vramUsedMb?: number;
  vramTotalMb?: number;
};
/** `used` et `total` en Go (une décimale), `percent` entier. */
export type RamUsage = { used: number; total: number; percent: number };
/** Go à une décimale ; `activity` : temps d'activité du disque, entier de 0 à 100 (Windows seulement). */
export type DiskPerf = { freeGb: number; totalGb: number; activity?: number };
/** Débit du réseau en Ko/s entiers. */
export type NetPerf = { upKBps: number; downKBps: number };
/** Les champs facultatifs sont absents quand la lecture est impossible, jamais inventés. */
export type Perf = { cpu: number; cpuMhz?: number; ram: RamUsage; gpu?: GpuPerf; disk?: DiskPerf; net?: NetPerf };
export type DiskRaw = { freeBytes: number; totalBytes: number; activity?: number | null };
export type CpuTimes = { idle: number; total: number };

export type PerfSources = {
  cpus: () => os.CpuInfo[];
  /**
   * Activité du processeur lue par le système (Windows : celle du Gestionnaire des tâches, rapportée à la fréquence
   * nominale) ; `null` : pas de lecteur. Absente, illisible ou trop lente, l'écart des temps de `cpus` la remplace.
   */
  cpuPercent: (() => Promise<number | null>) | null;
  memory: () => Promise<{ total: number; available: number }>;
  /** `null` : pas de lecteur GPU sur cette machine. */
  gpu: (() => Promise<GpuPerf | null>) | null;
  /** Fréquence courante du processeur en MHz ; absente ou `null` : illisible. */
  cpuMhz?: () => Promise<number | null>;
  disk?: () => Promise<DiskRaw | null>;
  net?: () => Promise<NetPerf | null>;
  now: () => number;
};

const GIB = 1024 ** 3;
const CACHE_MS = 1000;
/** Écart entre les deux lectures CPU du tout premier appel : un pourcentage se mesure sur une durée. */
const FIRST_SAMPLE_MS = 250;
/** Au-delà, la dernière lecture CPU ne sert plus de point de départ (widget fermé un moment). */
const STALE_MS = 5 * CACHE_MS;
/** Attente maximale du lecteur GPU : un pilote occupé ou qui démarre ne doit pas retarder le processeur et la RAM. */
export const GPU_WAIT_MS = 1500;
/** Même attente pour l'activité du processeur lue par le pilote : au-delà, l'écart des temps des cœurs la remplace. */
export const CPU_WAIT_MS = 1500;
/** Même attente pour la fréquence, le disque et le réseau : au-delà, le champ est omis pour ce relevé. */
export const EXTRA_WAIT_MS = 1500;

/** Temps cumulés de tous les cœurs (inactif / total). */
export function cpuTimes(cpus: os.CpuInfo[] = os.cpus()): CpuTimes {
  let idle = 0;
  let total = 0;
  for (const { times } of cpus) {
    idle += times.idle;
    total += times.user + times.nice + times.sys + times.idle + times.irq;
  }
  return { idle, total };
}

/** Activité du processeur entre deux lectures, entier de 0 à 100 ; sans écart de temps, 0. */
export function cpuPercent(prev: CpuTimes, next: CpuTimes): number {
  const total = next.total - prev.total;
  if (total <= 0) return 0;
  const busy = (total - (next.idle - prev.idle)) / total;
  return Math.min(100, Math.max(0, Math.round(busy * 100)));
}

const oneDecimal = (n: number) => Math.round(n * 10) / 10;
const isCount = (n: unknown): n is number => typeof n === "number" && Number.isFinite(n) && n >= 0;

function normalizeCpuMhz(mhz: number): number | undefined {
  return Number.isFinite(mhz) && mhz > 0 ? Math.round(mhz) : undefined;
}

function normalizeDisk(raw: DiskRaw): DiskPerf | undefined {
  if (!isCount(raw.freeBytes) || !isCount(raw.totalBytes) || raw.totalBytes <= 0 || raw.freeBytes > raw.totalBytes) return undefined;
  const disk: DiskPerf = { freeGb: oneDecimal(raw.freeBytes / GIB), totalGb: oneDecimal(raw.totalBytes / GIB) };
  if (typeof raw.activity === "number" && Number.isFinite(raw.activity))
    disk.activity = Math.min(100, Math.max(0, Math.round(raw.activity)));
  return disk;
}

function normalizeNet(raw: NetPerf): NetPerf | undefined {
  return isCount(raw.upKBps) && isCount(raw.downKBps) ? { upKBps: Math.round(raw.upKBps), downKBps: Math.round(raw.downKBps) } : undefined;
}

export function ramUsage(totalBytes: number, availableBytes: number): RamUsage {
  const usedBytes = Math.max(0, totalBytes - availableBytes);
  return {
    used: oneDecimal(usedBytes / GIB),
    total: oneDecimal(totalBytes / GIB),
    percent: totalBytes > 0 ? Math.round((usedBytes / totalBytes) * 100) : 0,
  };
}

/** Usage arrondi et borné ; une température hors 10–150 °C ou une vitesse négative sont des lectures ratées. */
export function normalizeGpu(raw: GpuPerf): GpuPerf {
  const gpu: GpuPerf = {
    name: raw.name,
    usage: Math.min(100, Math.max(0, Math.round(raw.usage))),
    dedicated: raw.dedicated,
  };
  if (raw.tempC !== undefined && Number.isFinite(raw.tempC) && raw.tempC >= 10 && raw.tempC <= 150) gpu.tempC = raw.tempC;
  if (raw.fanRpm !== undefined && Number.isFinite(raw.fanRpm) && raw.fanRpm >= 0) gpu.fanRpm = raw.fanRpm;
  const total = isCount(raw.vramTotalMb) && raw.vramTotalMb > 0 ? Math.round(raw.vramTotalMb) : undefined;
  if (total !== undefined) gpu.vramTotalMb = total;
  // Utilisée > totale : lecture incohérente (autre carte, mémoire partagée), omise plutôt que corrigée.
  if (isCount(raw.vramUsedMb) && (total === undefined || Math.round(raw.vramUsedMb) <= total)) gpu.vramUsedMb = Math.round(raw.vramUsedMb);
  return gpu;
}

/**
 * Beaucoup de pilotes renvoient 0 tpm pour un ventilateur qu'ils ne savent pas lire : tant qu'une vitesse positive
 * n'a pas été vue pour cette carte, un 0 est écarté ; après, il est gardé (ventilateur à l'arrêt).
 */
export class FanMemory {
  private seen = new Set<string>();

  apply(gpu: GpuPerf): GpuPerf {
    if (gpu.fanRpm === undefined) return gpu;
    if (gpu.fanRpm > 0) {
      this.seen.add(gpu.name);
      return gpu;
    }
    if (this.seen.has(gpu.name)) return gpu;
    const { fanRpm: _unreadable, ...rest } = gpu;
    return rest;
  }
}

const sleep = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms));

/** La valeur de `promise`, ou `null` si elle n'arrive pas dans les `ms` (la promesse continue, sans être attendue). */
function within<T>(promise: Promise<T>, ms: number): Promise<T | null> {
  let timer: NodeJS.Timeout | undefined;
  const late = new Promise<null>((resolve) => {
    timer = setTimeout(() => resolve(null), ms);
  });
  return Promise.race([promise, late]).finally(() => clearTimeout(timer));
}

/**
 * Lecture lente bornée à `ms` (`null` si elle n'arrive pas à temps) ; encore en cours (pilote lent), elle est
 * réattendue au relevé suivant plutôt que redemandée.
 */
function bounded<T>(read: () => Promise<T>, ms: number): () => Promise<T | null> {
  let pending: Promise<T> | undefined;
  return () => {
    pending ??= Promise.resolve()
      .then(read)
      .finally(() => {
        pending = undefined;
      });
    return within(pending, ms);
  };
}

/** Lecture facultative bornée : `undefined` sans lecteur, en échec, trop lente ou illisible (après `normalize`). */
function optional<T, R>(read: (() => Promise<T | null>) | undefined, normalize: (raw: T) => R | undefined): () => Promise<R | undefined> {
  const slow = read && bounded(read, EXTRA_WAIT_MS);
  return async () => {
    if (!slow) return undefined;
    try {
      const raw = await slow();
      return raw === null || raw === undefined ? undefined : normalize(raw);
    } catch {
      return undefined;
    }
  };
}

/** Renvoie la lecture à demander à chaque requête : mesure au plus une fois par seconde, appels simultanés fusionnés. */
export function perfSampler(sources: PerfSources): () => Promise<Perf> {
  const fans = new FanMemory();
  let lastCpu: { times: CpuTimes; at: number } | undefined;
  let cached: { at: number; perf: Perf } | undefined;
  let inflight: Promise<Perf> | undefined;
  const systemCpuRead = sources.cpuPercent && bounded(sources.cpuPercent, CPU_WAIT_MS);
  const gpuRead = sources.gpu && bounded(sources.gpu, GPU_WAIT_MS);
  const readMhz = optional(sources.cpuMhz, normalizeCpuMhz);
  const readDisk = optional(sources.disk, normalizeDisk);
  const readNet = optional(sources.net, normalizeNet);

  async function cpuFromTimes(): Promise<number> {
    // Une lecture ancienne donnerait la moyenne de toute la pause, pas l'activité actuelle : on repart d'une fenêtre courte.
    let prev = lastCpu && sources.now() - lastCpu.at <= STALE_MS ? lastCpu.times : undefined;
    if (!prev) {
      prev = cpuTimes(sources.cpus());
      await sleep(FIRST_SAMPLE_MS);
    }
    const next = cpuTimes(sources.cpus());
    lastCpu = { times: next, at: sources.now() };
    return cpuPercent(prev, next);
  }

  /** Activité lue par le système, entière et bornée ; `null` sans lecteur, en échec, trop lente ou illisible. */
  async function systemCpu(): Promise<number | null> {
    if (!systemCpuRead) return null;
    try {
      const value = await systemCpuRead();
      return typeof value === "number" && Number.isFinite(value) ? Math.min(100, Math.max(0, Math.round(value))) : null;
    } catch {
      return null;
    }
  }

  async function readCpu(): Promise<number> {
    // Temps des cœurs relevés à chaque fois : si le pilote lâche, le repli part d'une base récente.
    const [fromTimes, fromSystem] = await Promise.all([cpuFromTimes(), systemCpu()]);
    return fromSystem ?? fromTimes;
  }

  async function readGpu(): Promise<GpuPerf | undefined> {
    if (!gpuRead) return undefined;
    try {
      // Trop lente, la carte graphique est omise cette fois-ci : le processeur et la RAM partent sans elle.
      const raw = await gpuRead();
      // Un usage illisible (NaN) deviendrait « null » côté tablette : mieux vaut pas de GPU qu'un usage inventé.
      return raw && Number.isFinite(raw.usage) ? fans.apply(normalizeGpu(raw)) : undefined;
    } catch {
      return undefined;
    }
  }

  async function measure(): Promise<Perf> {
    const [cpu, memory, gpu, cpuMhz, disk, net] = await Promise.all([
      readCpu(),
      sources.memory(),
      readGpu(),
      readMhz(),
      readDisk(),
      readNet(),
    ]);
    const perf: Perf = { cpu, ram: ramUsage(memory.total, memory.available) };
    if (cpuMhz !== undefined) perf.cpuMhz = cpuMhz;
    if (gpu) perf.gpu = gpu;
    if (disk) perf.disk = disk;
    if (net) perf.net = net;
    cached = { at: sources.now(), perf };
    return perf;
  }

  return async () => {
    if (cached && sources.now() - cached.at < CACHE_MS) return cached.perf;
    inflight ??= measure().finally(() => {
      inflight = undefined;
    });
    return inflight;
  };
}
