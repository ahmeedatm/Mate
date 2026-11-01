import fs from "node:fs";
import path from "node:path";
import type { FastifyInstance, InjectOptions } from "fastify";
import { StreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/streamableHttp.js";
import { backendRoot, config } from "../config.ts";
import { createMcpServer, type Api } from "../mcp-tools.ts";

/**
 * Ce que la page locale montre pour brancher une IA (carte « Connecter une IA ») : l'adresse de la porte, et la commande
 * du pont stdio pour Claude Desktop. Compagnon installé : le node embarqué et dist/mcp-bridge.mjs (scripts/package.ts),
 * rien à installer. Depuis le dépôt : tsx sur src/mcp-bridge.ts.
 */
export function mcpInfo() {
  const url = `http://127.0.0.1:${config.port}/mcp`;
  const bundled = path.join(backendRoot, "dist", "mcp-bridge.mjs");
  const desktop = fs.existsSync(bundled)
    ? { command: process.execPath, args: [bundled, url] }
    : {
        command: path.join(backendRoot, "node_modules", ".bin", process.platform === "win32" ? "tsx.cmd" : "tsx"),
        args: [path.join(backendRoot, "src", "mcp-bridge.ts"), url],
      };
  return { url, desktop };
}

/**
 * Porte MCP du compagnon installé : `http://127.0.0.1:<port>/mcp` (Streamable HTTP), pour brancher une IA de bureau
 * (Claude Code, Claude Desktop par mcp-bridge.ts, Cursor, VS Code). Mêmes outils que mcp.ts (mcp-tools.ts), appelés sur
 * ce serveur sans passer par le réseau (`inject`) avec le jeton : mêmes routes et mêmes vérifications que la tablette.
 *
 * En local seulement, sans jeton (isLocalPage dans server.ts) : jamais par le WiFi, même avec le jeton. Un autre site ouvert
 * dans le navigateur ne peut pas l'appeler : le SDK exige un corps JSON (pré-vérification CORS, jamais accordée) et
 * server.ts refuse un hôte qui n'est pas local (DNS rebinding). Sans état : un serveur MCP par requête.
 */
export default async function mcpRoutes(app: FastifyInstance) {
  const auth = config.token ? { authorization: `Bearer ${config.token}` } : {};
  const api: Api = async (method, url, body) => {
    const res = await app.inject({ method, url, headers: auth, ...(body && { payload: body as InjectOptions["payload"] }) });
    let json: Record<string, any> = {};
    try {
      json = res.json();
    } catch {
      // Réponse sans JSON : seul le statut compte.
    }
    // Erreur levée par une route (forme de Fastify) : `error` n'est que le nom du statut (« Bad Request »), le texte est dans `message`.
    const reason = json.statusCode ? (json.message ?? json.error) : (json.error ?? json.message);
    if (res.statusCode >= 400) throw new Error(reason ?? `HTTP ${res.statusCode}`);
    return json;
  };

  const LOOPBACK = new Set(["127.0.0.1", "::1", "::ffff:127.0.0.1"]);

  app.post("/mcp", async (req, reply) => {
    if (!LOOPBACK.has(req.ip)) return reply.code(403).send({ error: "MCP en local seulement" });
    const server = createMcpServer(api, () => server.server.getClientVersion()?.name ?? "IA");
    const transport = new StreamableHTTPServerTransport({ sessionIdGenerator: undefined, enableJsonResponse: true });
    reply.hijack();
    reply.raw.on("close", () => {
      void transport.close();
      void server.close();
    });
    await server.connect(transport);
    await transport.handleRequest(req.raw, reply.raw, req.body);
  });

  // Sans état : pas de flux ouvert par GET, pas de session à fermer (réponse attendue par la spécification MCP).
  const notAllowed = { jsonrpc: "2.0", error: { code: -32000, message: "Méthode non permise" }, id: null };
  app.get("/mcp", async (_req, reply) => reply.code(405).header("allow", "POST").send(notAllowed));
  app.delete("/mcp", async (_req, reply) => reply.code(405).header("allow", "POST").send(notAllowed));
}
