import { createReadStream } from "node:fs";
import fs from "node:fs/promises";
import type { FastifyInstance } from "fastify";
import { cachedIcon } from "../deck.ts";
import { authorizeDiscord, discordAvatarUrl, discordPhotos, discordSpeaking } from "../discord-rpc.ts";
import { readCapped } from "../net-guard.ts";

/** Photo de 64 px : quelques Ko ; au-delà, ce n'est pas une photo de profil. */
const AVATAR_MAX_BYTES = 200_000;
const PNG_SIGNATURE = Buffer.from([0x89, 0x50, 0x4e, 0x47]);

/**
 * Photos des participants d'un appel Discord et qui parle (src/discord-rpc.ts). Les photos sont téléchargées par
 * l'ordinateur (Discord ne voit jamais la tablette) et gardées en cache ; leur adresse vient de Discord, la tablette ne
 * donne qu'un identifiant de participant de l'appel en cours.
 */
export default async function discordRoutes(app: FastifyInstance) {
  /** Geste « Montrer les photos » : attend le clic sur « Autoriser » dans Discord, 60 s au plus. */
  app.post("/discord/authorize", async () => {
    await authorizeDiscord();
    return { photos: discordPhotos() };
  });

  // Relu toutes les 500 ms par la tablette pendant un appel : lecture en mémoire, pas de trace par requête.
  app.get("/discord/speaking", { logLevel: "warn" }, () => ({ speaking: discordSpeaking() }));

  app.get<{ Querystring: { id: string } }>(
    "/discord/avatar",
    {
      schema: { querystring: { type: "object", required: ["id"], properties: { id: { type: "string", pattern: "^[0-9]{1,25}$" } } } },
    },
    async (req, reply) => {
      const url = discordAvatarUrl(req.query.id);
      // Le hash de la photo fait partie de la clé : une nouvelle photo se retélécharge.
      const file = url ? await cachedIcon(`discord:${url}`, (out) => downloadAvatar(url, out)) : undefined;
      if (!file) return reply.code(404).send({ error: "Pas de photo" });
      reply.header("Cache-Control", "max-age=86400");
      return reply.type("image/png").send(createReadStream(file));
    },
  );
}

async function downloadAvatar(url: string, out: string): Promise<boolean> {
  const res = await fetch(url, { redirect: "error", signal: AbortSignal.timeout(10_000) });
  if (!res.ok) return false;
  const { data, truncated } = await readCapped(res, AVATAR_MAX_BYTES);
  if (truncated || !data.subarray(0, 4).equals(PNG_SIGNATURE)) return false;
  // Écrit à côté puis renommé : une lecture en même temps ne sert jamais une photo à moitié écrite.
  const tmp = `${out}.${process.pid}.tmp`;
  await fs.writeFile(tmp, data);
  await fs.rename(tmp, out);
  return true;
}
