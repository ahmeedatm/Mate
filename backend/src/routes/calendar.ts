import type { FastifyInstance } from "fastify";
import { dayRange, getEvents, linkedCalendars, unlinkCalendar } from "../calendar.ts";
import { addEvent, deleteEvent, updateEvent, type EventInput } from "../events.ts";
import { autoJoinEnabled, joinMeeting, setAutoJoin } from "../meetings.ts";

export default async function calendarRoutes(app: FastifyInstance) {
  // --- Agenda

  app.get<{ Querystring: { days?: string } }>("/calendar", async (req) => {
    const days = Math.min(Math.max(Number.parseInt(req.query.days ?? "1", 10) || 1, 1), 14);
    const { source, events, error } = await getEvents(...dayRange(days));
    // Le lien Teams reste sur l'ordinateur : la tablette demande à rejoindre par identifiant.
    return { source, error, events: events.map(({ joinUrl, ...e }) => ({ ...e, joinable: !!joinUrl })) };
  });

  // Agenda personnel (src/events.ts) : rendez-vous notés depuis la tablette.
  const eventSchema = {
    type: "object",
    required: ["title", "startMs", "allDay"],
    additionalProperties: false,
    properties: {
      title: { type: "string", minLength: 1, maxLength: 200 },
      startMs: { type: "integer" },
      endMs: { type: "integer" },
      allDay: { type: "boolean" },
      remindBefore: { type: ["integer", "null"], minimum: 0, maximum: 1440 },
    },
  };

  app.post<{ Body: EventInput }>("/calendar/events", { schema: { body: eventSchema } }, async (req) => addEvent(req.body));

  app.put<{ Params: { id: string }; Body: EventInput }>(
    "/calendar/events/:id",
    { schema: { body: eventSchema } },
    async (req, reply) => (await updateEvent(req.params.id, req.body)) ?? reply.code(404).send({ error: "Événement introuvable" }),
  );

  app.delete<{ Params: { id: string } }>("/calendar/events/:id", async (req, reply) =>
    (await deleteEvent(req.params.id)) ? { ok: true } : reply.code(404).send({ error: "Événement introuvable" }),
  );

  app.post<{ Body: { id: string } }>(
    "/calendar/join",
    { schema: { body: { type: "object", required: ["id"], properties: { id: { type: "string", maxLength: 500 } } } } },
    async (req, reply) => {
      const joined = await joinMeeting(req.body.id);
      if (!joined) return reply.code(404).send({ error: "Cette réunion n'a pas de lien de visio" });
      // `kind` : la tablette n'ouvre la page Réunion du deck (raccourcis Teams) que pour Teams.
      return { ok: true, title: joined.event.title, kind: joined.kind ?? null };
    },
  );

  app.get("/calendar/autojoin", async () => ({ enabled: await autoJoinEnabled() }));

  app.put<{ Body: { enabled: boolean } }>(
    "/calendar/autojoin",
    { schema: { body: { type: "object", required: ["enabled"], properties: { enabled: { type: "boolean" } } } } },
    async (req) => {
      await setAutoJoin(req.body.enabled);
      return { enabled: req.body.enabled };
    },
  );

  /** Agendas reliés à cet ordinateur, pour la tablette (Accueil > Mes agendas) : voir et délier sans aller sur l'ordinateur. */
  app.get("/calendar/linked", async () => ({ calendars: await linkedCalendars() }));

  app.delete<{ Params: { id: string } }>("/calendar/linked/:id", async (req, reply) => {
    if (!(await unlinkCalendar(req.params.id))) return reply.code(404).send({ error: "Cet agenda n'est plus relié" });
    return { ok: true };
  });
}
