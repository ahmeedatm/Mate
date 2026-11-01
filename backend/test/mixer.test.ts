import assert from "node:assert/strict";
import { after, before, beforeEach, describe, it } from "node:test";
import type { MixerChannel, MixerState } from "../src/screens.ts";
import { startServer, type TestServer } from "./server.ts";

let server: TestServer;
before(async () => {
  server = await startServer({ os: "windows" });
});
after(async () => {
  await server?.stop();
});

const channel = (id: string, volume: number, muted = false): MixerChannel => ({ id, name: id, volume, muted, level: 0 });
const mixer = (apps: MixerChannel[]): MixerState => ({ master: channel("master", 50), mic: channel("mic", 80), apps });

beforeEach(async () => {
  server.setOs({ mixer: mixer([channel("discord", 100), channel("spotify", 40)]) });
  for (const s of (await server.call("GET", "/system/mixer/scenes")).json.scenes)
    await server.call("DELETE", `/system/mixer/scenes/${s.id}`);
  server.clearOsCalls();
});

const mixerCalls = () =>
  server
    .osCalls()
    .filter((c) => c.fn === "mixer")
    .map((c) => c.args[0]);

describe("Mélangeur de l'onglet Son", () => {
  it("donne la sortie, le micro et les applis", async () => {
    const res = await server.call("GET", "/system/mixer");
    assert.equal(res.status, 200);
    assert.deepEqual(
      res.json.apps.map((a: MixerChannel) => [a.id, a.volume]),
      [
        ["discord", 100],
        ["spotify", 40],
      ],
    );
    assert.equal(res.json.master.volume, 50);
  });

  it("donne l'appli au premier plan, ou null quand aucune ne joue", async () => {
    server.setOs({ mixer: { ...mixer([channel("discord", 100), channel("spotify", 40)]), front: "spotify" } });
    assert.equal((await server.call("GET", "/system/mixer")).json.front, "spotify");
    server.setOs({ mixer: { ...mixer([channel("discord", 100)]), front: null } });
    assert.equal((await server.call("GET", "/system/mixer")).json.front, null);
  });

  it("change le volume d'une appli et renvoie le nouvel état", async () => {
    const res = await server.call("POST", "/system/mixer", { id: "spotify", volume: 25 });
    assert.equal(res.json.apps[1].volume, 25);
    assert.deepEqual(mixerCalls(), [{ id: "spotify", volume: 25 }]);
  });

  it("refuse un volume hors de 0-100", async () => {
    assert.equal((await server.call("POST", "/system/mixer", { id: "master", volume: 140 })).status, 400);
  });
});

describe("Groupe d'applis", () => {
  it("règle toutes les applis du groupe d'un coup et renvoie le nouvel état", async () => {
    const res = await server.call("POST", "/system/mixer/group", { ids: ["discord", "spotify"], volume: 30 });
    assert.equal(res.status, 200);
    assert.deepEqual(
      res.json.apps.map((a: MixerChannel) => [a.id, a.volume]),
      [
        ["discord", 30],
        ["spotify", 30],
      ],
    );
    assert.deepEqual(mixerCalls(), [
      { id: "discord", volume: 30 },
      { id: "spotify", volume: 30 },
    ]);
  });

  it("coupe le groupe", async () => {
    const res = await server.call("POST", "/system/mixer/group", { ids: ["discord", "spotify"], muted: true });
    assert.deepEqual(
      res.json.apps.map((a: MixerChannel) => a.muted),
      [true, true],
    );
  });

  it("ignore une appli du groupe qui ne joue pas", async () => {
    const res = await server.call("POST", "/system/mixer/group", { ids: ["discord", "jeu-ferme"], volume: 10 });
    assert.equal(res.status, 200);
    assert.deepEqual(mixerCalls(), [{ id: "discord", volume: 10 }]);
  });

  it("ne touche ni la sortie ni le micro", async () => {
    await server.call("POST", "/system/mixer/group", { ids: ["master", "mic", "discord"], volume: 5 });
    assert.deepEqual(mixerCalls(), [{ id: "discord", volume: 5 }]);
  });

  it("refuse un groupe vide, sans réglage ou hors de 0-100", async () => {
    assert.equal((await server.call("POST", "/system/mixer/group", { ids: [], volume: 10 })).status, 400);
    assert.equal((await server.call("POST", "/system/mixer/group", { ids: ["discord"] })).status, 400);
    assert.equal((await server.call("POST", "/system/mixer/group", { ids: ["discord"], volume: 101 })).status, 400);
  });

  it("répond 501 quand l'ordinateur n'a pas de volume par appli (Mac)", async () => {
    server.setOs({ mixer: { master: channel("master", 50), mic: channel("mic", 80), apps: null } });
    assert.equal((await server.call("POST", "/system/mixer/group", { ids: ["discord"], volume: 10 })).status, 501);
  });
});

describe("Scènes", () => {
  it("enregistre la sortie et les applis, pas le micro", async () => {
    const scene = (await server.call("POST", "/system/mixer/scenes", { name: "Jeu" })).json;
    assert.equal(scene.name, "Jeu");
    assert.deepEqual(scene.master, { volume: 50, muted: false });
    assert.deepEqual(Object.keys(scene.apps), ["discord", "spotify"]);
    assert.equal((await server.call("GET", "/system/mixer/scenes")).json.scenes.length, 1);
  });

  it("un même nom remplace la scène", async () => {
    const first = (await server.call("POST", "/system/mixer/scenes", { name: "Jeu" })).json;
    await server.call("POST", "/system/mixer", { id: "discord", volume: 70 });
    const second = (await server.call("POST", "/system/mixer/scenes", { name: "jeu" })).json;
    assert.equal(second.id, first.id);
    const scenes = (await server.call("GET", "/system/mixer/scenes")).json.scenes;
    assert.equal(scenes.length, 1);
    assert.equal(scenes[0].apps.discord.volume, 70);
  });

  it("rappelle les volumes des applis présentes, signale les absentes", async () => {
    const scene = (await server.call("POST", "/system/mixer/scenes", { name: "Jeu" })).json;
    server.setOs({ mixer: mixer([channel("discord", 30, true)]) });
    server.clearOsCalls();
    const res = await server.call("POST", `/system/mixer/scenes/${scene.id}/apply`);
    assert.equal(res.status, 200);
    assert.deepEqual(res.json.missing, ["spotify"]);
    assert.deepEqual(res.json.mixer.apps[0], channel("discord", 100));
    assert.deepEqual(mixerCalls(), [{ id: "discord", volume: 100, muted: false }], "sortie déjà au bon volume : pas touchée");
  });

  it("scène inconnue : 404", async () => {
    assert.equal((await server.call("POST", "/system/mixer/scenes/nope/apply")).status, 404);
  });

  it("refuse un nom vide", async () => {
    assert.equal((await server.call("POST", "/system/mixer/scenes", { name: "   " })).status, 400);
  });

  it("réordonne : l'ordre donné d'abord, les oubliées à la suite, les inconnues ignorées", async () => {
    const ids: string[] = [];
    for (const name of ["Jeu", "Travail", "Nuit"]) ids.push((await server.call("POST", "/system/mixer/scenes", { name })).json.id);
    const res = await server.call("PUT", "/system/mixer/scenes/order", { ids: [ids[2], "nope", ids[0]] });
    assert.equal(res.status, 200);
    const names = (s: { name: string }[]) => s.map((x) => x.name);
    assert.deepEqual(names(res.json.scenes), ["Nuit", "Jeu", "Travail"]);
    assert.deepEqual(names((await server.call("GET", "/system/mixer/scenes")).json.scenes), ["Nuit", "Jeu", "Travail"]);
  });

  it("mettre à jour une scène (même nom) garde sa place", async () => {
    for (const name of ["Jeu", "Travail"]) await server.call("POST", "/system/mixer/scenes", { name });
    await server.call("POST", "/system/mixer/scenes", { name: "Jeu" });
    assert.deepEqual(
      (await server.call("GET", "/system/mixer/scenes")).json.scenes.map((s: { name: string }) => s.name),
      ["Jeu", "Travail"],
    );
  });

  it("refuse un ordre mal formé", async () => {
    assert.equal((await server.call("PUT", "/system/mixer/scenes/order", {})).status, 400);
    assert.equal((await server.call("PUT", "/system/mixer/scenes/order", { ids: [{ id: "Jeu" }] })).status, 400);
  });
});
