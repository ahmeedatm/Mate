import type { FastifyInstance } from "fastify";
import { BACKUP_FORMAT, BACKUP_PARTS, exportBackup, layoutFrom, type BackupParts, type BackupPart } from "../backup.ts";
import { appsFile, assertPushAlone, keySchema, keysFile, linksFile, MAX_SLOTS, pagesFile, type PersoKey } from "../perso.ts";
import { listApps } from "../os.ts";
import { sendToTablet, tabletStatus } from "../tablet.ts";
import { writeJson } from "../store.ts";
import { applyPluginChoices, pluginChoicesBody } from "./plugins.ts";
import { readCache } from "./local-profiles.ts";

/**
 * Sauvegarde de la configuration depuis la page locale (section Sauvegarde, local-config-backup.ts) : GET rend le fichier,
 * POST relit les parties choisies. Sans jeton, en local seulement (isLocalPage dans server.ts), comme /local/config/*.
 * Les écritures reprennent les schémas des routes de la tablette : un fichier modifié à la main ne passe pas.
 */
const slotsSchema = { type: "array", maxItems: MAX_SLOTS, items: { anyOf: [keySchema, { type: "null" }] } };

const partsSchema = {
  type: "object",
  additionalProperties: false,
  properties: {
    keys: { type: "object", additionalProperties: false, required: ["slots"], properties: { slots: slotsSchema } },
    pages: {
      type: "object",
      additionalProperties: false,
      required: ["pages"],
      properties: {
        pages: {
          type: "array",
          maxItems: 50,
          items: {
            type: "object",
            additionalProperties: false,
            required: ["title", "slots"],
            properties: { title: { type: "string", minLength: 1, maxLength: 40 }, slots: slotsSchema },
          },
        },
      },
    },
    apps: {
      type: "object",
      additionalProperties: false,
      required: ["apps"],
      properties: {
        apps: {
          type: "array",
          maxItems: 200,
          items: {
            type: "object",
            additionalProperties: false,
            required: ["label", "name"],
            properties: { label: { type: "string", maxLength: 200 }, name: { type: "string", minLength: 1, maxLength: 200 } },
          },
        },
      },
    },
    links: {
      type: "object",
      additionalProperties: false,
      required: ["links"],
      properties: {
        links: {
          type: "array",
          maxItems: 200,
          items: {
            type: "object",
            additionalProperties: false,
            required: ["label", "url"],
            properties: { label: { type: "string", maxLength: 40 }, url: { type: "string", pattern: "^https?://", maxLength: 2000 } },
          },
        },
      },
    },
    plugins: {
      type: "object",
      additionalProperties: false,
      required: ["choices"],
      properties: { choices: pluginChoicesBody.properties.choices },
    },
    layout: {
      type: "object",
      additionalProperties: false,
      required: ["layout", "topBar", "rail"],
      properties: {
        layout: {
          type: "object",
          required: ["names", "widgets"],
          properties: {
            names: { type: "array", items: { type: ["string", "null"] } },
            widgets: { type: "array", items: { type: "object" } },
          },
        },
        topBar: { type: "array", items: { type: "string" } },
        rail: {
          type: "object",
          additionalProperties: false,
          properties: { labels: { type: "boolean" }, tools: { type: "array", items: { type: "string" } } },
        },
      },
    },
  },
};

const badRequest = (error: string) => Object.assign(new Error(error), { statusCode: 400 });

export default async function localBackupRoutes(app: FastifyInstance) {
  app.get("/local/config/backup", async () => exportBackup(layoutFrom(readCache()?.data)));

  app.post<{ Body: { backup: { format: string; parts: BackupParts }; parts: BackupPart[] } }>(
    "/local/config/backup",
    {
      schema: {
        body: {
          type: "object",
          required: ["backup", "parts"],
          properties: {
            backup: { type: "object", required: ["format", "parts"], properties: { format: { const: BACKUP_FORMAT }, parts: partsSchema } },
            parts: { type: "array", minItems: 1, uniqueItems: true, items: { enum: BACKUP_PARTS } },
          },
        },
      },
    },
    async (req) => {
      const { backup, parts } = req.body;
      const missing = parts.filter((p) => !backup.parts[p]);
      if (missing.length) throw badRequest("Le fichier ne contient pas une des parties choisies");
      const imported: BackupPart[] = [];
      const skipped: Partial<Record<BackupPart, string>> = {};
      const notes: Partial<Record<BackupPart, string>> = {};
      for (const part of parts) {
        const { skip, note } = await restore(part, backup.parts);
        if (skip) skipped[part] = skip;
        else imported.push(part);
        if (note) notes[part] = note;
      }
      return { imported, skipped, notes };
    },
  );
}

const checked = (slots: (PersoKey | null)[]) => slots.forEach((k) => assertPushAlone(k));

/** Reprend une partie : `skip` si elle n'a pas pu l'être (tablette éteinte), `note` pour ce qui a été laissé de côté. */
async function restore(part: BackupPart, parts: BackupParts): Promise<{ skip?: string; note?: string }> {
  switch (part) {
    case "keys":
      checked(parts.keys!.slots);
      await writeJson(keysFile, parts.keys);
      return {};
    case "pages":
      parts.pages!.pages.forEach((p) => checked(p.slots));
      await writeJson(pagesFile, parts.pages);
      return {};
    case "apps": {
      // Seules les applis installées ici : le Deck n'a rien à lancer pour les autres.
      const installed = new Set(await listApps());
      const apps = parts.apps!.apps.filter((a) => installed.has(a.name));
      await writeJson(appsFile, { apps });
      const absent = parts.apps!.apps.length - apps.length;
      return absent ? { note: `${absent} appli(s) absente(s) de cet ordinateur laissée(s) de côté` } : {};
    }
    case "links":
      await writeJson(linksFile, parts.links);
      return {};
    case "plugins":
      await applyPluginChoices(parts.plugins!.choices);
      return {};
    case "layout": {
      if (!tabletStatus().connected) return { skip: "Tablette non connectée : rallume-la puis reprends les profils" };
      const result = await sendToTablet({ type: "layout", action: "set", ...parts.layout! });
      return result.ok ? {} : { skip: result.error ?? "La tablette n'a rien rendu" };
    }
  }
}
