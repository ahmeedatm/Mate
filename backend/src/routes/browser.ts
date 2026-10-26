import type { FastifyInstance } from "fastify";
import { receiveTabs, type ExtensionReport } from "../browser-extension.ts";
import { browserPage, browserTabs, openTarget, osName } from "../os.ts";

export default async function browserRoutes(app: FastifyInstance) {
  // --- Page web du navigateur de l'ordinateur, affichée sur la tablette (tutos à côté d'un jeu)

  /** Adresse web lisible : la barre d'adresse de Chrome cache souvent « https:// ». Autre protocole : refusé. */
  function webUrl(raw: string): string | undefined {
    try {
      const url = new URL(/^[a-z][a-z0-9+.-]*:/i.test(raw) ? raw : "https://" + raw);
      return url.protocol === "http:" || url.protocol === "https:" ? url.href : undefined;
    } catch {
      return undefined;
    }
  }

  /** Onglets ouverts sur l'ordinateur (choix de la page sur la tablette), sans les pages internes (chrome://, about:…). */
  app.get("/browser/tabs", async () => {
    const tabs = await browserTabs();
    return {
      // false : Windows sans l'extension du navigateur (la tablette explique comment l'ajouter).
      extension: tabs !== undefined,
      tabs: (tabs ?? [])
        .map((tab) => ({ ...tab, url: webUrl(tab.url) }))
        .filter((tab) => tab.url !== undefined)
        .slice(0, 80),
    };
  });

  /** Onglets envoyés par l'extension « Compagnon Unowhy » du navigateur (en local, sans jeton). */
  app.post<{ Body: ExtensionReport }>(
    "/browser/extension",
    {
      logLevel: "warn",
      schema: {
        body: {
          type: "object",
          required: ["browser", "focusedAt", "focusedWindow", "tabs"],
          properties: {
            browser: { type: "string", maxLength: 40 },
            focusedAt: { type: "number" },
            focusedWindow: { type: ["number", "null"] },
            tabs: {
              type: "array",
              maxItems: 500,
              items: {
                type: "object",
                required: ["id", "window", "index", "url", "active"],
                properties: {
                  id: { type: "number" }, window: { type: "number" }, index: { type: "number" },
                  title: { type: "string", maxLength: 1000 }, url: { type: "string", maxLength: 4000 }, active: { type: "boolean" },
                },
              },
            },
          },
        },
      },
    },
    async (req, reply) => {
      // Seule une extension (pas une page web ouverte sur l'ordinateur) peut annoncer des onglets.
      if (!/^(chrome|moz)-extension:\/\//.test(req.headers.origin ?? "")) return reply.code(403).send({ error: "Réservé à l'extension du navigateur" });
      receiveTabs(req.body);
      return { ok: true };
    },
  );

  app.get("/browser/page", async (req, reply) => {
    const page = await browserPage();
    const url = page.found && page.url ? webUrl(page.url) : undefined;
    if (!url) {
      // État du presse-papiers (Windows) : vide, autre chose qu'une adresse, ou illisible ; jamais son contenu.
      const clipboard = (page as { clipboard?: string }).clipboard;
      if (clipboard?.startsWith("error")) req.log.warn({ clipboard }, "presse-papiers illisible");
      const copy = osName === "mac" ? "⌘L puis ⌘C" : "Ctrl+L puis Ctrl+C";
      return reply.code(404).send({
        error: clipboard === "not-url"
          ? `Le lien copié n'est pas une adresse web : dans le navigateur de l'ordinateur, fais ${copy} et réessaie`
          : `Adresse illisible : dans le navigateur de l'ordinateur, copie l'adresse (${copy}) et réessaie`,
        clipboard: clipboard?.startsWith("error") ? "error" : clipboard,
      });
    }
    // Titre de fenêtre Windows : « Page - Opera », « Page - Personnel - Microsoft Edge », « Page — Mozilla Firefox ».
    const title = (page.title ?? "")
      .replace(/\s+[-—–]\s+(?:[^-—–]+\s+[-—–]\s+)?(?:Google Chrome|Microsoft\u200b?\s*Edge|Opera(?: GX)?|Brave|Vivaldi|Mozilla Firefox)\s*$/i, "")
      .trim();
    return { url, title: title || new URL(url).hostname, browser: page.browser ?? null };
  });

  /** Rouvre sur l'ordinateur la page affichée sur la tablette (elle a pu y changer de page). */
  app.post<{ Body: { url: string } }>(
    "/browser/open",
    { schema: { body: { type: "object", required: ["url"], properties: { url: { type: "string", maxLength: 4000 } } } } },
    async (req, reply) => {
      const url = webUrl(req.body.url);
      if (!url) return reply.code(400).send({ error: "Adresse web attendue" });
      await openTarget(url);
      return { ok: true };
    },
  );
}
