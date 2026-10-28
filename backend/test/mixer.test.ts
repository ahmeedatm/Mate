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

  it("change le volume d'une appli et renvoie le nouvel état", async () => {
    const res = await server.call("POST", "/system/mixer", { id: "spotify", volume: 25 });
    assert.equal(res.json.apps[1].volume, 25);
    assert.deepEqual(mixerCalls(), [{ id: "spotify", volume: 25 }]);
  });

  it("refuse un volume hors de 0-100", async () => {
    assert.equal((await server.call("POST", "/system/mixer", { id: "master", volume: 140 })).status, 400);
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
});
