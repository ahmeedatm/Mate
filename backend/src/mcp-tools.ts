import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { CallToolResult } from "@modelcontextprotocol/sdk/types.js";
import { z } from "zod";
import { SYSTEM_COMMANDS, TILE_SYMBOLS } from "./screens.ts";
import { THEMES } from "./tablet.ts";

/**
 * Outils MCP du compagnon, communs aux deux portes d'entrée : `mcp.ts` (stdio, lancé par le client depuis le dépôt) et
 * `routes/mcp.ts` (HTTP sur 127.0.0.1, dans le compagnon installé). Chaque outil appelle l'API HTTP du compagnon par
 * `api` : mêmes routes, mêmes vérifications que la tablette et la page locale. Ce module ne charge rien de src/ qui lise
 * la config (mcp.ts charge backend/.env avant).
 */

export type Api = (method: "GET" | "POST" | "PUT" | "DELETE", path: string, body?: object) => Promise<Record<string, any>>;

/** Transforme les erreurs en résultat d'outil lisible par Claude plutôt qu'en erreur de protocole. */
async function attempt(fn: () => Promise<CallToolResult>): Promise<CallToolResult> {
  try {
    return await fn();
  } catch (e) {
    return { isError: true, content: [{ type: "text", text: e instanceof Error ? e.message : String(e) }] };
  }
}

const text = (value: unknown): CallToolResult => ({
  content: [{ type: "text", text: typeof value === "string" ? value : JSON.stringify(value, null, 2) }],
});

/** `where` : d'où vient la question posée sur la tablette (dossier du client stdio, nom du client HTTP). */
export function createMcpServer(api: Api, where: () => string): McpServer {
  const command = (body: object) => api("POST", "/tablet/command", body);

  const server = new McpServer(
    { name: "mate", version: "0.1.0" },
    {
      instructions:
        "Pilote la tablette posée sur le bureau (compagnon de l'ordinateur, Mac ou PC Windows). Écran paysage 1280×800, " +
        "modes : accueil (tableau de bord : heure, prochain rendez-vous, agenda, tâches dont le worklog Jira, Focus, son de l'ordinateur), " +
        "deck (profil Deck de l'Accueil : applis, son, raccourcis de l'ordinateur, touches perso à affecter : deck_touche_*), docs (dossier partagé de l'ordinateur), focus (Pomodoro, sons d'ambiance), camera (visualiseur : la caméra filme une feuille, image projetée depuis l'ordinateur sur /camera), réglages. Commencer par tablette_etat ; tablette_capture montre l'écran. " +
        "Tout se règle aussi d'ici, comme sur la page de l'ordinateur : thème, luminosité, volume (tablette_regler), profils, widgets, bandeau du haut et barre de gauche " +
        "(profils_lire puis profils_ecrire), touches et macros (deck_touche_*), plugins, applis et sites du deck. Lire l'état avant de le changer ; " +
        "montrer le résultat avec tablette_capture. " +
        "Textes affichés en français, courts : la tablette est lue de loin.",
    },
  );

  const MODES = { accueil: "home", deck: "deck", docs: "docs", focus: "focus", camera: "camera", reglages: "settings" } as const;
  const FOCUS = { demarrer: "start", pause: "pause", reinitialiser: "reset", passer: "skip" } as const;
  const NOISES = { pluie: "storm", foret: "forest", japon: "japan", mer: "sea", lofi: "lofi", silence: "off" } as const;
  const keys = <T extends object>(o: T) => Object.keys(o) as [keyof T & string, ...(keyof T & string)[]];

  server.registerTool(
    "tablette_etat",
    {
      title: "État de la tablette",
      description: "Connectée ou non, mode affiché, document ouvert, minuteur Focus, son d'ambiance, synthèse vocale disponible.",
      annotations: { readOnlyHint: true },
    },
    () => attempt(async () => text(await api("GET", "/tablet"))),
  );

  server.registerTool(
    "tablette_capture",
    {
      title: "Capture d'écran de la tablette",
      description: "Renvoie l'image de ce qui est affiché sur la tablette (l'interface du compagnon).",
      annotations: { readOnlyHint: true },
    },
    () =>
      attempt(async () => {
        const { data } = await command({ type: "screenshot" });
        return { content: [{ type: "image", data: data.jpeg, mimeType: "image/jpeg" }] };
      }),
  );

  server.registerTool(
    "tablette_afficher_mode",
    {
      title: "Changer d'écran",
      description: "Affiche un mode de la tablette.",
      inputSchema: { mode: z.enum(keys(MODES)) },
    },
    ({ mode }) => attempt(async () => (await command({ type: "mode", mode: MODES[mode] }), text(`Mode ${mode} affiché.`))),
  );

  server.registerTool(
    "tablette_message",
    {
      title: "Afficher un message",
      description: "Affiche un message en bandeau sur la tablette, et le lit à voix haute si demandé. Toucher le bandeau le ferme.",
      inputSchema: {
        texte: z.string().min(1).max(1000),
        lire_a_voix_haute: z.boolean().default(false),
        duree_secondes: z.number().int().min(2).max(600).default(10),
      },
    },
    ({ texte, lire_a_voix_haute, duree_secondes }) =>
      attempt(async () => {
        await command({ type: "message", text: texte, speak: lire_a_voix_haute, seconds: duree_secondes });
        return text("Message affiché" + (lire_a_voix_haute ? " et lu." : "."));
      }),
  );

  server.registerTool(
    "docs_lister",
    {
      title: "Lister le dossier partagé",
      description: "Liste un dossier du dossier partagé de l'ordinateur (celui que la tablette affiche dans Docs). Chemins relatifs.",
      inputSchema: { dossier: z.string().default("").describe("Chemin relatif, vide pour la racine") },
      annotations: { readOnlyHint: true },
    },
    ({ dossier }) => attempt(async () => text(await api("GET", "/docs?dir=" + encodeURIComponent(dossier)))),
  );

  server.registerTool(
    "tablette_document",
    {
      title: "Ouvrir ou fermer un document",
      description:
        "Ouvre un fichier du dossier partagé (PDF, image, texte) dans le panneau de droite de la tablette. Sans chemin : ferme le panneau.",
      inputSchema: { chemin: z.string().optional().describe("Chemin relatif renvoyé par docs_lister") },
    },
    ({ chemin }) =>
      attempt(async () => {
        await command({ type: "document", ...(chemin ? { path: chemin } : {}) });
        return text(chemin ? `${chemin} ouvert sur la tablette.` : "Panneau document fermé.");
      }),
  );

  server.registerTool(
    "tablette_focus",
    {
      title: "Minuteur Pomodoro",
      description: "Pilote le minuteur Focus. Avec des durées, le minuteur est d'abord réinitialisé sur ces durées.",
      inputSchema: {
        action: z.enum(keys(FOCUS)),
        minutes_focus: z.number().int().min(1).max(180).optional(),
        minutes_pause: z.number().int().min(1).max(60).optional(),
      },
    },
    ({ action, minutes_focus, minutes_pause }) =>
      attempt(async () => {
        const { data } = await command({ type: "focus", action: FOCUS[action], focusMinutes: minutes_focus, breakMinutes: minutes_pause });
        return text(data ?? "OK");
      }),
  );

  server.registerTool(
    "tablette_ambiance",
    {
      title: "Son d'ambiance",
      description:
        "Lance une ambiance sonore douce sur le haut-parleur de la tablette (gouttes de pluie et tonnerre lointain, oiseaux et vent en forêt, lofi japonais, mer, lofi), ou la coupe.",
      inputSchema: { son: z.enum(keys(NOISES)), volume: z.number().int().min(0).max(100).optional().describe("En %") },
    },
    ({ son, volume }) =>
      attempt(async () => {
        await command({ type: "noise", kind: NOISES[son], ...(volume === undefined ? {} : { volume: volume / 100 }) });
        return text(son === "silence" ? "Son coupé." : `Bruit ${son} lancé.`);
      }),
  );

  server.registerTool(
    "taches_lister",
    {
      title: "Tâches du jour",
      description: "Liste les tâches affichées sur la tablette : quotidiennes (ex. worklog Jira, avec le temps saisi) et ponctuelles.",
      annotations: { readOnlyHint: true },
    },
    () => attempt(async () => text((await api("GET", "/tasks?refresh=1")).tasks)),
  );

  server.registerTool(
    "tache_ajouter",
    {
      title: "Ajouter une tâche",
      description:
        "Ajoute une tâche à la liste de la tablette. Sans `jours` : ponctuelle (disparaît le lendemain une fois cochée). " +
        '`jours` (1 = lundi … 7 = dimanche) : récurrente ces jours-là. `rappel` ("HH:MM") : la tablette le dit à voix haute ' +
        "à cette heure (récurrente : puis toutes les 30 min pendant 2 h tant que ce n'est pas coché).",
      inputSchema: {
        texte: z.string().min(1).max(200),
        jours: z.array(z.number().int().min(1).max(7)).max(7).optional(),
        rappel: z
          .string()
          .regex(/^([01][0-9]|2[0-3]):[0-5][0-9]$/)
          .optional(),
      },
    },
    ({ texte, jours, rappel }) => attempt(async () => text(await api("POST", "/tasks", { label: texte, days: jours, remindAt: rappel }))),
  );

  server.registerTool(
    "tache_cocher",
    {
      title: "Cocher une tâche",
      description: "Coche (ou décoche) une tâche par son id (voir taches_lister).",
      inputSchema: { id: z.string(), fait: z.boolean().default(true) },
    },
    ({ id, fait }) =>
      attempt(
        async () => (
          await api("POST", `/tasks/${encodeURIComponent(id)}/done`, { done: fait }),
          text(fait ? "Tâche cochée." : "Tâche décochée.")
        ),
      ),
  );

  server.registerTool(
    "agenda_ajouter",
    {
      title: "Noter un rendez-vous",
      description:
        "Ajoute un rendez-vous à l'agenda personnel de la tablette (affiché sur l'Accueil avec les agendas reliés). " +
        "`date` : AAAA-MM-JJ ; `heure` : HH:MM (absente = toute la journée) ; `rappel` : minutes avant pour que la tablette le dise à voix haute (0 = à l'heure).",
      inputSchema: {
        titre: z.string().min(1).max(200),
        date: z.string().regex(/^\d{4}-\d{2}-\d{2}$/),
        heure: z
          .string()
          .regex(/^([01][0-9]|2[0-3]):[0-5][0-9]$/)
          .optional(),
        rappel: z.number().int().min(0).max(1440).optional(),
      },
    },
    ({ titre, date, heure, rappel }) =>
      attempt(async () => {
        // Heure locale de l'ordinateur (celle que montre la tablette).
        const [y, m, d] = date.split("-").map(Number);
        const [h, min] = (heure ?? "00:00").split(":").map(Number);
        const startMs = new Date(y, m - 1, d, h, min).getTime();
        return text(await api("POST", "/calendar/events", { title: titre, startMs, allDay: !heure, remindBefore: rappel ?? null }));
      }),
  );

  // Plafond des touches perso : MAX_SLOTS de backend/src/perso.ts (ce fichier ne charge pas src/).
  const MAX_SLOTS = 100;

  // Étapes d'une touche perso : mêmes types que backend/src/perso.ts (le backend revalide).
  const STEP = z.discriminatedUnion("type", [
    z.object({
      type: z.literal("app"),
      name: z.string().describe("Nom de l'appli : .app sur Mac, menu Démarrer sur Windows (ex. Visual Studio Code)"),
    }),
    z.object({
      type: z.literal("keys"),
      keys: z.string().describe("Raccourci, ex. cmd+shift+m, ctrl+up, f5, return (sur Windows cmd = Ctrl, win = touche Windows)"),
      app: z.string().optional().describe("Appli à mettre devant avant"),
    }),
    z.object({ type: z.literal("text"), text: z.string().max(4000).describe("Texte collé dans l'appli au premier plan") }),
    z.object({ type: z.literal("url"), url: z.string().describe("http(s), mailto, msteams, slack, vscode, x-apple…") }),
    z.object({ type: z.literal("shortcut"), name: z.string().describe("Raccourci de l'appli Raccourcis (Mac seulement)") }),
    z.object({ type: z.literal("media"), command: z.enum(["play-pause", "next", "previous"]) }),
    z.object({
      type: z.literal("system"),
      command: z.enum(SYSTEM_COMMANDS),
    }),
    z.object({ type: z.literal("wait"), ms: z.number().int().min(0).max(10_000) }),
    z.object({
      type: z.literal("window"),
      app: z.string(),
      display: z.string().optional().describe("Libellé d'écran (voir deck_touches_lister), sinon l'écran actuel"),
      layout: z
        .enum(["keep", "left", "right", "max"])
        .optional()
        .describe("keep : même place relative ; left/right : moitié ; max : agrandie"),
    }),
  ]);

  /** Chemin d'une touche : page Perso, ou page nommée (numéro 1 et suivants, comme deck_touches_lister). */
  const keyPath = (numero: number, page?: number) =>
    page === undefined ? `/local/config/keys/${numero - 1}` : `/local/config/pages/${page - 1}/keys/${numero - 1}`;
  const PAGE = z.number().int().min(1).max(50).optional().describe("Page nommée (numéro de deck_touches_lister) ; absente : page Perso");
  type Slot = Record<string, unknown> | null;
  const numbered = (keys: Slot[]) => keys.map((k, i) => ({ numero: i + 1, ...(k ?? { vide: true }) }));

  server.registerTool(
    "deck_touches_lister",
    {
      title: "Touches du deck",
      description:
        "Liste les touches de la page Perso et des pages nommées du deck (numéros 1 et suivants, vides comprises), les icônes " +
        "possibles, les écrans de l'ordinateur, son système, ses applis installées et les raccourcis Apple disponibles (Mac).",
      annotations: { readOnlyHint: true },
    },
    () =>
      attempt(async () => {
        const k = await api("GET", "/local/config/keys");
        return text({
          systeme: k.catalog.os,
          perso: numbered(k.perso.keys),
          pages: (k.pages as { title: string; keys: Slot[] }[]).map((p, i) => ({ page: i + 1, titre: p.title, touches: numbered(p.keys) })),
          icones: k.symbols,
          ecrans: k.catalog.displays,
          applis: k.catalog.apps,
          raccourcis_apple: k.catalog.shortcuts,
        });
      }),
  );

  server.registerTool(
    "deck_touche_affecter",
    {
      title: "Affecter une touche du deck",
      description:
        "Programme une touche du deck (page Perso ou page nommée) : une étape (raccourci simple) ou plusieurs (macro exécutée dans l'ordre). " +
        "Ex. « Mode code » : window VS Code sur l'écran externe en max, puis window Teams sur l'écran intégré à gauche. Remplace la touche si elle est prise.",
      inputSchema: {
        numero: z.number().int().min(1).max(MAX_SLOTS),
        page: PAGE,
        libelle: z.string().min(1).max(40).describe("Court : lu de loin sur la tablette"),
        icone: z.enum(TILE_SYMBOLS).optional().describe("Icône dessinée sur la touche (liste dans deck_touches_lister)"),
        symbole: z.string().max(8).optional().describe("Emoji, pour les tablettes d'avant les icônes"),
        couleur: z
          .string()
          .regex(/^#[0-9A-Fa-f]{6}$/)
          .optional(),
        etapes: z.array(STEP).min(1).max(20),
      },
    },
    ({ numero, page, libelle, icone, symbole, couleur, etapes }) =>
      attempt(async () => {
        await api("PUT", keyPath(numero, page), { label: libelle, symbol: icone, glyph: symbole, color: couleur, steps: etapes });
        return text(`Touche ${numero} « ${libelle} » enregistrée (actualiser le deck sur la tablette).`);
      }),
  );

  server.registerTool(
    "deck_touche_effacer",
    {
      title: "Vider une touche du deck",
      description: "Remet à vide une touche du deck (page Perso ou page nommée).",
      inputSchema: { numero: z.number().int().min(1).max(MAX_SLOTS), page: PAGE },
      annotations: { destructiveHint: true },
    },
    ({ numero, page }) => attempt(async () => (await api("DELETE", keyPath(numero, page)), text(`Touche ${numero} vidée.`))),
  );

  // --- Réglages de la tablette et de l'ordinateur : les mêmes que la page locale (routes/local.ts, local-profiles.ts, local-config.ts)

  server.registerTool(
    "tablette_reglages",
    {
      title: "Réglages de la tablette",
      description:
        "Réglages actuels de la tablette connectée : thème, luminosité, volume, atténuation la nuit, Ne pas déranger sur " +
        "l'ordinateur pendant la concentration ; et les thèmes possibles.",
      annotations: { readOnlyHint: true },
    },
    () =>
      attempt(async () => {
        const s = await api("GET", "/local/status");
        if (!s.tabletInfo) return text({ connectee: false, themes: THEMES });
        return text({ connectee: true, tablette: s.tabletInfo.name, reglages: s.tabletInfo.settings, themes: THEMES });
      }),
  );

  server.registerTool(
    "tablette_regler",
    {
      title: "Changer les réglages de la tablette",
      description: "Change un ou plusieurs réglages de la tablette connectée ; les autres restent tels quels.",
      inputSchema: {
        theme: z.enum(THEMES).optional(),
        luminosite: z.number().int().min(5).max(100).optional().describe("En %"),
        volume: z.number().int().min(0).max(100).optional().describe("Haut-parleur de la tablette, en %"),
        attenuation_nuit: z.boolean().optional().describe("Écran atténué de 22 h à 7 h"),
        ne_pas_deranger_focus: z.boolean().optional().describe("Ne pas déranger sur l'ordinateur pendant la concentration"),
      },
    },
    ({ theme, luminosite, volume, attenuation_nuit, ne_pas_deranger_focus }) =>
      attempt(async () => {
        const change = {
          theme,
          brightness: luminosite === undefined ? undefined : luminosite / 100,
          volume: volume === undefined ? undefined : volume / 100,
          nightDim: attenuation_nuit,
          focusDnd: ne_pas_deranger_focus,
        };
        if (Object.values(change).every((v) => v === undefined)) throw new Error("Aucun réglage à changer.");
        const res = await api("PUT", "/local/tablet/settings", change);
        return text({ reglages: res.tablet?.settings ?? null });
      }),
  );

  server.registerTool(
    "profils_lire",
    {
      title: "Profils, widgets et barres de la tablette",
      description:
        "Disposition de la tablette, à relire avant profils_ecrire. `layout` : `names` (nom de chaque profil = page de la grille ; " +
        "`home`, `deck`, `call` = index des profils Accueil, Deck et Réunion), `widgets` : { t: clé du widget, s: taille, p: index du profil, " +
        "c: colonne, r: ligne, cfg: réglages du widget }. `cols` × `rows` : grille ; `sizes` : tailles (largeur w, hauteur h) ; " +
        "`catalog` : widgets possibles et leurs tailles (`offered` faux = pas proposé sur cet ordinateur). `topBar` : bandeau du haut " +
        "(`items` dans l'ordre, `addable`). `rail` : barre de gauche (`labels`, outils `tools` affichés, `available`). `deck` : pages " +
        "de touches de l'ordinateur (pour le widget Touches). `readOnly` vrai : tablette absente, dernier état connu.",
      annotations: { readOnlyHint: true },
    },
    () => attempt(async () => text(await api("GET", "/local/tablet/layout"))),
  );

  const WIDGET = z
    .object({
      t: z.string().describe("Clé du widget (catalog)"),
      s: z.string().describe("Taille permise pour ce widget (catalog)"),
      p: z.number().int().min(0).describe("Index du profil"),
      c: z.number().int().min(0),
      r: z.number().int().min(0),
      cfg: z.record(z.string(), z.unknown()).optional().describe("Réglages du widget : garder ceux lus par profils_lire"),
    })
    .passthrough();

  server.registerTool(
    "profils_ecrire",
    {
      title: "Changer les profils, widgets ou barres",
      description:
        "Remplace la disposition des profils (`layout` complet : partir de celui de profils_lire et le modifier), le bandeau du haut " +
        "(`bandeau` : clés dans l'ordre) et/ou la barre de gauche (`barre`). Au moins une des trois parties. La tablette vérifie tout " +
        "avant d'écrire : un widget qui sort de la grille ou en chevauche un autre, une taille non permise, un rôle retiré (Accueil, " +
        "Réunion) refusent tout le changement, avec la raison. Renvoie le nouvel état.",
      inputSchema: {
        layout: z
          .object({
            names: z.array(z.string().nullable()).min(1),
            widgets: z.array(WIDGET),
          })
          .passthrough()
          .optional()
          .describe("Disposition complète (names, icons, home, deck, call, roles, widgets), telle que profils_lire la rend"),
        bandeau: z.array(z.string()).optional().describe("Clés des éléments du bandeau du haut, dans l'ordre"),
        barre: z
          .object({
            libelles: z.boolean().optional().describe("Noms affichés sous les icônes"),
            outils: z.array(z.string()).optional().describe("Clés des outils affichés"),
          })
          .optional(),
      },
      annotations: { destructiveHint: true },
    },
    ({ layout, bandeau, barre }) =>
      attempt(async () => {
        const rail = barre && { labels: barre.libelles, tools: barre.outils };
        if (!layout && !bandeau && !barre) throw new Error("Rien à changer : donner layout, bandeau ou barre.");
        return text(await api("PUT", "/local/tablet/layout", { layout, topBar: bandeau, rail }));
      }),
  );

  server.registerTool(
    "plugins_lister",
    {
      title: "Plugins de la tablette",
      description:
        "Applis détectées (Discord, Teams, Claude Code) et fonctions (Ouvertes, Son, Pavé, Média) que la tablette propose : " +
        "`on` actif, `present` appli installée sur l'ordinateur.",
      annotations: { readOnlyHint: true },
    },
    () => attempt(async () => text((await api("GET", "/local/config/plugins")).plugins)),
  );

  server.registerTool(
    "plugins_choisir",
    {
      title: "Activer ou couper des plugins",
      description:
        "Active (true) ou coupe (false) des plugins par leur id (plugins_lister). Un plugin coupé retire sa page du deck et ses widgets du catalogue.",
      inputSchema: { choix: z.record(z.string(), z.boolean()) },
    },
    ({ choix }) => attempt(async () => text(await api("PUT", "/local/config/plugins", { choices: choix }))),
  );

  server.registerTool(
    "deck_applis_lister",
    {
      title: "Applis du deck",
      description: "Applis de la page Apps du deck (id, libellé, nom) et applis installées sur l'ordinateur qu'on peut ajouter.",
      annotations: { readOnlyHint: true },
    },
    () => attempt(async () => text(await api("GET", "/local/config/apps"))),
  );

  server.registerTool(
    "deck_appli_ajouter",
    {
      title: "Ajouter une appli au deck",
      description: "Ajoute une appli installée (nom exact de `installed` dans deck_applis_lister) à la page Apps du deck.",
      inputSchema: { nom: z.string().min(1).max(200) },
    },
    ({ nom }) => attempt(async () => (await api("POST", "/local/config/apps", { name: nom }), text(`${nom} ajoutée au deck.`))),
  );

  server.registerTool(
    "deck_appli_retirer",
    {
      title: "Retirer une appli du deck",
      description: "Retire une appli de la page Apps du deck, par son id (deck_applis_lister). L'appli reste installée.",
      inputSchema: { id: z.string().min(1) },
      annotations: { destructiveHint: true },
    },
    ({ id }) => attempt(async () => (await api("DELETE", `/local/config/apps/${encodeURIComponent(id)}`), text("Appli retirée du deck."))),
  );

  server.registerTool(
    "deck_applis_reinitialiser",
    {
      title: "Rendre les applis de départ",
      description: "Remet la page Apps du deck au profil de base : les ajouts et retraits sont perdus.",
      annotations: { destructiveHint: true },
    },
    () => attempt(async () => (await api("DELETE", "/local/config/apps"), text("Page Apps remise au profil de base."))),
  );

  server.registerTool(
    "deck_sites_lister",
    {
      title: "Sites de la page Média",
      description: "Sites de la page Média du deck (id, libellé, adresse) et suggestions.",
      annotations: { readOnlyHint: true },
    },
    () => attempt(async () => text(await api("GET", "/local/config/links"))),
  );

  server.registerTool(
    "deck_site_ajouter",
    {
      title: "Ajouter un site à la page Média",
      description: "Ajoute un site (ex. netflix.com) à la page Média du deck : la touche l'ouvre sur l'ordinateur.",
      inputSchema: { adresse: z.string().min(1).max(2000), libelle: z.string().max(40).optional() },
    },
    ({ adresse, libelle }) =>
      attempt(async () => (await api("POST", "/local/config/links", { url: adresse, label: libelle }), text(`${adresse} ajouté.`))),
  );

  server.registerTool(
    "deck_site_retirer",
    {
      title: "Retirer un site de la page Média",
      description: "Retire un site de la page Média, par son id (deck_sites_lister).",
      inputSchema: { id: z.string().min(1) },
      annotations: { destructiveHint: true },
    },
    ({ id }) => attempt(async () => (await api("DELETE", `/local/config/links/${encodeURIComponent(id)}`), text("Site retiré."))),
  );

  server.registerTool(
    "agents_lister",
    {
      title: "Sessions Claude Code de l'ordinateur",
      description:
        "Liste les sessions Claude Code ouvertes sur cet ordinateur (Mac) et leur état : travaille, attend une réponse, fini, " +
        "limite atteinte, inactif ; dossier, branche, depuis quand, début du dernier message. Pour savoir où en sont les autres sessions.",
      annotations: { readOnlyHint: true },
    },
    () =>
      attempt(async () => {
        const state = await api("GET", "/agents");
        const label: Record<string, string> = {
          working: "travaille",
          waiting: "attend",
          done: "fini",
          limit: "limite atteinte",
          idle: "inactif",
        };
        const lines = (state.sessions as Record<string, any>[]).map((s) => {
          const since = Math.round((state.now - s.since) / 60_000);
          const where = [s.dir, s.branch].filter(Boolean).join(" · ");
          const summary = (s.lines as string[]).join(" / ");
          return `- ${s.name} (${where}) : ${label[s.state] ?? s.state} depuis ${since} min${summary ? ` : ${summary}` : ""}`;
        });
        return text(lines.length ? lines.join("\n") : "Aucune session Claude Code ouverte.");
      }),
  );

  server.registerTool(
    "tablette_demander",
    {
      title: "Poser une question sur la tablette",
      description:
        "Pose une question à choix à l'utilisateur sur la tablette posée à côté de l'ordinateur (lumière sur le bord de l'écran, " +
        "carte à toucher), et attend sa réponse (3 min au plus). Pour une décision quand il n'est peut-être pas devant l'ordinateur. " +
        "Il peut aussi répondre en texte libre (« Autre »). Renvoie la réponse, ou l'absence de réponse.",
      inputSchema: {
        question: z.string().min(1).max(500),
        options: z.array(z.string().min(1).max(200)).min(1).max(6),
        choix_multiple: z.boolean().default(false),
      },
    },
    ({ question, options, choix_multiple }) =>
      attempt(async () => {
        const res = await api("POST", "/agents/ask-tablet", {
          question,
          options,
          multi: choix_multiple,
          who: "Claude",
          where: where(),
        });
        return text(res.answered ? `Réponse sur la tablette : ${res.answer}` : "Pas de réponse sur la tablette à temps.");
      }),
  );

  return server;
}
