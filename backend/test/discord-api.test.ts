import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { after, before, describe, it } from "node:test";
import { FAKE_TOKEN, PNG, SELF_ID, startFakeDiscord, voiceStateOf, type FakeDiscord } from "./fake-discord-rpc.ts";
import { TOKEN, startServer, type TestServer } from "./server.ts";

const A = "200000000000000002";
const B = "300000000000000003";
const HASH = "0123456789abcdef0123456789abcdef";
const VOICE = {
  found: true,
  connected: true,
  streaming: false,
  channel: "Salon",
  people: ["alice", "bob"],
  muted: false,
  deafened: false,
  mic: null,
  deaf: null,
};

async function until(check: () => Promise<boolean>, ms = 5000) {
  const deadline = Date.now() + ms;
  while (!(await check())) {
    if (Date.now() > deadline) throw new Error("condition jamais atteinte");
    await new Promise((r) => setTimeout(r, 50));
  }
}

describe("Discord : photos et qui parle, par l'API", () => {
  let fake: FakeDiscord;
  let server: TestServer;
  before(async () => {
    fake = await startFakeDiscord();
    fake.channel = {
      id: "900",
      voice_states: [voiceStateOf(SELF_ID, "moi"), voiceStateOf(A, "alice", { avatar: HASH }), voiceStateOf(B, "bob")],
    };
    const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), "companion-discord-api-"));
    fs.writeFileSync(path.join(dataDir, "discord.json"), JSON.stringify({ accessToken: FAKE_TOKEN }));
    server = await startServer({
      dataDir,
      env: { DISCORD_RPC_PORTS: String(fake.port), DISCORD_STREAMKIT_URL: fake.url, DISCORD_CDN_URL: fake.url },
    });
    server.setOs({ voice: { Discord: VOICE } });
  });
  after(async () => {
    await server.stop();
    await fake.close();
    fs.rmSync(server.dataDir, { recursive: true, force: true });
  });

  it("l'appel lu dans la fenêtre gagne les participants du RPC, `people` reste", async () => {
    await until(async () => (await server.call("GET", "/deck/voice?app=Discord")).json.voice?.members !== undefined);
    const { json } = await server.call("GET", "/deck/voice?app=Discord");
    assert.deepEqual(json.voice, {
      ...VOICE,
      photos: "on",
      members: [
        { id: A, name: "alice", avatar: true, speaking: false },
        { id: B, name: "bob", avatar: false, speaking: false },
      ],
    });
  });

  it("qui parle : route légère", async () => {
    fake.dispatch("SPEAKING_START", { channel_id: "900", user_id: B });
    await until(async () => (await server.call("GET", "/discord/speaking")).json.speaking?.includes(B));
    fake.dispatch("SPEAKING_STOP", { channel_id: "900", user_id: B });
    await until(async () => (await server.call("GET", "/discord/speaking")).json.speaking?.length === 0);
  });

  it("photo : téléchargée une fois puis servie depuis le cache", async () => {
    for (let i = 0; i < 2; i++) {
      const res = await fetch(`${server.url}/discord/avatar?id=${A}`, { headers: { authorization: `Bearer ${TOKEN}` } });
      assert.equal(res.status, 200);
      assert.equal(res.headers.get("content-type"), "image/png");
      assert.deepEqual(Buffer.from(await res.arrayBuffer()), PNG);
    }
    assert.deepEqual(fake.avatarHits, [`/avatars/${A}/${HASH}.png?size=64`]);
  });

  it("photo : 404 sans photo ou inconnu, 400 pour un identifiant invalide", async () => {
    assert.equal((await server.call("GET", `/discord/avatar?id=${B}`)).status, 404);
    assert.equal((await server.call("GET", "/discord/avatar?id=999")).status, 404);
    assert.equal((await server.call("GET", "/discord/avatar?id=..%2Fx")).status, 400);
  });

  it("sortie du son de Discord : liste, choix, sortie inconnue, corps invalide", async () => {
    const list = await server.call("GET", "/discord/outputs");
    assert.equal(list.status, 200);
    assert.deepEqual(
      list.json.outputs.map((o: { id: string; current: boolean }) => [o.id, o.current]),
      [
        ["default", true],
        ["BuiltInSpeakerDevice", false],
        ["casque-bt", false],
      ],
    );
    const set = await server.call("POST", "/discord/output", { id: "casque-bt" });
    assert.equal(set.status, 200);
    assert.equal(set.json.outputs.find((o: { current: boolean }) => o.current).id, "casque-bt");
    const gone = await server.call("POST", "/discord/output", { id: "partie" });
    assert.equal(gone.status, 404);
    assert.match(gone.json.error, /n'existe plus/);
    assert.equal((await server.call("POST", "/discord/output", { id: "" })).status, 400);
    assert.equal((await server.call("POST", "/discord/output", {})).status, 400);
  });

  it("jeton d'appairage exigé, et le jeton Discord ne sort jamais", async () => {
    for (const route of ["/discord/speaking", `/discord/avatar?id=${A}`, "/discord/outputs"])
      assert.equal((await server.call("GET", route, undefined, false)).status, 401);
    assert.equal((await server.call("POST", "/discord/authorize", undefined, false)).status, 401);
    assert.equal((await server.call("POST", "/discord/output", { id: "default" }, false)).status, 401);
    assert.ok(!server.output().includes(FAKE_TOKEN));
    assert.ok(!JSON.stringify((await server.call("GET", "/deck/voice?app=Discord")).json).includes(FAKE_TOKEN));
  });
});

describe("Discord : autorisation depuis la tablette", () => {
  let fake: FakeDiscord;
  let server: TestServer;
  before(async () => {
    fake = await startFakeDiscord();
    server = await startServer({
      env: { DISCORD_RPC_PORTS: String(fake.port), DISCORD_STREAMKIT_URL: fake.url, DISCORD_CDN_URL: fake.url },
    });
  });
  after(async () => {
    await server.stop();
    await fake.close();
  });

  it("sans jeton : pas de connexion au démarrage, photos « off »", async () => {
    server.setOs({ voice: { Discord: VOICE } });
    assert.equal((await server.call("GET", "/deck/voice?app=Discord")).json.voice.photos, "off");
    assert.equal(fake.origins.length, 0);
  });

  it("sans jeton : la sortie du son demande l'accès d'abord (409)", async () => {
    const res = await server.call("GET", "/discord/outputs");
    assert.equal(res.status, 409);
    assert.match(res.json.error, /pas encore donné l'accès/);
  });

  it("refus dans Discord : message pour la tablette", async () => {
    fake.authorize = "refuse";
    const res = await server.call("POST", "/discord/authorize");
    assert.equal(res.status, 409);
    assert.match(res.json.error, /refusé/);
  });

  it("accord : photos « on », jeton gardé sur l'ordinateur seulement", async () => {
    fake.authorize = "accept";
    const res = await server.call("POST", "/discord/authorize");
    assert.equal(res.status, 200);
    assert.deepEqual(res.json, { photos: "on" });
    assert.equal(JSON.parse(fs.readFileSync(path.join(server.dataDir, "discord.json"), "utf8")).accessToken, FAKE_TOKEN);
    assert.ok(!JSON.stringify(res.json).includes(FAKE_TOKEN));
  });
});

describe("Discord : ordinateur sans pilotage", () => {
  it("routes en 501 (ni Mac ni Windows)", async () => {
    const server = await startServer({ os: "other" });
    try {
      assert.equal((await server.call("GET", "/discord/speaking")).status, 501);
      assert.equal((await server.call("POST", "/discord/authorize")).status, 501);
      assert.equal((await server.call("GET", "/discord/outputs")).status, 501);
    } finally {
      await server.stop();
    }
  });
});
