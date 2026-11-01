import type { FastifyInstance } from "fastify";
import { runAction } from "../deck.ts";
import { addTask, deleteTask, listRecurring, listTasks, setTaskDone, taskUrl } from "../tasks.ts";

export default async function taskRoutes(app: FastifyInstance) {
  // --- Tâches (quotidiennes dans config/tasks.json, ponctuelles ajoutées par la tablette ou Claude)

  app.get<{ Querystring: { refresh?: string } }>("/tasks", async (req) => ({ tasks: await listTasks(req.query.refresh === "1") }));

  app.get("/tasks/recurring", async () => ({ tasks: await listRecurring() }));

  /** `days` : récurrente ces jours-là (1 = lundi) ; `remindAt` : rappel parlé à cette heure ("HH:MM") ; `people` : corvée à tour de rôle (récurrente). */
  app.post<{ Body: { label: string; remindAt?: string; days?: number[]; people?: string[] } }>(
    "/tasks",
    {
      schema: {
        body: {
          type: "object",
          required: ["label"],
          properties: {
            label: { type: "string", minLength: 1, maxLength: 200 },
            remindAt: { type: "string", pattern: "^([01][0-9]|2[0-3]):[0-5][0-9]$" },
            days: { type: "array", maxItems: 7, items: { type: "integer", minimum: 1, maximum: 7 } },
            people: { type: "array", maxItems: 10, items: { type: "string", minLength: 1, maxLength: 40 } },
          },
        },
      },
    },
    async (req) => addTask(req.body.label, { remindAt: req.body.remindAt, days: req.body.days, people: req.body.people }),
  );

  app.post<{ Params: { id: string }; Body: { done: boolean } }>(
    "/tasks/:id/done",
    { schema: { body: { type: "object", required: ["done"], properties: { done: { type: "boolean" } } } } },
    async (req, reply) =>
      (await setTaskDone(req.params.id, req.body.done)) ? { ok: true } : reply.code(404).send({ error: "Tâche inconnue" }),
  );

  app.delete<{ Params: { id: string } }>("/tasks/:id", async (req, reply) =>
    (await deleteTask(req.params.id))
      ? { ok: true }
      : reply.code(404).send({ error: "Tâche inconnue (ou définie dans le fichier de l'ordinateur)" }),
  );

  app.post<{ Params: { id: string } }>("/tasks/:id/open", async (req, reply) => {
    const url = await taskUrl(req.params.id);
    if (!url) return reply.code(404).send({ error: "Pas de lien pour cette tâche" });
    await runAction({ type: "url", url });
    return { ok: true };
  });
}
