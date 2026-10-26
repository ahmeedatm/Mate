import { createReadStream } from "node:fs";
import fs from "node:fs/promises";
import type { FastifyInstance } from "fastify";
import { kindOf, listDocs, resolveDoc, savePhoto } from "../docs.ts";
import { openTarget } from "../os.ts";

export default async function docRoutes(app: FastifyInstance) {
  // --- Documents partagés (dossier DOCS_DIR sur l'ordinateur)

  app.get<{ Querystring: { dir?: string } }>("/docs", async (req) => listDocs(req.query.dir ?? ""));

  app.get<{ Querystring: { path: string } }>("/docs/file", async (req, reply) => {
    const full = resolveDoc(req.query.path ?? "");
    const stat = await fs.stat(full).catch(() => undefined);
    if (!stat?.isFile()) return reply.code(404).send({ error: "Fichier introuvable" });
    const types = { pdf: "application/pdf", image: "image/*", text: "text/plain; charset=utf-8" } as const;
    const kind = kindOf(full);
    reply.header("Content-Length", stat.size);
    return reply.type(kind in types ? types[kind as keyof typeof types] : "application/octet-stream").send(createReadStream(full));
  });

  app.post<{ Body: { path: string } }>(
    "/docs/open",
    { schema: { body: { type: "object", required: ["path"], properties: { path: { type: "string" } } } } },
    async (req) => {
      await openTarget(resolveDoc(req.body.path));
      return { ok: true };
    },
  );

  app.post("/docs/upload", async (req, reply) => {
    const file = await req.file();
    if (!file) return reply.code(400).send({ error: "Champ multipart 'photo' manquant" });
    const ext = file.mimetype === "image/png" ? ".png" : ".jpg";
    return { path: await savePhoto(await file.toBuffer(), ext) };
  });
}
