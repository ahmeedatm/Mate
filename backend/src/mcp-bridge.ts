import readline from "node:readline";

/**
 * Pont stdio → porte MCP du compagnon (routes/mcp.ts), pour les clients qui ne savent que lancer un programme (Claude
 * Desktop). Lancé par le client avec le node du compagnon installé : `node dist/mcp-bridge.mjs http://127.0.0.1:3000/mcp`
 * (commande donnée par la page locale). Un message JSON-RPC par ligne sur stdin, chacun relayé à part (une question posée
 * sur la tablette attend sans bloquer les autres) ; réponses sur stdout, qui ne sert qu'au protocole. Aucun jeton : la
 * porte est locale. Ne charge rien de src/.
 */

const url = process.argv[2] ?? "http://127.0.0.1:3000/mcp";
const write = (message: unknown) => process.stdout.write(JSON.stringify(message) + "\n");

async function forward(line: string) {
  let id: unknown;
  try {
    id = JSON.parse(line).id;
  } catch {
    return;
  }
  const fail = (message: string) => id !== undefined && write({ jsonrpc: "2.0", id, error: { code: -32000, message } });
  try {
    const res = await fetch(url, {
      method: "POST",
      headers: { "content-type": "application/json", accept: "application/json, text/event-stream" },
      body: line,
    });
    const body = await res.text();
    if (!body) return; // 202 : notification reçue.
    const answer = JSON.parse(body);
    for (const message of [answer].flat()) {
      if (message?.jsonrpc) write(message);
      else fail(message?.error ?? `HTTP ${res.status}`);
    }
  } catch {
    fail("Compagnon UnoMate injoignable : rouvre « UnoMate » sur cet ordinateur.");
  }
}

readline.createInterface({ input: process.stdin }).on("line", (line) => {
  if (line.trim()) void forward(line);
});
