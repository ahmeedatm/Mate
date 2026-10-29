import type { TLSSocket } from "node:tls";
import type { FastifyInstance, FastifyRequest } from "fastify";
import { accessibility } from "../os.ts";
import { approvePairing, confirmPairing, identity, requestPairing, revealPairing } from "../pairing.ts";

/**
 * Routes sans jeton : identité de l'ordinateur et appairage (protocole : src/pairing.ts). `authorized` : contrôle du jeton
 * de server.ts ; `fingerprint` : empreinte du certificat du WiFi (null : serveur en local seulement).
 */
export default async function pairingRoutes(
  app: FastifyInstance,
  opts: { authorized: (header: string | undefined) => boolean; fingerprint: string | null },
) {
  /** Empreinte vue par la tablette sur cette connexion : celle du certificat en HTTPS, "" en HTTP local (USB). */
  const channel = (req: FastifyRequest) => ((req.raw.socket as TLSSocket).encrypted ? (opts.fingerprint ?? "") : "");

  /** `auth` : le jeton envoyé est le bon (la tablette sait s'il faut refaire l'appairage). */
  app.get("/health", async (req) => ({
    ok: true,
    ...identity(),
    auth: opts.authorized(req.headers.authorization),
    // Mac : false = fenêtres et raccourcis bloqués tant que l'Accessibilité n'est pas accordée (la tablette prévient).
    accessibility: await accessibility().catch(() => null),
  }));

  const id = { type: "string", maxLength: 100 };
  /** 32 octets en base64url. */
  const nonce = { type: "string", pattern: "^[A-Za-z0-9_-]{43}$" };

  app.post<{ Body: { tablet?: string; commit: string } }>(
    "/pair/request",
    {
      schema: { body: { type: "object", required: ["commit"], properties: { tablet: { type: "string", maxLength: 100 }, commit: nonce } } },
    },
    async (req) => requestPairing(req.body.tablet || "tablette", req.ip, req.body.commit, channel(req)),
  );

  app.post<{ Body: { request: string; nonce: string } }>(
    "/pair/reveal",
    { schema: { body: { type: "object", required: ["request", "nonce"], properties: { request: id, nonce } } } },
    async (req) => revealPairing(req.body.request, req.body.nonce),
  );

  app.post<{ Body: { request: string } }>(
    "/pair/confirm",
    { schema: { body: { type: "object", required: ["request"], properties: { request: id } } } },
    async (req) => confirmPairing(req.body.request, opts.fingerprint ?? ""),
  );

  /** Saisie du code sur l'ordinateur : page locale seulement (voir `isLocalPage` dans server.ts). */
  app.post<{ Body: { code: string } }>(
    "/local/pair",
    {
      schema: {
        body: { type: "object", required: ["code"], additionalProperties: false, properties: { code: { type: "string", maxLength: 20 } } },
      },
    },
    async (req) => approvePairing(req.body.code),
  );
}
