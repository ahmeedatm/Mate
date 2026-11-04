import assert from "node:assert/strict";
import fs from "node:fs/promises";
import { createServer } from "node:net";
import os from "node:os";
import path from "node:path";
import { after, afterEach, beforeEach, describe, it } from "node:test";
import { FAKE_TOKEN, SELF_ID, startFakeDiscord, voiceStateOf, type FakeDiscord } from "./fake-discord-rpc.ts";

process.env.DATA_DIR = await fs.mkdtemp(path.join(os.tmpdir(), "companion-discord-"));
process.env.DISCORD_RPC_RETRY_MS = "50";
process.env.DISCORD_AUTHORIZE_TIMEOUT_MS = "400";
const rpc = await import("../src/discord-rpc.ts");
const { setLogger } = await import("../src/log.ts");
const storeFile = path.join(process.env.DATA_DIR, "discord.json");

const logs: string[] = [];
setLogger({ warn: (msg) => logs.push(msg) });

/** Port fermé : rien n'y écoute (Discord absent). */
async function closedPort() {
  const server = createServer();
  await new Promise<void>((r) => server.listen(0, "127.0.0.1", r));
  const { port } = server.address() as { port: number };
  await new Promise((r) => server.close(r));
  return port;
}

async function until(check: () => boolean, ms = 3000) {
  const deadline = Date.now() + ms;
  while (!check()) {
    if (Date.now() > deadline) throw new Error("condition jamais atteinte");
    await new Promise((r) => setTimeout(r, 10));
  }
}

const A = "200000000000000002";
const B = "300000000000000003";
const C = "400000000000000004";
const HASH = "0123456789abcdef0123456789abcdef";

let fake: FakeDiscord;
beforeEach(async () => {
  fake = await startFakeDiscord();
  process.env.DISCORD_RPC_PORTS = String(fake.port);
  process.env.DISCORD_STREAMKIT_URL = fake.url;
  process.env.DISCORD_CDN_URL = "https://cdn.exemple";
});
afterEach(async () => {
  rpc.stopDiscordRpc();
  await fake.close();
  await fs.rm(storeFile, { force: true });
});
after(() => assert.ok(!logs.some((l) => l.includes(FAKE_TOKEN)), "jeton dans le journal"));

const authorized = async () => {
  await fs.writeFile(storeFile, JSON.stringify({ accessToken: FAKE_TOKEN }));
  await rpc.startDiscordRpc();
  await until(() => rpc.discordPhotos() === "on");
};
const sent = (cmd: string) => fake.received.filter((f) => f.cmd === cmd);

describe("Discord RPC : autorisation", () => {
  it("sans jeton : ne se connecte pas (aucune fenêtre surprise)", async () => {
    await rpc.startDiscordRpc();
    await new Promise((r) => setTimeout(r, 150));
    assert.equal(fake.origins.length, 0);
    assert.equal(rpc.discordPhotos(), "off");
  });

  it("accord : scope rpc seul, code échangé, jeton gardé en 600 sur l'ordinateur", async () => {
    await rpc.authorizeDiscord();
    assert.deepEqual(fake.origins, ["http://localhost:3000"]);
    assert.deepEqual(sent("AUTHORIZE")[0].args, { client_id: "207646673902501888", scopes: ["rpc"] });
    assert.deepEqual(fake.exchanged, [{ code: "code-du-faux-discord" }]);
    assert.equal(sent("AUTHENTICATE")[0].args?.access_token, FAKE_TOKEN);
    assert.equal(JSON.parse(await fs.readFile(storeFile, "utf8")).accessToken, FAKE_TOKEN);
    if (process.platform !== "win32") assert.equal((await fs.stat(storeFile)).mode & 0o777, 0o600);
    assert.equal(rpc.discordPhotos(), "on");
  });

  it("deux demandes en même temps : une seule fenêtre dans Discord", async () => {
    await Promise.all([rpc.authorizeDiscord(), rpc.authorizeDiscord()]);
    assert.equal(sent("AUTHORIZE").length, 1);
  });

  it("refus dans Discord : message clair, rien de gardé", async () => {
    fake.authorize = "refuse";
    await assert.rejects(rpc.authorizeDiscord(), /refusé/);
    assert.equal(rpc.discordPhotos(), "off");
    await assert.rejects(fs.access(storeFile));
  });

  it("pas de clic dans le délai : message, la connexion reste ouverte (pas d'erreur affichée dans Discord)", async () => {
    fake.authorize = "never";
    const pending = rpc.authorizeDiscord();
    await until(() => rpc.discordPhotos() === "waiting");
    await assert.rejects(pending, /Pas de réponse/);
    assert.equal(rpc.discordPhotos(), "off");
    assert.equal(fake.connections(), 1);
  });

  it("Discord fermé : message clair", async () => {
    process.env.DISCORD_RPC_PORTS = String(await closedPort());
    await assert.rejects(rpc.authorizeDiscord(), /Discord ne répond pas/);
    assert.equal(rpc.discordPhotos(), "off");
  });

  it("cherche la porte sur les ports suivants", async () => {
    process.env.DISCORD_RPC_PORTS = `${await closedPort()},${fake.port}`;
    await rpc.authorizeDiscord();
    assert.equal(rpc.discordPhotos(), "on");
  });
});

describe("Discord RPC : jeton", () => {
  it("jeton refusé : renouvellement silencieux (prompt none), nouveau jeton gardé", async () => {
    await fs.writeFile(storeFile, JSON.stringify({ accessToken: "jeton-expire" }));
    await rpc.startDiscordRpc();
    await until(() => rpc.discordPhotos() === "on");
    assert.equal(sent("AUTHORIZE")[0].args?.prompt, "none");
    assert.equal(JSON.parse(await fs.readFile(storeFile, "utf8")).accessToken, FAKE_TOKEN);
  });

  it("jeton refusé et renouvellement refusé : jeton effacé, plus de reconnexion", async () => {
    fake.authorize = "refuse";
    await fs.writeFile(storeFile, JSON.stringify({ accessToken: "jeton-revoque" }));
    await rpc.startDiscordRpc();
    await until(() => sent("AUTHORIZE").length === 1);
    await until(() => fake.connections() === 0);
    await assert.rejects(fs.access(storeFile));
    await new Promise((r) => setTimeout(r, 200));
    assert.equal(fake.origins.length, 1);
    assert.equal(rpc.discordPhotos(), "off");
  });

  it("jeton qui expire dans moins d'un jour : renouvelé en silence", async () => {
    fake.expires = new Date(Date.now() + 3600_000).toISOString();
    fake.validTokens.add("jeton-presque-fini");
    await fs.writeFile(storeFile, JSON.stringify({ accessToken: "jeton-presque-fini" }));
    await rpc.startDiscordRpc();
    await until(() => sent("AUTHORIZE").length === 1);
    assert.equal(sent("AUTHORIZE")[0].args?.prompt, "none");
    await until(() => fake.exchanged.length === 1);
    await new Promise((r) => setTimeout(r, 50));
    assert.equal(JSON.parse(await fs.readFile(storeFile, "utf8")).accessToken, FAKE_TOKEN);
  });
});

describe("Discord RPC : appel en cours", () => {
  const inCall = () => {
    fake.channel = {
      id: "900",
      name: "Salon",
      type: 2,
      voice_states: [
        voiceStateOf(SELF_ID, "moi"),
        voiceStateOf(A, "alice", { avatar: HASH, global_name: "Alice" }),
        voiceStateOf(B, "bob", { nick: "Bobby" }),
      ],
    };
  };

  it("participants (sans soi), noms affichés, abonnements au salon", async () => {
    inCall();
    await authorized();
    await until(() => rpc.discordMembers() !== undefined);
    assert.deepEqual(rpc.discordMembers(), [
      { id: A, name: "alice", avatar: true, speaking: false },
      { id: B, name: "Bobby", avatar: false, speaking: false },
    ]);
    const subs = sent("SUBSCRIBE").map((f) => `${f.evt}:${f.args?.channel_id ?? ""}`);
    for (const evt of ["VOICE_STATE_CREATE", "VOICE_STATE_UPDATE", "VOICE_STATE_DELETE", "SPEAKING_START", "SPEAKING_STOP"])
      assert.ok(subs.includes(`${evt}:900`), evt);
    assert.ok(subs.includes("VOICE_CHANNEL_SELECT:"));
  });

  it("qui parle, arrivées, départs, fin d'appel", async () => {
    inCall();
    await authorized();
    await until(() => rpc.discordMembers() !== undefined);
    fake.dispatch("SPEAKING_START", { channel_id: "900", user_id: A });
    await until(() => rpc.discordSpeaking().includes(A));
    fake.dispatch("SPEAKING_START", { channel_id: "900", user_id: SELF_ID });
    fake.dispatch("SPEAKING_STOP", { channel_id: "900", user_id: A });
    await until(() => rpc.discordSpeaking().length === 0);
    fake.dispatch("VOICE_STATE_CREATE", { channel_id: "900", ...voiceStateOf(C, "carole") });
    fake.dispatch("VOICE_STATE_DELETE", { channel_id: "900", ...voiceStateOf(B, "bob") });
    await until(
      () =>
        rpc
          .discordMembers()!
          .map((m) => m.id)
          .join() === `${A},${C}`,
    );
    fake.channel = null;
    fake.dispatch("VOICE_CHANNEL_SELECT", { channel_id: null });
    await until(() => rpc.discordMembers() === undefined);
    assert.ok(sent("UNSUBSCRIBE").some((f) => f.evt === "SPEAKING_START" && f.args?.channel_id === "900"));
  });

  it("Discord redémarre : reconnexion seule, l'appel revient", async () => {
    inCall();
    await authorized();
    await until(() => rpc.discordMembers() !== undefined);
    fake.drop();
    await until(() => rpc.discordPhotos() === "off");
    assert.equal(rpc.discordMembers(), undefined);
    await until(() => rpc.discordPhotos() === "on" && rpc.discordMembers() !== undefined);
    assert.equal(sent("AUTHENTICATE").length, 2);
  });

  it("photo : adresse 64 px sur le CDN, rien pour un inconnu ou sans photo", async () => {
    inCall();
    await authorized();
    await until(() => rpc.discordMembers() !== undefined);
    assert.equal(rpc.discordAvatarUrl(A), `https://cdn.exemple/avatars/${A}/${HASH}.png?size=64`);
    assert.equal(rpc.discordAvatarUrl(B), undefined);
    assert.equal(rpc.discordAvatarUrl("999"), undefined);
  });

  it("photo au hash inattendu : ignorée (aucune adresse construite)", async () => {
    fake.channel = { id: "900", voice_states: [voiceStateOf(A, "alice", { avatar: "../../x" })] };
    await authorized();
    await until(() => rpc.discordMembers() !== undefined);
    assert.deepEqual(rpc.discordMembers(), [{ id: A, name: "alice", avatar: false, speaking: false }]);
    assert.equal(rpc.discordAvatarUrl(A), undefined);
  });
});

describe("Discord RPC : état vocal de la tablette", () => {
  const voice = { found: true, connected: true, streaming: false, channel: "Salon", people: ["alice"], muted: false, deafened: false };

  it("hors RPC : l'état lu dans la fenêtre, avec photos « off »", () => {
    assert.deepEqual(rpc.withDiscordMembers(voice), { ...voice, photos: "off" });
    assert.equal(rpc.withDiscordMembers(null), null);
  });

  it("RPC en appel : participants ajoutés, `people` gardé", async () => {
    fake.channel = { id: "900", voice_states: [voiceStateOf(A, "alice", { avatar: HASH })] };
    await authorized();
    await until(() => rpc.discordMembers() !== undefined);
    assert.deepEqual(rpc.withDiscordMembers(voice), {
      ...voice,
      photos: "on",
      members: [{ id: A, name: "alice", avatar: true, speaking: false }],
    });
    assert.deepEqual(rpc.withDiscordMembers({ ...voice, connected: false }), { ...voice, connected: false, photos: "on" });
  });
});
