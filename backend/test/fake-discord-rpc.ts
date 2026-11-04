import { createHash } from "node:crypto";
import http from "node:http";
import type { AddressInfo, Socket } from "node:net";

/**
 * Faux Discord des tests : la porte RPC locale (WebSocket, comme ws://127.0.0.1:6463 de l'appli Discord) et l'échange
 * du code de StreamKit (`POST /overlay/token`), sur le même port. Il se comporte comme le vrai, relevé le 4/11/2026 sur
 * le PC d'Ahmed : READY à l'ouverture, `Origin` exigé (4001 sinon), réponses portant le `nonce` de la demande.
 */

export const FAKE_CODE = "code-du-faux-discord";
export const FAKE_TOKEN = "jeton-du-faux-discord-0123456789";
export const SELF_ID = "100000000000000001";
/** PNG de 1 × 1 px servi par le faux CDN. */
export const PNG = Buffer.from(
  "89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c4890000000d49444154789c6360000002000154a24f5f0000000049454e44ae426082",
  "hex",
);

type Frame = { cmd: string; args?: Record<string, unknown>; evt?: string; nonce?: string };

export type FakeDiscord = {
  port: number;
  url: string;
  /** Réponse à AUTHORIZE : code rendu, refus, ou rien (l'utilisateur ne clique pas). */
  authorize: "accept" | "refuse" | "never";
  /** Jetons que AUTHENTICATE accepte. */
  validTokens: Set<string>;
  /** Échéance du jeton rendue par AUTHENTICATE. */
  expires: string;
  /** Appel choisi (`GET_SELECTED_VOICE_CHANNEL`), null hors appel. */
  channel: Record<string, unknown> | null;
  /** Demandes reçues, dans l'ordre. */
  received: Frame[];
  /** En-têtes Origin vus à l'ouverture. */
  origins: (string | undefined)[];
  /** Corps reçus par l'échange du code. */
  exchanged: unknown[];
  /** Photos demandées au faux CDN. */
  avatarHits: string[];
  connections: () => number;
  dispatch: (evt: string, data: unknown) => void;
  /** Coupe les connexions ouvertes (Discord qui redémarre), le port reste à l'écoute. */
  drop: () => void;
  close: () => Promise<void>;
};

const encode = (text: string) => {
  const payload = Buffer.from(text);
  const head =
    payload.length < 126 ? Buffer.from([0x81, payload.length]) : Buffer.from([0x81, 126, payload.length >> 8, payload.length & 0xff]);
  return Buffer.concat([head, payload]);
};

/** Trames du client (masquées, non fragmentées : les demandes RPC sont courtes). */
function* decode(buffer: Buffer): Generator<{ opcode: number; text: string; size: number }> {
  let offset = 0;
  while (buffer.length - offset >= 6) {
    const opcode = buffer[offset] & 0x0f;
    let length = buffer[offset + 1] & 0x7f;
    let at = offset + 2;
    if (length === 126) {
      length = buffer.readUInt16BE(at);
      at += 2;
    }
    if (buffer.length < at + 4 + length) return;
    const mask = buffer.subarray(at, at + 4);
    const data = Buffer.from(buffer.subarray(at + 4, at + 4 + length));
    for (let i = 0; i < data.length; i++) data[i] ^= mask[i % 4];
    yield { opcode, text: data.toString(), size: at + 4 + length - offset };
    offset = at + 4 + length;
  }
}

export async function startFakeDiscord(): Promise<FakeDiscord> {
  const sockets = new Set<Socket>();
  const send = (socket: Socket, message: unknown) => socket.write(encode(JSON.stringify(message)));

  const server = http.createServer((req, res) => {
    // Faux CDN des photos (cdn.discordapp.com/avatars/<id>/<hash>.png).
    if (req.method === "GET" && req.url?.startsWith("/avatars/")) {
      fake.avatarHits.push(req.url);
      return res.writeHead(200, { "content-type": "image/png" }).end(PNG);
    }
    if (req.method !== "POST" || req.url !== "/overlay/token") return res.writeHead(404).end();
    let body = "";
    req.on("data", (d) => (body += d));
    req.on("end", () => {
      const parsed = JSON.parse(body || "{}") as { code?: string };
      fake.exchanged.push(parsed);
      if (parsed.code !== FAKE_CODE) return res.writeHead(400, { "content-type": "application/json" }).end('{"error":"invalid_grant"}');
      res.writeHead(200, { "content-type": "application/json" }).end(JSON.stringify({ access_token: FAKE_TOKEN }));
    });
  });

  const reply = (socket: Socket, frame: Frame) => {
    const answer = (data: unknown, evt: string | null = null) => send(socket, { cmd: frame.cmd, data, evt, nonce: frame.nonce });
    switch (frame.cmd) {
      case "AUTHORIZE":
        if (fake.authorize === "accept") answer({ code: FAKE_CODE });
        else if (fake.authorize === "refuse") answer({ code: 5000, message: "OAuth2 Error: access_denied" }, "ERROR");
        return;
      case "AUTHENTICATE":
        if (fake.validTokens.has(String(frame.args?.access_token)))
          answer({ user: { id: SELF_ID, username: "moi" }, scopes: ["rpc"], expires: fake.expires });
        else answer({ code: 4009, message: "Invalid access token" }, "ERROR");
        return;
      case "GET_SELECTED_VOICE_CHANNEL":
        return answer(fake.channel);
      case "SUBSCRIBE":
      case "UNSUBSCRIBE":
        return answer({ evt: frame.evt });
      default:
        return answer({ code: 4000, message: "Unknown command" }, "ERROR");
    }
  };

  server.on("upgrade", (req, socket: Socket) => {
    const origin = req.headers.origin;
    fake.origins.push(origin);
    const accept = createHash("sha1")
      .update(String(req.headers["sec-websocket-key"]) + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")
      .digest("base64");
    socket.write(
      `HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ${accept}\r\n\r\n`,
    );
    sockets.add(socket);
    socket.on("close", () => sockets.delete(socket));
    socket.on("error", () => sockets.delete(socket));
    if (origin !== "http://localhost:3000") {
      // Comme Discord : fermeture 4001 « Invalid Origin ».
      const reason = Buffer.from("Invalid Origin");
      socket.end(Buffer.concat([Buffer.from([0x88, reason.length + 2, 4001 >> 8, 4001 & 0xff]), reason]));
      return;
    }
    send(socket, { cmd: "DISPATCH", evt: "READY", data: { v: 1, config: { cdn_host: "cdn.discordapp.com" } }, nonce: null });
    let pending = Buffer.alloc(0);
    socket.on("data", (chunk: Buffer) => {
      pending = Buffer.concat([pending, chunk]);
      for (const { opcode, text, size } of decode(pending)) {
        pending = pending.subarray(size);
        if (opcode === 0x8) {
          socket.end(Buffer.from([0x88, 0]));
          return;
        }
        if (opcode !== 0x1) continue;
        const frame = JSON.parse(text) as Frame;
        fake.received.push(frame);
        reply(socket, frame);
      }
    });
  });

  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const port = (server.address() as AddressInfo).port;
  const fake: FakeDiscord = {
    port,
    url: `http://127.0.0.1:${port}`,
    authorize: "accept",
    validTokens: new Set([FAKE_TOKEN]),
    expires: new Date(Date.now() + 7 * 86_400_000).toISOString(),
    channel: null,
    received: [],
    origins: [],
    exchanged: [],
    avatarHits: [],
    connections: () => sockets.size,
    dispatch: (evt, data) => sockets.forEach((s) => send(s, { cmd: "DISPATCH", evt, data, nonce: null })),
    drop: () => sockets.forEach((s) => s.destroy()),
    close: async () => {
      sockets.forEach((s) => s.destroy());
      await new Promise<void>((resolve) => server.close(() => resolve()));
    },
  };
  return fake;
}

/** État vocal d'un participant, tel que Discord le donne (salon de serveur ou appel privé). */
export const voiceStateOf = (
  id: string,
  username: string,
  extra: { nick?: string; avatar?: string | null; global_name?: string } = {},
) => ({
  nick: extra.nick ?? username,
  mute: false,
  volume: 100,
  voice_state: { mute: false, deaf: false, self_mute: false, self_deaf: false, suppress: false },
  user: { id, username, global_name: extra.global_name ?? null, avatar: extra.avatar ?? null },
});
