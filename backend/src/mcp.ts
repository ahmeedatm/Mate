import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";

/**
 * Extension MCP en stdio (déclarée dans .mcp.json pour Claude Code dans ce dépôt) : tout client MCP pilote et règle la tablette.
 * Outils dans mcp-tools.ts, partagés avec la porte HTTP du compagnon installé (routes/mcp.ts). Lancée par le client, elle relaie vers le backend déjà démarré (service automatique ou `npm run dev`),
 * qui transmet à la tablette par le canal /tablet. stdout est réservé au protocole MCP.
 */

// Lancée par Claude depuis n'importe quel dossier : on charge nous-mêmes backend/.env.
const envFile = fileURLToPath(new URL("../.env", import.meta.url));
if (fs.existsSync(envFile)) process.loadEnvFile(envFile);
// DATA_DIR (.mcp.json) = compagnon installé par paquet, sans .env : son jeton est celui de identity.json, pas celui du .env de dev.
if (process.env.DATA_DIR) delete process.env.COMPANION_TOKEN;
const { config } = await import("./config.ts");

const baseUrl = `http://127.0.0.1:${config.port}`;

async function api(method: "GET" | "POST" | "PUT" | "DELETE", path: string, body?: object): Promise<Record<string, any>> {
  const headers: Record<string, string> = {};
  if (config.token) headers.authorization = `Bearer ${config.token}`;
  if (body) headers["content-type"] = "application/json";
  const res = await fetch(baseUrl + path, { method, headers, body: body && JSON.stringify(body) }).catch(() => {
    throw new Error(
      `Backend du compagnon injoignable sur ${baseUrl} : lancer \`npm run service:install\` (ou \`npm run dev\`) dans backend/`,
    );
  });
  const json = (await res.json().catch(() => ({}))) as Record<string, any>;
  // Erreurs Fastify : { error: "Bad Request", message: <la vraie raison> } ; les nôtres : { error }.
  if (!res.ok) throw new Error(json.message ?? json.error ?? `HTTP ${res.status}`);
  return json;
}

const { createMcpServer } = await import("./mcp-tools.ts");
await createMcpServer(api, () => path.basename(process.cwd())).connect(new StdioServerTransport());
