import type { FastifyInstance } from "fastify";
import { findTile, isPush } from "../deck.ts";
import { addSchedule, deleteSchedule, listSchedule, setScheduleEnabled, type Schedule, type ScheduleInput } from "../schedule.ts";

const DAY_NAMES = ["lun", "mar", "mer", "jeu", "ven", "sam", "dim"];

/** « Lun–ven · 18:00 », « Tous les jours · 18:00 », « Lun, mer · 18:00 », « Toutes les 45 min ». */
function describe(item: Schedule): string {
  if (item.everyMinutes) return `Toutes les ${item.everyMinutes} min`;
  const days = item.days ?? [1, 2, 3, 4, 5, 6, 7];
  const key = days.join("");
  const text =
    key === "1234567"
      ? "Tous les jours"
      : key === "12345"
        ? "Lun–ven"
        : key === "67"
          ? "Week-end"
          : days
              .map((d) => DAY_NAMES[d - 1])
              .join(", ")
              .replace(/^./, (c) => c.toUpperCase());
  return `${text} · ${item.at}`;
}

async function view(item: Schedule) {
  const tile = await findTile(item.tileId);
  return { id: item.id, tileId: item.tileId, label: tile?.label ?? null, enabled: item.enabled, schedule: describe(item) };
}

export default async function scheduleRoutes(app: FastifyInstance) {
  // --- Programmations : une touche du Deck lancée par l'ordinateur à heure fixe ou à intervalle (src/schedule.ts)

  app.get("/schedule", async () => ({ items: await Promise.all((await listSchedule()).map(view)) }));

  app.post<{ Body: ScheduleInput }>(
    "/schedule",
    {
      schema: {
        body: {
          type: "object",
          required: ["tileId"],
          additionalProperties: false,
          properties: {
            tileId: { type: "string", minLength: 1, maxLength: 20 },
            at: { type: "string", pattern: "^([01][0-9]|2[0-3]):[0-5][0-9]$" },
            days: { type: "array", maxItems: 7, items: { type: "integer", minimum: 1, maximum: 7 } },
            everyMinutes: { type: "integer", minimum: 5, maximum: 1440 },
          },
        },
      },
    },
    async (req, reply) => {
      const { tileId, at, everyMinutes, days } = req.body;
      if (Boolean(at) === Boolean(everyMinutes)) return reply.code(400).send({ error: "Une heure fixe ou un intervalle, pas les deux" });
      if (days && !at) return reply.code(400).send({ error: "Les jours vont avec une heure fixe" });
      const tile = await findTile(tileId);
      if (!tile) return reply.code(404).send({ error: "Touche inconnue" });
      if (isPush(tile)) return reply.code(409).send({ error: "Cette touche se maintient : elle ne se programme pas" });
      return view(await addSchedule({ tileId, at, everyMinutes, days }));
    },
  );

  app.post<{ Params: { id: string }; Body: { enabled: boolean } }>(
    "/schedule/:id/enabled",
    { schema: { body: { type: "object", required: ["enabled"], properties: { enabled: { type: "boolean" } } } } },
    async (req, reply) =>
      (await setScheduleEnabled(req.params.id, req.body.enabled))
        ? { ok: true }
        : reply.code(404).send({ error: "Programmation inconnue" }),
  );

  app.delete<{ Params: { id: string } }>("/schedule/:id", async (req, reply) =>
    (await deleteSchedule(req.params.id)) ? { ok: true } : reply.code(404).send({ error: "Programmation inconnue" }),
  );
}
