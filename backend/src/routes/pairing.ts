import type { FastifyInstance } from "fastify";
import { accessibility } from "../os.ts";
import { confirmPairing, identity, requestPairing } from "../pairing.ts";

/** Routes sans jeton : identité de l'ordinateur et appairage par code. `authorized` : contrôle du jeton de server.ts. */
export default async function pairingRoutes(app: FastifyInstance, opts: { authorized: (header: string | undefined) => boolean }) {
  /** `auth` : le jeton envoyé est le bon (la tablette sait s'il faut refaire l'appairage). */
  app.get("/health", async (req) => ({
    ok: true, ...identity(), auth: opts.authorized(req.headers.authorization),
    // Mac : false = fenêtres et raccourcis bloqués tant que l'Accessibilité n'est pas accordée (la tablette prévient).
    accessibility: await accessibility().catch(() => null),
  }));

  // --- Appairage (voir src/pairing.ts)

  app.post<{ Body: { tablet?: string } }>(
    "/pair/request",
    { schema: { body: { type: "object", properties: { tablet: { type: "string", maxLength: 100 } } } } },
    async (req) => requestPairing(req.body?.tablet || "tablette", req.ip),
  );

  app.post<{ Body: { request: string; code: string } }>(
    "/pair/confirm",
    {
      schema: {
        body: {
          type: "object",
          required: ["request", "code"],
          properties: { request: { type: "string", maxLength: 100 }, code: { type: "string", maxLength: 20 } },
        },
      },
    },
    async (req) => confirmPairing(req.body.request, req.body.code),
  );
}
