import assert from "node:assert/strict";
import zlib from "node:zlib";
import { after, before, beforeEach, describe, it } from "node:test";
import { shrinkPng } from "../src/png-shrink.ts";
import { boardOf, parseMatch } from "../src/valorant-match.ts";
import { JETT, ME, SAGE, SOVA, matchFixture, pngOf, startFakeRiot, type FakeRiot } from "./fake-riot.ts";
import { startServer, TOKEN, type TestServer } from "./server.ts";

/** Relit /valorant jusqu'à ce que `ok` soit vrai (les stats et le tableau arrivent après la première réponse). */
async function until(server: TestServer, ok: (json: any) => boolean, ms = 3000): Promise<any> {
  const stop = Date.now() + ms;
  for (;;) {
    const json = (await server.call("GET", "/valorant")).json;
    if (ok(json)) return json;
    if (Date.now() > stop) assert.fail(`attente dépassée : ${JSON.stringify(json)}`);
    await new Promise((r) => setTimeout(r, 30));
  }
}

const byName = (json: any, name: string | null) => json.players.find((p: any) => p.name === name);
const pngSize = (data: Buffer) => [data.readUInt32BE(16), data.readUInt32BE(20)];

/** Données compressées d'un PNG (blocs IDAT mis bout à bout). */
function idatOf(png: Buffer): Buffer {
  const parts: Buffer[] = [];
  for (let at = 8; at < png.length;) {
    const len = png.readUInt32BE(at);
    if (png.toString("ascii", at + 4, at + 8) === "IDAT") parts.push(png.subarray(at + 8, at + 8 + len));
    at += 12 + len;
  }
  return Buffer.concat(parts);
}

async function image(server: TestServer, route: string) {
  const res = await fetch(`${server.url}${route}`, { headers: { authorization: `Bearer ${TOKEN}` } });
  return { status: res.status, type: res.headers.get("content-type"), data: Buffer.from(await res.arrayBuffer()) };
}

let riot: FakeRiot;
let server: TestServer;
const fresh = async () => {
  await server?.stop();
  server = await startServer({ os: "windows", env: riot.env });
  server.setOs({ riotLockfile: riot.lockfile });
};
before(async () => {
  riot = await startFakeRiot();
});
after(async () => {
  await server?.stop();
  riot.close();
});
beforeEach(async () => {
  riot.reset();
  await fresh();
});

describe("Valorant : stats de tracker en partie", () => {
  it("niveau du compte, identifiant d'agent, meilleur rang, % de victoires de l'acte et groupes", async () => {
    riot.scene.loop = "INGAME";
    const json = await until(server, (j) => byName(j, "Eliot#EUW")?.winRate != null);
    const me = byName(json, "Eliot#EUW");
    // Immortel 1 d'un acte d'avant Ascendant (21) : 24 aujourd'hui.
    assert.deepEqual(
      { agentId: me.agentId, level: me.level, peakTier: me.peakTier, winRate: me.winRate },
      { agentId: SOVA, level: 90, peakTier: 24, winRate: 60 },
    );
    const foe = byName(json, "Adversaire#1");
    assert.deepEqual(
      { agentId: foe.agentId, level: foe.level, peakTier: foe.peakTier, winRate: foe.winRate },
      { agentId: JETT, level: 15, peakTier: 20, winRate: 25 },
    );
    // Incognito : nom caché, stats visibles ; niveau caché par le joueur.
    const hidden = byName(json, null);
    assert.equal(hidden.level, null);
    assert.equal(hidden.tier, 9);
    // Venus ensemble (même groupe dans les présences) : même numéro ; seul dans son groupe : null.
    assert.equal(me.party, 1);
    assert.equal(byName(json, "Kinj#EUW").party, 1);
    assert.equal(foe.party, null);
    assert.equal(json.me.peakTier, 24);
  });

  it("K/D et % tête de ses 3 dernières classées arrivent ensuite, une partie lue une seule fois", async () => {
    riot.scene.loop = "INGAME";
    const first = (await server.call("GET", "/valorant")).json;
    assert.equal(first.players.length, 4);
    const json = await until(server, (j) => byName(j, "Eliot#EUW")?.kd != null && byName(j, "Adversaire#1")?.kd != null);
    assert.deepEqual([byName(json, "Eliot#EUW").kd, byName(json, "Eliot#EUW").hs], [1.2, 25]);
    assert.deepEqual([byName(json, "Adversaire#1").kd, byName(json, "Adversaire#1").hs], [0.25, 10]);
    // Aucune classée : pas de stat.
    assert.equal(byName(json, "Kinj#EUW").kd, null);
    assert.ok(
      riot.seen.some(
        (s) => s.url.includes("/match-history/v1/history/ennemi?") && s.url.includes("queue=competitive") && s.url.includes("endIndex=3"),
      ),
    );
    // m1 est dans l'historique de deux joueurs : lue une fois.
    assert.equal(riot.count("/match-details/v1/matches/m1"), 1);
  });

  it("historique refusé par Riot (429) : tiret, rang gardé, pas redemandé tout de suite", async () => {
    riot.scene.loop = "INGAME";
    riot.scene.historyRefused = true;
    await until(server, (j) => byName(j, "Eliot#EUW")?.kd != null);
    const json = (await server.call("GET", "/valorant")).json;
    assert.equal(byName(json, "Adversaire#1").kd, null);
    assert.equal(byName(json, "Adversaire#1").rank, "Diamant 1");
    await server.call("GET", "/valorant");
    assert.equal(riot.count("/match-history/v1/history/ennemi?"), 1);
  });

  it("combat à mort : rang et agent seulement, pas d'historique demandé", async () => {
    riot.scene.loop = "INGAME";
    riot.scene.queueId = "deathmatch";
    await server.call("GET", "/valorant");
    await new Promise((r) => setTimeout(r, 200));
    const json = (await server.call("GET", "/valorant")).json;
    assert.equal(byName(json, "Eliot#EUW").kd, null);
    assert.equal(riot.count("/match-history/"), 0);
  });
});

describe("Valorant : tableau des scores en fin de partie", () => {
  it("après la partie : les 10 joueurs triés par ACS dans chaque équipe, MVP, incognito sans nom", async () => {
    riot.scene.loop = "INGAME";
    await server.call("GET", "/valorant");
    riot.scene.loop = "MENUS";
    riot.scene.endNotReady = 2;
    const json = await until(server, (j) => j.end?.status === "ready");
    assert.equal(json.state, "menu");
    const end = json.end;
    assert.deepEqual([end.map, end.queue, end.mode, end.won, end.score], ["Summit", "Compétition", "teams", true, { ally: 13, enemy: 7 }]);
    assert.deepEqual(
      end.players.map((p: any) => [p.name, p.ally, p.acs, p.mvp]),
      [
        ["Eliot#EUW", true, 250, true],
        ["Kinj#EUW", true, 150, false],
        ["Adversaire#1", false, 300, true],
        [null, false, 100, false],
      ],
    );
    const me = end.players[0];
    assert.deepEqual(
      [me.agent, me.agentId, me.tier, me.rank, me.kills, me.deaths, me.assists, me.hs, me.adr, me.firstKills, me.me],
      ["Sova", SOVA, 10, "Argent 2", 20, 10, 5, 25, 150, 3, true],
    );
    assert.equal(end.players[1].agentId, SAGE);
    // Retenté jusqu'à ce que Riot ait le détail.
    assert.equal(riot.count("/match-details/v1/matches/jeu-1"), 3);
  });

  it("détail jamais prêt : « missing », puis plus rien après la durée d'affichage", async () => {
    riot.scene.loop = "INGAME";
    await server.call("GET", "/valorant");
    riot.scene.loop = "MENUS";
    riot.scene.endNotReady = -1;
    const json = await until(server, (j) => j.end?.status === "missing");
    assert.deepEqual(json.end.players, []);
    await until(server, (j) => j.end === null, 4000);
  });

  it("une nouvelle file coupe le tableau ; il disparaît seul après la durée d'affichage", async () => {
    riot.scene.loop = "INGAME";
    await server.call("GET", "/valorant");
    riot.scene.loop = "MENUS";
    await until(server, (j) => j.end?.status === "ready");
    riot.scene.partyState = "MATCHMAKING";
    const queued = (await server.call("GET", "/valorant")).json;
    assert.equal(queued.state, "queue");
    assert.equal(queued.end, null);
    riot.scene.partyState = "DEFAULT";
    assert.equal((await server.call("GET", "/valorant")).json.end, null);
  });

  it("le tableau s'efface après la durée d'affichage", async () => {
    riot.scene.loop = "INGAME";
    await server.call("GET", "/valorant");
    riot.scene.loop = "MENUS";
    await until(server, (j) => j.end?.status === "ready");
    await until(server, (j) => j.end === null, 4000);
  });

  it("combat à mort : classement par kills, sans MVP, seul « moi » dans mon camp", () => {
    const board = boardOf(parseMatch(matchFixture("dm")), ME, new Set());
    assert.equal(board.mode, "ffa");
    assert.equal(board.score, null);
    assert.deepEqual(
      board.players.map((p) => [p.name, p.kills, p.ally, p.mvp]),
      [
        ["Adversaire#1", 40, false, false],
        ["Eliot#EUW", 30, true, false],
      ],
    );
  });
});

describe("Valorant : au menu", () => {
  it("mes 5 dernières classées : victoire, score, agent, carte, K/D/A, ACS, RR gagnés ou perdus", async () => {
    const json = await until(server, (j) => j.history?.length === 5 && j.history.every((h: any) => h.acs != null));
    assert.deepEqual(json.history[0], {
      won: true,
      score: { ally: 13, enemy: 7 },
      map: "Summit",
      agent: "Sova",
      agentId: SOVA,
      kills: 20,
      deaths: 10,
      assists: 4,
      acs: 250,
      rr: 18,
    });
    assert.deepEqual(
      json.history.map((h: any) => [h.won, h.rr]),
      [
        [true, 18],
        [false, -15],
        [null, 2],
        [true, 21],
        [false, -19],
      ],
    );
    assert.equal(json.history[1].agent, "Sage");
    assert.ok(riot.seen.some((s) => s.url.startsWith(`/mmr/v1/players/${ME}/competitiveupdates?`) && s.url.includes("queue=competitive")));
  });

  it("en partie : pas d'historique", async () => {
    riot.scene.loop = "INGAME";
    assert.deepEqual((await server.call("GET", "/valorant")).json.history, []);
  });
});

describe("Valorant : images servies par l'ordinateur", () => {
  it("icône d'agent réduite à 128 px, logo de rang ; la tablette ne parle jamais à valorant-api.com", async () => {
    await server.call("GET", "/valorant");
    const agent = await image(server, `/valorant/agent?id=${SOVA}`);
    assert.equal(agent.status, 200);
    assert.equal(agent.type, "image/png");
    assert.deepEqual(pngSize(agent.data), [128, 128]);
    const rank = await image(server, "/valorant/rank?tier=24");
    assert.equal(rank.status, 200);
    assert.deepEqual(pngSize(rank.data), [128, 128]);
    // Deuxième demande : depuis le cache de l'ordinateur.
    const before = riot.count("/agents/");
    await image(server, `/valorant/agent?id=${SOVA}`);
    assert.equal(riot.count("/agents/"), before);
  });

  it("agent inconnu : 404 ; identifiant ou rang invalide : 400 ; sans jeton : 401", async () => {
    assert.equal((await image(server, "/valorant/agent?id=00000000-0000-0000-0000-000000000000")).status, 404);
    assert.equal((await image(server, "/valorant/agent?id=..%2F..%2Fsecret")).status, 400);
    assert.equal((await image(server, "/valorant/rank?tier=99")).status, 400);
    assert.equal((await fetch(`${server.url}/valorant/rank?tier=3`)).status, 401);
  });
});

describe("Réduction d'une image PNG", () => {
  it("divise par la plus petite puissance de deux qui passe sous la taille demandée ; garde la couleur", () => {
    const small = shrinkPng(pngOf(1024, [10, 20, 30, 255]), 128);
    assert.ok(small);
    assert.deepEqual(pngSize(small), [128, 128]);
    // Premier pixel de la première ligne (après l'octet de filtre).
    const raw = zlib.inflateSync(idatOf(small));
    assert.deepEqual([...raw.subarray(1, 5)], [10, 20, 30, 255]);
  });

  it("défait les 5 filtres de ligne, lit le RGB, moyenne chaque bloc", () => {
    // Image RGB 8×8 en dégradé, chaque ligne avec un autre filtre (0 à 4), réduite à 4×4 (blocs de 2×2).
    const size = 8;
    const pixel = (x: number, y: number) => [x * 30, y * 30, (x + y) * 10];
    const rows: number[] = [];
    const prev = (x: number, y: number, c: number) => (x < 0 || y < 0 ? 0 : pixel(x, y)[c]);
    for (let y = 0; y < size; y++) {
      const type = y % 5;
      rows.push(type);
      for (let x = 0; x < size; x++)
        for (let c = 0; c < 3; c++) {
          const [a, b, d] = [prev(x - 1, y, c), prev(x, y - 1, c), prev(x - 1, y - 1, c)];
          const p = a + b - d;
          const paeth =
            Math.abs(p - a) <= Math.abs(p - b) && Math.abs(p - a) <= Math.abs(p - d) ? a : Math.abs(p - b) <= Math.abs(p - d) ? b : d;
          const predictor = [0, a, b, (a + b) >> 1, paeth][type];
          rows.push((pixel(x, y)[c] - predictor) & 0xff);
        }
    }
    const rgb = pngOf(size);
    const ihdr = Buffer.from(rgb.subarray(8, 33));
    ihdr[4 + 4 + 9] = 2;
    const typed = ihdr.subarray(4, 21);
    ihdr.writeUInt32BE(zlib.crc32(typed), 21);
    const idat = Buffer.concat([Buffer.from("IDAT"), zlib.deflateSync(Buffer.from(rows))]);
    const len = Buffer.alloc(4);
    len.writeUInt32BE(idat.length - 4);
    const crc = Buffer.alloc(4);
    crc.writeUInt32BE(zlib.crc32(idat));
    const png = Buffer.concat([
      rgb.subarray(0, 8),
      ihdr,
      len,
      idat,
      crc,
      Buffer.from([0, 0, 0, 0, 0x49, 0x45, 0x4e, 0x44, 0xae, 0x42, 0x60, 0x82]),
    ]);
    const small = shrinkPng(png, 4)!;
    assert.deepEqual(pngSize(small), [4, 4]);
    const out = zlib.inflateSync(idatOf(small));
    // Bloc (1, 2) : pixels x 2-3, y 4-5 ; moyenne exacte du dégradé.
    const at = 2 * (4 * 4 + 1) + 1 + 1 * 4;
    assert.deepEqual([...out.subarray(at, at + 4)], [75, 135, 70, 255]);
  });

  it("déjà assez petite : rendue telle quelle ; pas un PNG lisible : null", () => {
    const png = pngOf(64);
    assert.equal(shrinkPng(png, 128), png);
    assert.equal(shrinkPng(Buffer.from("pas une image"), 128), null);
  });
});
