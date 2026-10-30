import { createReadStream } from "node:fs";
import fs from "node:fs/promises";
import type { FastifyInstance } from "fastify";
import { applyEssai, applyUpdate, backendState, restoreOrigin } from "../essai-backend.ts";
import { listBackends, listUpdates, updateFile } from "../updates.ts";

/** Redémarrage du compagnon, mises à jour de la tablette, mise à jour du backend et essai du backend d'une MR. */
export default async function updateRoutes(app: FastifyInstance) {
  /** Relancé par la boucle du lanceur (voir /companion/restart) ; après la réponse, pas avant. */
  const restartSoon = () => setTimeout(() => process.exit(0), 300);

  /**
   * Redémarre le compagnon après une mise à jour de ses fichiers : la boucle du lanceur (exe portable
   * sur Windows, « Mate.app » sur Mac, ou le service du dépôt) le relance quelques secondes plus tard. Sous
   * `npm run dev`, tsx watch attend un changement de fichier avant de relancer.
   */
  app.post("/companion/restart", async (req) => {
    req.log.info("redémarrage demandé");
    restartSoon();
    return { ok: true };
  });

  // --- Essai du backend d'une MR (voir essai-backend.ts) : envoyé par la tablette, jamais téléchargé ici.

  app.get("/companion/backend", async () => backendState());

  app.post<{ Body: Buffer; Querystring: { mr: number; sha256?: string } }>(
    "/companion/backend/essai",
    {
      logLevel: "warn",
      schema: {
        querystring: {
          type: "object",
          required: ["mr"],
          additionalProperties: false,
          properties: { mr: { type: "integer", minimum: 1 }, sha256: { type: "string", pattern: "^[0-9a-fA-F]{64}$" } },
        },
      },
    },
    async (req, reply) => {
      if (!Buffer.isBuffer(req.body) || req.body.length === 0) return reply.code(415).send({ error: "Corps application/gzip attendu" });
      applyEssai(req.body, req.query.mr, req.query.sha256);
      req.log.info({ mr: req.query.mr }, "backend d'essai posé, redémarrage");
      restartSoon();
      return { ok: true };
    },
  );

  /**
   * Mise à jour stable du backend (essai-backend.ts, applyUpdate) : archive d'une release téléchargée et vérifiée par la
   * tablette, version plus récente que celle qui tourne. Pose puis redémarre.
   */
  app.post<{ Body: Buffer; Querystring: { version: string; sha256: string } }>(
    "/companion/backend/update",
    {
      logLevel: "warn",
      schema: {
        querystring: {
          type: "object",
          required: ["version", "sha256"],
          additionalProperties: false,
          properties: {
            version: { type: "string", pattern: "^\\d+\\.\\d+\\.\\d+$" },
            sha256: { type: "string", pattern: "^[0-9a-fA-F]{64}$" },
          },
        },
      },
    },
    async (req, reply) => {
      if (!Buffer.isBuffer(req.body) || req.body.length === 0) return reply.code(415).send({ error: "Corps application/gzip attendu" });
      applyUpdate(req.body, req.query.sha256, req.query.version);
      req.log.info({ version: req.query.version }, "backend mis à jour, redémarrage");
      restartSoon();
      return { ok: true };
    },
  );

  /** Retour au backend d'origine ; sans essai en cours, rien à faire (et pas de redémarrage). */
  app.post("/companion/backend/origin", async (req) => {
    const changed = restoreOrigin();
    if (changed) {
      req.log.info("backend d'origine restauré, redémarrage");
      restartSoon();
    }
    return { ok: true, changed };
  });

  // --- Mises à jour de la tablette (« serveur Unowhy » simulé, voir updates.ts)

  app.get("/updates", async () => ({ updates: await listUpdates(), backends: await listBackends() }));

  app.get<{ Params: { file: string } }>("/updates/file/:file", async (req, reply) => {
    const full = await updateFile(req.params.file);
    if (!full) return reply.code(404).send({ error: "Mise à jour inconnue" });
    const stat = await fs.stat(full);
    reply.header("Content-Length", stat.size);
    const type = full.endsWith(".tar.gz") ? "application/gzip" : "application/vnd.android.package-archive";
    return reply.type(type).send(createReadStream(full));
  });
}
