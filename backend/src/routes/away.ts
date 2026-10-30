import type { FastifyInstance } from "fastify";
import { fileStore, resumeAway, startAway, type AwayDeps } from "../away.ts";
import { controlOpenWindow, listOpenWindows } from "../os.ts";

/** Absence de la tablette (src/away.ts). */
export default async function awayRoutes(app: FastifyInstance) {
  const deps: AwayDeps = { list: listOpenWindows, control: controlOpenWindow, ...fileStore() };

  // Une absence à la fois : les fenêtres se remettent l'une après l'autre.
  let queue: Promise<unknown> = Promise.resolve();
  function oneAtATime<T>(task: () => Promise<T>): Promise<T> {
    const run = queue.then(task, task);
    queue = run.catch(() => {});
    return run;
  }

  // Lecture seule : y a-t-il une absence gardée ? (la sanity ne remet jamais de fenêtre)
  app.get("/away", async () => {
    const saved = await deps.load();
    return { saved: saved !== null, count: saved?.windows.length ?? 0 };
  });

  app.post("/away/start", async () => oneAtATime(() => startAway(deps)));

  app.post("/away/resume", async () => oneAtATime(() => resumeAway(deps)));
}
