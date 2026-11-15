import type { FastifyInstance } from "fastify";
import { monitors, setMonitor } from "../os.ts";
import { MONITOR_CONTROLS, type Monitor, type MonitorChange } from "../screens.ts";
import { singleFlight } from "../single-flight.ts";

/**
 * Widget Écrans (Windows) : écrans branchés et leurs réglages. DDC/CI est lent (une à deux secondes au premier appel,
 * chaîne de capacités) : des lectures qui se chevauchent en partagent une. Un mode n'est accepté que s'il est dans la
 * liste annoncée pour cet écran, pour ne jamais envoyer un mode qui l'éteindrait.
 */
export default async function monitorRoutes(app: FastifyInstance) {
  const read = singleFlight<Monitor[]>(0);

  app.get("/monitors", { logLevel: "warn" }, async () => ({ monitors: await read.get(() => monitors()) }));

  const size = { type: "integer", minimum: 1, maximum: 20000 } as const;
  app.post<{ Body: MonitorChange }>(
    "/monitors",
    {
      schema: {
        body: {
          type: "object",
          additionalProperties: false,
          required: ["id"],
          properties: {
            id: { type: "string", pattern: String.raw`^\\\\\.\\DISPLAY\d{1,2}$` },
            control: { type: "string", enum: [...MONITOR_CONTROLS, "preset"] },
            value: { type: "integer", minimum: 0, maximum: 65535 },
            width: size,
            height: size,
            hz: { type: "integer", minimum: 1, maximum: 1000 },
          },
          dependencies: {
            control: ["value"],
            value: ["control"],
            width: ["height", "hz"],
            height: ["width", "hz"],
            hz: ["width", "height"],
          },
        },
      },
    },
    async (req, reply) => {
      const change = req.body;
      if (change.control === undefined && change.width === undefined) {
        return reply.code(400).send({ error: "Rien à changer" });
      }
      if (change.width !== undefined) {
        const screen = (await monitors()).find((m) => m.id === change.id);
        if (!screen) return reply.code(404).send({ error: "Écran introuvable : il a peut-être été débranché." });
        const offered = screen.modes.some((m) => m.width === change.width && m.height === change.height && m.hz.includes(change.hz!));
        if (!offered) return reply.code(400).send({ error: "Mode non proposé par cet écran" });
      }
      return { monitors: await setMonitor(change) };
    },
  );
}
