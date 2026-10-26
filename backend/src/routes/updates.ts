import { createReadStream } from "node:fs";
import fs from "node:fs/promises";
import type { FastifyInstance } from "fastify";
import { listUpdates, updateFile } from "../updates.ts";

/** Redémarrage du compagnon et mises à jour de la tablette. */
export default async function updateRoutes(app: FastifyInstance) {
  /**
   * Redémarre le compagnon après une mise à jour de ses fichiers : le lancement automatique (boucle de
   * lanceur UnowhyCompanion.exe sur Windows, LaunchAgent sur Mac) le relance quelques secondes plus tard. Sous
   * `npm run dev`, tsx watch attend un changement de fichier avant de relancer.
   */
  app.post("/companion/restart", async (req) => {
    req.log.info("redémarrage demandé");
    setTimeout(() => process.exit(0), 300);
    return { ok: true };
  });

  // --- Mises à jour de la tablette (« serveur Unowhy » simulé, voir updates.ts)

  app.get("/updates", async () => ({ updates: await listUpdates() }));

  app.get<{ Params: { file: string } }>("/updates/file/:file", async (req, reply) => {
    const full = await updateFile(req.params.file);
    if (!full) return reply.code(404).send({ error: "Mise à jour inconnue" });
    const stat = await fs.stat(full);
    reply.header("Content-Length", stat.size);
    return reply.type("application/vnd.android.package-archive").send(createReadStream(full));
  });
}
