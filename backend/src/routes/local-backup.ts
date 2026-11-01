import type { FastifyInstance } from "fastify";
import { BACKUP_FORMAT, BACKUP_PARTS, exportBackup, layoutFrom, type BackupParts, type BackupPart } from "../backup.ts";
import { normalizeLink } from "../deck.ts";
import { appsFile, assertPushAlone, keySchema, keysFile, linksFile, MAX_SLOTS, pagesFile, type DeckLink, type PersoKey } from "../perso.ts";
import { listApps } from "../os.ts";
import { sendToTablet, tabletStatus } from "../tablet.ts";
import { writeJson } from "../store.ts";
import { applyPluginChoices, pluginChoicesBody } from "./plugins.ts";
import { readCache } from "./local-profiles.ts";

/**
 * Sauvegarde de la configuration depuis la page locale (section Sauvegarde, local-config-backup.ts) : GET rend le fichier,
 * POST relit les parties choisies. Sans jeton, en local seulement (isLocalPage dans server.ts), comme /local/config/* ;
 * le jeton d'appairage ne suffit pas : un hook refuse (403) tout ce qui ne vient pas de la page locale. Le fichier peut
 * venir d'ailleurs : les écritures reprennent les schémas des routes de la tablette, un nom d'appli ou de raccourci ne
 * peut pas être un chemin, et tout est vérifié avant la première écriture (tout ou rien).
 */
const LOOPBACK = new Set(["127.0.0.1", "::1", "::ffff:127.0.0.1"]);
const LOCAL_HOST = /^(127\.0\.0\.1|localhost|\[::1\])(:\d+)?$/i;

const slotsSchema = { type: "array", maxItems: MAX_SLOTS, items: { anyOf: [keySchema, { type: "null" }] } };

// Champs d'un widget tels que la tablette les écrit (WidgetModel.kt) ; Fastify retire les autres.
const widgetSchema = {
  type: "object",
  additionalProperties: false,
  properties: { t: {}, s: {}, p: {}, c: {}, r: {}, cfg: { type: "object" } },
};

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
            names: { type: "array", maxItems: 20, items: { type: ["string", "null"] } },
            widgets: { type: "array", maxItems: 200, items: widgetSchema },
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
  app.addHook("onRequest", async (req, reply) => {
    if (!LOOPBACK.has(req.ip) || !LOCAL_HOST.test(req.headers.host ?? "")) {
      return reply.code(403).send({ error: "Sauvegarde depuis la page locale de l'ordinateur seulement" });
    }
  });

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
      // Tout est vérifié avant la première écriture : une partie invalide n'en laisse aucune à moitié reprise.
      const plans = await Promise.all(parts.map(async (part) => ({ part, run: await plan(part, backup.parts) })));
      for (const { part, run } of plans) {
        const { skip, note } = await run();
        if (skip) skipped[part] = skip;
        else imported.push(part);
        if (note) notes[part] = note;
      }
      return { imported, skipped, notes };
    },
  );
}

const checked = (slots: (PersoKey | null)[]) => slots.forEach((k) => assertPushAlone(k));

/** Nom d'une appli ou d'un raccourci : jamais un chemin (l'appli lancée serait celle du fichier, pas une de l'ordinateur). */
const BAD_NAME = /[\\/]|\.app$/i;
function checkSteps(slots: (PersoKey | null)[]) {
  for (const key of slots) {
    for (const step of [...(key?.steps ?? []), ...(key?.hold ?? [])]) {
      if ((step.type === "app" || step.type === "shortcut") && BAD_NAME.test(step.name)) {
        throw badRequest(`« ${step.name} » n'est pas un nom d'appli ou de raccourci`);
      }
    }
  }
}
const checkKeys = (slots: (PersoKey | null)[]) => {
  checked(slots);
  checkSteps(slots);
};

/** Vérifie une partie sans rien écrire, rend de quoi la reprendre : `skip` si elle n'a pas pu l'être (tablette éteinte), `note` pour ce qui a été laissé de côté. */
async function plan(part: BackupPart, parts: BackupParts): Promise<() => Promise<{ skip?: string; note?: string }>> {
  switch (part) {
    case "keys":
      checkKeys(parts.keys!.slots);
      return async () => {
        await writeJson(keysFile, parts.keys);
        return {};
      };
    case "pages":
      parts.pages!.pages.forEach((p) => checkKeys(p.slots));
      return async () => {
        await writeJson(pagesFile, parts.pages);
        return {};
      };
    case "apps": {
      // Seules les applis installées ici : le Deck n'a rien à lancer pour les autres.
      const installed = new Set(await listApps());
      const apps = parts.apps!.apps.filter((a) => installed.has(a.name));
      return async () => {
        await writeJson(appsFile, { apps });
        const absent = parts.apps!.apps.length - apps.length;
        return absent ? { note: `${absent} appli(s) absente(s) de cet ordinateur laissée(s) de côté` } : {};
      };
    }
    case "links": {
      // Comme à l'ajout depuis la tablette : adresse normalisée, une seule fois chaque site.
      const links = new Map<string, DeckLink>();
      for (const l of parts.links!.links) {
        let link: DeckLink;
        try {
          link = normalizeLink(l.url, l.label);
        } catch {
          throw badRequest(`Adresse web illisible : ${l.url}`);
        }
        if (!links.has(link.url)) links.set(link.url, link);
      }
      return async () => {
        await writeJson(linksFile, { links: [...links.values()] });
        return {};
      };
    }
    case "plugins":
      return async () => {
        await applyPluginChoices(parts.plugins!.choices);
        return {};
      };
    case "layout":
      return async () => {
        if (!tabletStatus().connected) return { skip: "Tablette non connectée : rallume-la puis reprends les profils" };
        const result = await sendToTablet({ type: "layout", action: "set", ...parts.layout! });
        return result.ok ? {} : { skip: result.error ?? "La tablette n'a rien rendu" };
      };
  }
}
