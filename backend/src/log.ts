/**
 * Journal partagé : le serveur y branche le logger Fastify (`setLogger`), les modules s'en servent sans le connaître.
 * Avant ça (tests, serveur MCP), les messages vont sur la sortie d'erreur.
 */
type Sink = { warn(msg: string): void };
let sink: Sink = { warn: (msg) => console.warn(msg) };

export const setLogger = (next: Sink) => {
  sink = next;
};

export const warn = (msg: string) => sink.warn(msg);

const THROTTLE_MS = 60_000;
const lastLogged = new Map<string, number>();

/** Échec d'une tâche de fond : une trace par minute et par tâche au plus (un tick de 2 s ne doit pas inonder le journal). */
export function logFailure(what: string, e: unknown) {
  const now = Date.now();
  if (now - (lastLogged.get(what) ?? 0) < THROTTLE_MS) return;
  lastLogged.set(what, now);
  warn(`${what} : ${e instanceof Error ? e.message : String(e)}`);
}
