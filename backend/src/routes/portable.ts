import path from "node:path";
import type { FastifyInstance } from "fastify";
import { checkUpdate, installUpdate } from "../app-update.ts";
import { backendRoot } from "../paths.ts";
import { identity } from "../pairing.ts";
import {
  appPath,
  autostartEnabled,
  capteursActive,
  firewallOpen,
  FORGET_CODE,
  forgetEverything,
  portable,
  QUIT_CODE,
  setAutostart,
  setCapteurs,
  setFirewall,
} from "../portable.ts";

/**
 * Panneau de l'appli portable (cartes de la page locale, src/local-page.ts). Pages locales sans jeton : chaque route
 * qui change quelque chose veut un corps JSON, qu'un site ne peut pas envoyer à 127.0.0.1 sans autorisation du navigateur.
 */
export default async function portableRoutes(app: FastifyInstance) {
  const object = { type: "object" } as const;
  const onlyPortable = () => {
    if (!portable) throw Object.assign(new Error("Disponible dans l'appli portable seulement"), { statusCode: 409 });
  };
  /** Sort après la réponse, avec le code que lit la boucle du lanceur. */
  const exitSoon = (code: number) => setTimeout(() => process.exit(code), 300);

  app.get("/local/panel", async () => {
    if (!portable) return { portable: false };
    return {
      portable: true,
      app: appPath,
      version: identity().version,
      autostart: await autostartEnabled(),
      firewall: await firewallOpen(),
      capteurs: await capteursActive(),
      extension: path.join(backendRoot, "extension"),
    };
  });

  app.post<{ Body: { on: boolean } }>(
    "/local/autostart",
    { schema: { body: { type: "object", required: ["on"], properties: { on: { type: "boolean" } } } } },
    async (req) => {
      onlyPortable();
      await setAutostart(req.body.on);
      return { ok: true, autostart: await autostartEnabled() };
    },
  );

  app.post("/local/firewall", { schema: { body: object } }, async () => {
    onlyPortable();
    return { ok: await setFirewall(true), firewall: await firewallOpen() };
  });

  app.post("/local/capteurs", { schema: { body: object } }, async () => {
    onlyPortable();
    if ((await capteursActive()) === null)
      throw Object.assign(new Error("Température du processeur : rien à activer sur ce système"), { statusCode: 501 });
    return { ok: await setCapteurs(true), capteurs: await capteursActive() };
  });

  app.post<{ Body: { sure: true } }>(
    "/local/forget",
    { schema: { body: { type: "object", required: ["sure"], properties: { sure: { const: true } } } } },
    async (req) => {
      onlyPortable();
      req.log.info("tout oublier demandé");
      await forgetEverything();
      exitSoon(FORGET_CODE);
      return { ok: true };
    },
  );

  app.post("/local/quit", { schema: { body: object } }, async (req) => {
    onlyPortable();
    req.log.info("arrêt demandé");
    exitSoon(QUIT_CODE);
    return { ok: true };
  });

  app.get("/local/update", async () => {
    onlyPortable();
    return checkUpdate(identity().version);
  });

  app.post("/local/update", { schema: { body: object } }, async (req) => {
    onlyPortable();
    const { version, launch } = await installUpdate(identity().version);
    req.log.info({ version }, "mise à jour posée, relance");
    setTimeout(launch, 300);
    return { ok: true, version };
  });
}
