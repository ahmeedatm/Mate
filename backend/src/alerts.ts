/**
 * Alerte maison : un capteur de Home Assistant (porte, fuite, lessive…) change d'état, la tablette affiche une bannière
 * qui reste jusqu'à un toucher et la lit à voix haute (ordre `alert` du canal /tablet). Réglé par HA_ALERTS dans le .env :
 * `binary_sensor.porte` (alerte quand il passe à « on »), `binary_sensor.lessive:off` (quand il passe à « off»), et
 * `=texte` pour dire autre chose que le nom du capteur. Le 1er état lu sert de repère : rien n'est annoncé au démarrage.
 */

export const ALERT_ENTITY_ID = /^binary_sensor\.[a-z0-9_]+$/;

export type AlertSpec = { id: string; when: "on" | "off"; text?: string };

/** Entrées valides de HA_ALERTS, dans l'ordre, sans doublon ; un identifiant mal écrit est ignoré. */
export function parseAlertSpecs(raw: string | undefined): AlertSpec[] {
  const specs = new Map<string, AlertSpec>();
  for (const part of (raw ?? "").split(",")) {
    const [head, ...rest] = part.split("=");
    const [id, when] = head.trim().split(":");
    if (!ALERT_ENTITY_ID.test(id) || (when !== undefined && when !== "on" && when !== "off") || specs.has(id)) continue;
    specs.set(id, { id, when: when === "off" ? "off" : "on", text: rest.join("=").trim() || undefined });
  }
  return [...specs.values()];
}

export type AlertStates = Record<string, { state: string; name?: string }>;

export type AlertDeps = {
  specs: AlertSpec[];
  /** État des capteurs de `specs` ; lève si Home Assistant ne répond pas. */
  read: () => Promise<AlertStates>;
  intervalMs?: number;
};

/**
 * Démarre la veille ; renvoie la fonction qui l'arrête. `notify` rend vrai quand la tablette a reçu l'alerte : sinon elle
 * est renvoyée au tour suivant (tablette hors ligne, ordinateur verrouillé). Lecture ratée : l'état d'avant est gardé.
 */
export function startAlertWatch(notify: (text: string) => Promise<boolean>, deps: AlertDeps): () => void {
  const intervalMs = deps.intervalMs ?? 5000;
  /** Dernier état connu (« unavailable » et « unknown » ne comptent pas), pour ne détecter que les passages. */
  const last = new Map<string, string>();
  const waiting: string[] = [];
  let stopped = false;
  let timer: NodeJS.Timeout | undefined;

  const tick = async () => {
    try {
      const states = await deps.read();
      for (const spec of deps.specs) {
        const now = states[spec.id];
        if (!now || now.state === "unavailable" || now.state === "unknown") continue;
        if (last.get(spec.id) !== undefined && last.get(spec.id) !== spec.when && now.state === spec.when) {
          waiting.push(spec.text ?? (now.name || spec.id));
        }
        last.set(spec.id, now.state);
      }
    } catch {
      // Home Assistant injoignable un instant : on garde l'état et on relit au tour suivant.
    }
    // Une seule alerte à la fois : la suivante part quand la tablette a reçu la précédente.
    while (waiting.length > 0 && !stopped && (await notify(waiting[0]))) waiting.shift();
    if (!stopped) timer = setTimeout(() => void tick(), intervalMs);
  };

  void tick();
  return () => {
    stopped = true;
    clearTimeout(timer);
  };
}
