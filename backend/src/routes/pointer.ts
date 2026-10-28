import type { FastifyInstance } from "fastify";
import { MAX_MOVE, MAX_SCROLL, POINTER_GESTURES, type PointerGesture } from "../pointer.ts";
import { pointerButton, pointerGesture, pointerMove, pointerScroll } from "../os.ts";

const int = (max: number) => ({ type: "integer", minimum: -max, maximum: max });

/**
 * Pavé tactile : même effet que les paquets UDP (src/pointer.ts), en HTTP pour les clics (fiables), les tests et le secours
 * si l'UDP est bloqué. Pas de journal de requête : un déplacement arrive jusqu'à 60 fois par seconde.
 */
export default async function pointerRoutes(app: FastifyInstance) {
  app.post<{ Body: { dx: number; dy: number } }>(
    "/pointer/move",
    {
      logLevel: "warn",
      schema: {
        body: { type: "object", required: ["dx", "dy"], additionalProperties: false, properties: { dx: int(MAX_MOVE), dy: int(MAX_MOVE) } },
      },
    },
    async (req) => {
      await pointerMove(req.body.dx, req.body.dy);
      return { ok: true };
    },
  );

  app.post<{ Body: { sx: number; sy: number } }>(
    "/pointer/scroll",
    {
      logLevel: "warn",
      schema: {
        body: {
          type: "object",
          required: ["sx", "sy"],
          additionalProperties: false,
          properties: { sx: int(MAX_SCROLL), sy: int(MAX_SCROLL) },
        },
      },
    },
    async (req) => {
      await pointerScroll(req.body.sx, req.body.sy);
      return { ok: true };
    },
  );

  app.post<{ Body: { button: "left" | "right"; action: "click" | "down" | "up" } }>(
    "/pointer/button",
    {
      logLevel: "warn",
      schema: {
        body: {
          type: "object",
          required: ["button", "action"],
          additionalProperties: false,
          properties: { button: { enum: ["left", "right"] }, action: { enum: ["click", "down", "up"] } },
        },
      },
    },
    async (req) => {
      await pointerButton(req.body.button, req.body.action);
      return { ok: true };
    },
  );

  // Liste fermée de gestes : le raccourci correspondant est choisi par le compagnon, selon le système.
  app.post<{ Body: { gesture: PointerGesture } }>(
    "/pointer/gesture",
    {
      logLevel: "warn",
      schema: {
        body: {
          type: "object",
          required: ["gesture"],
          additionalProperties: false,
          properties: { gesture: { enum: [...POINTER_GESTURES] } },
        },
      },
    },
    async (req) => {
      await pointerGesture(req.body.gesture);
      return { ok: true };
    },
  );
}
