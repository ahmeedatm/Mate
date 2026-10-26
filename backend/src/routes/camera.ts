import type { FastifyInstance } from "fastify";
import { addViewer, CAMERA_STATUSES, cameraStatus, pushFrame, setCameraState, VIEWER_PAGE, type CameraStatus } from "../camera.ts";
import { config } from "../config.ts";
import { openTarget } from "../os.ts";

export default async function cameraRoutes(app: FastifyInstance) {
  // --- Visualiseur (caméra de la tablette au-dessus d'une feuille, image projetée depuis l'ordinateur)

  app.post<{ Body: Buffer }>("/camera/frame", { logLevel: "warn" }, async (req, reply) => {
    if (!Buffer.isBuffer(req.body) || req.body.length === 0) return reply.code(415).send({ error: "Corps image/jpeg attendu" });
    pushFrame(req.body);
    return { ok: true };
  });

  app.post<{ Body: { status: CameraStatus; rotation?: number } }>(
    "/camera/state",
    {
      logLevel: "warn",
      schema: {
        body: {
          type: "object",
          required: ["status"],
          additionalProperties: false,
          properties: { status: { enum: CAMERA_STATUSES }, rotation: { enum: [0, 90, 180, 270] } },
        },
      },
    },
    async (req) => {
      setCameraState(req.body.status, req.body.rotation);
      return cameraStatus();
    },
  );

  app.get("/camera/status", { logLevel: "warn" }, async () => cameraStatus());

  app.get("/camera", async (_req, reply) => reply.type("text/html; charset=utf-8").send(VIEWER_PAGE));

  app.get("/camera/stream", async (_req, reply) => {
    reply.hijack();
    addViewer(reply.raw);
  });

  /** Ouvre la page du visualiseur dans le navigateur de l'ordinateur (à envoyer ensuite sur le vidéoprojecteur). */
  app.post("/camera/open", async () => {
    await openTarget(`http://127.0.0.1:${config.port}/camera`);
    return { ok: true };
  });
}
