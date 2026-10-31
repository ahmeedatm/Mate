/**
 * Une lecture distante gardée `ttlMs` et jamais lancée deux fois en même temps (MR GitLab, PR GitHub, pipelines, runs).
 * Une erreur n'est pas gardée. `clear()` (fusion, autre compte) oublie le résultat et la lecture en cours : celle-ci rend
 * encore sa valeur à qui l'attendait, mais ne remplit plus le cache (elle date d'avant le changement).
 */
export function singleFlight<T>(ttlMs: number) {
  let cache: { at: number; value: T } | undefined;
  let inflight: Promise<T> | undefined;
  return {
    get(load: () => Promise<T>): Promise<T> {
      if (cache && Date.now() - cache.at < ttlMs) return Promise.resolve(cache.value);
      if (inflight) return inflight;
      const run: Promise<T> = load()
        .then((value) => {
          if (inflight === run) cache = { at: Date.now(), value };
          return value;
        })
        .finally(() => {
          if (inflight === run) inflight = undefined;
        });
      inflight = run;
      return run;
    },
    clear() {
      cache = undefined;
      inflight = undefined;
    },
  };
}
