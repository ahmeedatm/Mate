import type { FastifyInstance } from "fastify";
import { PERSONA_IDS, quitDemo, setPersona, type PersonaId } from "../demo.ts";

/** Démo produit (src/demo.ts) : données fictives d'un persona à la place de celles de l'ordinateur, rendues en la quittant. */
export default async function demoRoutes(app: FastifyInstance) {
  app.post<{ Body: { persona: PersonaId } }>(
    "/demo/persona",
    {
      schema: {
        body: {
          type: "object",
          additionalProperties: false,
          required: ["persona"],
          properties: { persona: { enum: PERSONA_IDS } },
        },
      },
    },
    async (req) => ({ persona: req.body.persona, keys: await setPersona(req.body.persona) }),
  );

  app.delete("/demo", async () => ({ restored: await quitDemo() }));
}
