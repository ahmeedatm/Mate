import assert from "node:assert/strict";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { afterEach, describe, it } from "node:test";
import { TILE_SYMBOLS } from "../src/screens.ts";
import { startServer, type TestServer } from "./server.ts";

type Tile = { id: string; label?: string; type?: string; app: string | null };
type Page = { title: string; apps: boolean; editable: boolean; keyPage: number | null; tiles: Tile[] };

const OUTLOOK_NAME = "Microsoft Outlook";

let server: TestServer | undefined;
afterEach(async () => {
  await server?.stop();
  server = undefined;
});

const start = async (installed: string[] = []) => {
  server = await startServer();
  server.setOs({ installed });
  return server;
};
const pages = async (s: TestServer): Promise<Page[]> => (await s.call("GET", "/deck")).json.pages;
const persoLabels = async (s: TestServer) =>
  (await pages(s)).find((p) => p.editable)!.tiles.flatMap((t) => (t.type === "empty" ? [] : [t.label]));
const keyPages = async (s: TestServer) => (await pages(s)).filter((p) => p.keyPage !== null);
const pluginsOff = async (s: TestServer): Promise<string[]> => (await s.call("GET", "/deck/features")).json.off;
const filled = (page: Page) => page.tiles.filter((t) => t.type !== "empty");
const taskLabels = async (s: TestServer): Promise<string[]> =>
  (await s.call("GET", "/tasks/recurring")).json.tasks.map((t: { label: string }) => t.label);
const exists = (s: TestServer, name: string) =>
  fs.access(path.join(s.dataDir, name)).then(
    () => true,
    () => false,
  );

describe("démo : personas", () => {
  it("pose touches, tâches et agenda du persona, puis rend l'état d'avant", async () => {
    const s = await start();
    await s.call("PUT", "/deck/keys/0", { label: "Ma touche", steps: [{ type: "text", text: "a" }] });
    await s.call("POST", "/tasks", { label: "Ma tâche", days: [1, 2, 3, 4, 5, 6, 7] });

    const set = await s.call("POST", "/demo/persona", { persona: "nour" });
    assert.equal(set.status, 200);
    assert.equal(set.json.persona, "nour");
    assert.equal(set.json.keys[0].id, "k-0");
    assert.ok(set.json.keys.length >= 6);
    // Les touches des pages nommées, que lisent les widgets Touches des écrans du persona.
    assert.deepEqual(
      set.json.pages.map((p: { title: string }) => p.title),
      ["Excel", "Libellés", "Dossiers clients"],
    );
    assert.equal(set.json.pages[1].keys[0].id, "k2-0");
    assert.equal(set.json.pages[0].keys.find((k: { label: string }) => k.label === "Rechercher").symbol, "search");
    assert.deepEqual(
      await persoLabels(s),
      set.json.keys.map((k: { label: string }) => k.label),
    );
    assert.ok(!(await taskLabels(s)).includes("Ma tâche"));
    assert.ok((await taskLabels(s)).includes("Rapprochement bancaire"));
    assert.ok(
      (await exists(s, "events.json")) && JSON.parse(await fs.readFile(path.join(s.dataDir, "events.json"), "utf8")).items.length > 0,
    );

    const quit = await s.call("DELETE", "/demo");
    assert.deepEqual(quit.json, { restored: true });
    assert.deepEqual(await persoLabels(s), ["Ma touche"]);
    assert.ok((await taskLabels(s)).includes("Ma tâche"));
    assert.ok(!(await taskLabels(s)).includes("Rapprochement bancaire"));
    // Pas d'agenda ni de page Apps enregistrés avant la démo : ils n'existent pas après.
    assert.equal(await exists(s, "events.json"), false);
    assert.equal(await exists(s, "apps.json"), false);
    assert.equal(await exists(s, "demo.json"), false);
  });

  it("chaque touche de chaque persona a un symbole connu de la tablette", async () => {
    const s = await start();
    const known: readonly string[] = TILE_SYMBOLS;
    for (const persona of ["nour", "gwen", "camille", "aimee", "sam", "sacha"]) {
      const set = await s.call("POST", "/demo/persona", { persona });
      assert.equal(set.status, 200, persona);
      const posed = [...set.json.keys, ...set.json.pages.flatMap((p: { keys: unknown[] }) => p.keys)] as {
        label: string;
        symbol?: string;
      }[];
      for (const k of posed) assert.ok(k.symbol && known.includes(k.symbol), `${persona} : ${k.label} (${k.symbol})`);
      // Le deck rend le même symbole à la tablette (un nom hors liste y serait écarté).
      const tiles = (await pages(s)).flatMap((p) => p.tiles.filter((t) => t.type !== "empty")) as (Tile & { symbol?: string | null })[];
      for (const t of tiles.filter((t) => /^k\d*-\d+$/.test(t.id))) assert.ok(t.symbol, `${persona} : ${t.label} sans symbole dans /deck`);
    }
  });

  it("changer de persona garde la sauvegarde d'avant la démo", async () => {
    const s = await start();
    await s.call("PUT", "/deck/keys/0", { label: "Ma touche", steps: [{ type: "text", text: "a" }] });
    await s.call("POST", "/demo/persona", { persona: "gwen" });
    const sam = await s.call("POST", "/demo/persona", { persona: "sam" });
    assert.deepEqual(
      await persoLabels(s),
      sam.json.keys.map((k: { label: string }) => k.label),
    );
    await s.call("DELETE", "/demo");
    assert.deepEqual(await persoLabels(s), ["Ma touche"]);
  });

  it("pose les pages de touches nommées du persona, éditables, puis les retire", async () => {
    const s = await start();
    await s.call("POST", "/demo/persona", { persona: "nour" });
    const named = await keyPages(s);
    assert.deepEqual(
      named.map((p) => p.title),
      ["Excel", "Libellés", "Dossiers clients"],
    );
    assert.ok(named.every((p, n) => p.editable && p.keyPage === n && p.tiles[0].id === `k${n + 1}-0` && filled(p).length >= 5));

    // Éditée depuis la tablette comme Perso, une touche d'une page nommée est lue, lancée et retirée.
    const put = await s.call("PUT", "/deck/pages/1/keys/9", { label: "Ma touche", steps: [{ type: "text", text: "a" }] });
    assert.equal(put.status, 200);
    assert.equal((await s.call("GET", "/deck/pages/1/keys/9")).json.key.label, "Ma touche");
    assert.equal((await keyPages(s))[1].tiles[9].label, "Ma touche");
    assert.equal((await s.call("POST", "/deck/run", { id: "k2-9" })).status, 200);
    assert.equal((await s.call("DELETE", "/deck/pages/1/keys/9")).status, 200);
    assert.equal((await keyPages(s))[1].tiles[9].type, "empty");
    // Perso n'est pas touchée.
    assert.equal((await persoLabels(s)).includes("Ma touche"), false);

    assert.equal((await s.call("GET", "/deck/pages/7/keys/0")).status, 404);
    assert.equal((await s.call("PUT", "/deck/pages/7/keys/0", { label: "x", steps: [{ type: "text", text: "a" }] })).status, 404);

    // Un autre persona remplace les pages ; quitter les retire.
    await s.call("POST", "/demo/persona", { persona: "camille" });
    assert.deepEqual(
      (await keyPages(s)).map((p) => p.title),
      ["Travail", "Maison"],
    );
    await s.call("POST", "/demo/persona", { persona: "sam" });
    assert.deepEqual(
      (await keyPages(s)).map((p) => p.title),
      ["Mes touches"],
    );
    assert.ok(filled((await keyPages(s))[0]).length >= 6);
    await s.call("DELETE", "/demo");
    assert.deepEqual(await keyPages(s), []);
    assert.equal(await exists(s, "pages.json"), false);
  });

  it("coupe les plugins du persona, les choix d'avant la démo sont rendus", async () => {
    const s = await start();
    await s.call("POST", "/plugins", { choices: { pad: false } });

    await s.call("POST", "/demo/persona", { persona: "nour" });
    const off = await pluginsOff(s);
    for (const id of ["discord", "mixer", "pad", "media", "claude"]) assert.ok(off.includes(id), id);
    assert.ok(!off.includes("open"));
    assert.ok((await s.call("GET", "/plugins")).json.plugins.every((p: { fresh: boolean }) => !p.fresh));

    await s.call("POST", "/demo/persona", { persona: "sacha" });
    const sacha = await pluginsOff(s);
    assert.ok(!sacha.includes("claude") && !sacha.includes("open"));

    await s.call("DELETE", "/demo");
    assert.deepEqual(await pluginsOff(s), ["pad"]);
  });

  it("sans choix de plugins avant la démo, rien n'est choisi après", async () => {
    const s = await start();
    await s.call("POST", "/demo/persona", { persona: "sam" });
    assert.ok((await pluginsOff(s)).length >= 5);
    await s.call("DELETE", "/demo");
    assert.deepEqual(await pluginsOff(s), []);
    assert.equal(await exists(s, "plugins.json"), false);
  });

  it("la page Apps ne garde que les applis installées", async () => {
    const s = await start(["Microsoft Excel", "Microsoft Word"]);
    await s.call("POST", "/demo/persona", { persona: "nour" });
    const apps = (await pages(s)).find((p) => p.apps)!.tiles.map((t) => t.app);
    assert.deepEqual(apps, ["Microsoft Excel", "Microsoft Word"]);
  });

  it("quitter sans démo en cours ne change rien", async () => {
    const s = await start();
    await s.call("PUT", "/deck/keys/0", { label: "Ma touche", steps: [{ type: "text", text: "a" }] });
    assert.deepEqual((await s.call("DELETE", "/demo")).json, { restored: false });
    assert.deepEqual(await persoLabels(s), ["Ma touche"]);
  });

  it("Aimée a une réunion entre 5 et 7 minutes après le choix, à la minute pleine (alerte de réunion)", async () => {
    const s = await start();
    const before = Date.now();
    await s.call("POST", "/demo/persona", { persona: "aimee" });
    const after = Date.now();
    const events = (await s.call("GET", "/calendar?days=2")).json.events as {
      title: string;
      startMs: number;
      endMs: number;
      allDay: boolean;
    }[];
    const meeting = events.find((e) => e.title === "Point client Boréal");
    assert.ok(meeting && !meeting.allDay);
    assert.ok(meeting.startMs - after >= 5 * 60_000 && meeting.startMs - before <= 7 * 60_000);
    assert.equal(meeting.startMs % 60_000, 0);
    assert.ok(meeting.endMs > meeting.startMs);
    // Triés par début, comme l'agenda personnel : la tablette prend le premier à venir comme « prochain rendez-vous ».
    const starts = events.map((e) => e.startMs);
    assert.deepEqual(
      starts,
      [...starts].sort((a, b) => a - b),
    );
    // Les autres personas n'ont pas cette réunion.
    await s.call("POST", "/demo/persona", { persona: "nour" });
    const nour = (await s.call("GET", "/calendar?days=2")).json.events as { title: string }[];
    assert.ok(!nour.some((e) => e.title === "Point client Boréal"));
  });

  it("les personas portent les nouveaux noms, les anciens sont refusés", async () => {
    const s = await start();
    for (const persona of ["nour", "gwen", "camille", "aimee", "sam", "sacha"]) {
      assert.equal((await s.call("POST", "/demo/persona", { persona })).status, 200, persona);
    }
    for (const persona of ["sylvie", "julie", "thomas", "karim", "leo"]) {
      assert.equal((await s.call("POST", "/demo/persona", { persona })).status, 400, persona);
    }
  });

  it("refuse un persona inconnu et l'absence de jeton", async () => {
    const s = await start();
    assert.equal((await s.call("POST", "/demo/persona", { persona: "inconnu" })).status, 400);
    assert.equal((await s.call("POST", "/demo/persona", { persona: "sacha" }, false)).status, 401);
    assert.equal((await s.call("DELETE", "/demo", undefined, false)).status, 401);
  });
});

describe("démo : données fictives à la place de celles de l'ordinateur", () => {
  const features = async (s: TestServer) => (await s.call("GET", "/deck/features")).json;
  const display = { id: 1, label: "Réel", main: true, x: 0, y: 0, w: 1920, h: 1080 };
  const realWindows = {
    apps: [
      {
        key: "Vrai",
        name: "Vrai",
        windows: [{ index: 1, title: "Document privé", minimized: false, fullscreen: false, display: 1, elsewhere: false }],
      },
    ],
    displays: [display],
  };
  const realMedia = {
    app: "Vrai",
    name: "Vrai",
    current: true,
    title: "Morceau privé",
    artist: "",
    album: "",
    hasThumb: false,
    status: "playing",
    canPlayPause: true,
    canNext: true,
    canPrevious: true,
    canSeek: false,
    positionMs: 0,
    durationMs: 1000,
  };
  const mediaTitle = async (s: TestServer) => (await s.call("GET", "/system/media")).json.sessions[0]?.title;

  it("onglets GitLab, Jira et Agents : seulement chez Sacha, même si l'ordinateur les offre, rendus en quittant", async () => {
    server = await startServer({
      env: { GITLAB_TOKEN: "jeton-de-test", JIRA_SITE: "http://127.0.0.1:1", JIRA_EMAIL: "a@b.c", JIRA_API_TOKEN: "x" },
    });
    server.setOs({ installed: [] });
    const s = server;
    assert.equal((await features(s)).gitlab, true);
    assert.equal((await features(s)).jira, true);

    for (const persona of ["nour", "gwen", "camille", "aimee", "sam"]) {
      await s.call("POST", "/demo/persona", { persona });
      const f = await features(s);
      assert.equal(f.gitlab, false, `${persona} gitlab`);
      assert.equal(f.agents, false, `${persona} agents`);
      assert.equal(f.jira, persona === "aimee", `${persona} jira`);
    }
    await s.call("POST", "/demo/persona", { persona: "sacha" });
    const f = await features(s);
    assert.deepEqual([f.gitlab, f.jira, f.agents], [true, true, true]);
    for (const id of ["claude", "jira", "open"]) assert.ok(!f.off.includes(id), id);
    for (const id of ["discord", "mixer", "pad", "media"]) assert.ok(f.off.includes(id), id);

    await s.call("DELETE", "/demo");
    const after = await features(s);
    assert.deepEqual([after.gitlab, after.jira], [true, true]);
  });

  it("GitLab : MR fictives, la fusion les retire sans appeler GitLab, les vraies sont rendues après", async () => {
    const s = await start();
    assert.equal((await s.call("GET", "/gitlab/mrs")).json.connected, false);
    await s.call("POST", "/demo/persona", { persona: "sacha" });
    const mrs = (await s.call("GET", "/gitlab/mrs")).json;
    assert.equal(mrs.connected, true);
    assert.ok(mrs.mine.length >= 2 && mrs.review.length >= 1);
    assert.ok(mrs.mine.some((m: { mergeable: boolean }) => m.mergeable));
    const merged = mrs.mine.find((m: { mergeable: boolean }) => m.mergeable);
    assert.equal(merged.project.includes("example"), false);
    const res = await s.call("POST", "/gitlab/mrs/merge", { project: merged.project, iid: merged.iid, sha: merged.sha });
    assert.equal(res.status, 200);
    assert.ok(!(await s.call("GET", "/gitlab/mrs")).json.mine.some((m: { iid: number }) => m.iid === merged.iid));
    assert.equal((await s.call("POST", "/gitlab/mrs/open", { project: merged.project, iid: 1 })).status, 200);
    assert.deepEqual(s.osCalls(), []);

    await s.call("DELETE", "/demo");
    assert.equal((await s.call("GET", "/gitlab/mrs")).json.connected, false);
  });

  it("Jira : tickets fictifs de Sacha et d'Aimée, un ticket se déplace, 501 de nouveau après", async () => {
    const s = await start();
    assert.equal((await s.call("GET", "/jira/issues")).status, 501);
    await s.call("POST", "/demo/persona", { persona: "sacha" });
    const keys = async () =>
      (await s.call("GET", "/jira/issues")).json.columns.flatMap((c: { title: string; issues: { key: string }[] }) =>
        c.issues.map((i) => `${c.title}/${i.key}`),
      );
    assert.ok((await keys()).includes("À faire/FACT-236"));
    const { transitions } = (await s.call("GET", "/jira/issues/FACT-236/transitions")).json;
    const toDone = transitions.find((t: { to: string }) => t.to === "Terminé");
    assert.ok(toDone && !transitions.some((t: { to: string }) => t.to === "À faire"));
    assert.equal((await s.call("POST", "/jira/issues/FACT-236/transitions", { id: toDone.id })).status, 200);
    assert.ok((await keys()).includes("Terminé/FACT-236"));
    assert.equal((await s.call("POST", "/jira/issues/FACT-236/open")).status, 200);
    assert.deepEqual(s.osCalls(), []);

    await s.call("POST", "/demo/persona", { persona: "aimee" });
    const aimee: string[] = await keys();
    assert.ok(aimee.some((k) => k.includes("ATLAS-")) && !aimee.some((k) => k.includes("FACT-")));

    await s.call("DELETE", "/demo");
    assert.equal((await s.call("GET", "/jira/issues")).status, 501);
  });

  it("agents Claude : sessions fictives de Sacha, actions sans toucher à l'ordinateur", async () => {
    const s = await start();
    await s.call("POST", "/demo/persona", { persona: "sacha" });
    const agents = (await s.call("GET", "/agents")).json;
    assert.ok(agents.sessions.length >= 4);
    for (const state of ["working", "waiting", "done"])
      assert.ok(
        agents.sessions.some((a: { state: string }) => a.state === state),
        state,
      );
    assert.equal(agents.counts.waiting, 1);
    const idle = agents.sessions.find((a: { state: string }) => a.state === "idle");
    assert.equal((await s.call("POST", "/agents/send", { session: idle.id, text: "continue" })).status, 200);
    assert.equal((await s.call("GET", "/agents")).json.counts.idle, 0);
    assert.deepEqual(s.osCalls(), []);

    await s.call("POST", "/demo/persona", { persona: "nour" });
    assert.equal((await s.call("GET", "/agents")).json.sessions.length, 0);
    await s.call("DELETE", "/demo");
    assert.ok(!(await s.call("GET", "/agents")).json.sessions?.some?.((a: { id: string }) => a.id.startsWith("demo-")));
  });

  it("Ouvertes : fenêtres et écrans fictifs, mettre devant ou fermer change l'état sans appeler l'OS", async () => {
    const s = await start();
    s.setOs({ openWindows: realWindows });
    const titles = async () =>
      (await s.call("GET", "/windows/open")).json.apps.flatMap((a: { windows: { title: string }[] }) => a.windows.map((w) => w.title));
    assert.deepEqual(await titles(), ["Document privé"]);

    await s.call("POST", "/demo/persona", { persona: "nour" });
    const list = (await s.call("GET", "/windows/open")).json;
    const names = list.apps.map((a: { name: string }) => a.name);
    assert.ok(names.includes("Microsoft Excel") && names.includes(OUTLOOK_NAME));
    assert.ok(!(await titles()).includes("Document privé"));
    const outlook = list.apps.find((a: { name: string }) => a.name === OUTLOOK_NAME);
    const win = outlook.windows[0];
    s.clearOsCalls();
    const after = await s.call("POST", "/windows/open", {
      key: outlook.key,
      action: "focus",
      window: { index: win.index, title: win.title },
    });
    assert.equal(after.status, 200);
    const front = after.json.apps
      .flatMap((a: { windows: { index: number; front?: boolean }[] }) => a.windows)
      .filter((w: { front?: boolean }) => w.front);
    assert.deepEqual(
      front.map((w: { index: number }) => w.index),
      [win.index],
    );
    await s.call("POST", "/windows/open", {
      key: outlook.key,
      action: "close",
      window: { index: win.index, title: win.title },
      list: false,
    });
    assert.ok(!(await s.call("GET", "/windows/open")).json.apps.some((a: { name: string }) => a.name === OUTLOOK_NAME));
    assert.deepEqual(s.osCalls(), []);

    // Aimée : un navigateur à vingt onglets, sur deux écrans.
    await s.call("POST", "/demo/persona", { persona: "aimee" });
    assert.equal((await s.call("GET", "/browser/tabs")).json.tabs.length, 20);
    assert.equal((await s.call("GET", "/windows/open")).json.displays.length, 2);

    await s.call("DELETE", "/demo");
    assert.deepEqual(await titles(), ["Document privé"]);
  });

  it("Média, Son et performances : lecture fictive qui réagit aux commandes, vraies valeurs après", async () => {
    const s = await start();
    s.setOs({ media: [realMedia] });
    assert.equal(await mediaTitle(s), "Morceau privé");

    await s.call("POST", "/demo/persona", { persona: "gwen" });
    const first = await mediaTitle(s);
    assert.ok(first && first !== "Morceau privé");
    assert.equal((await s.call("GET", "/system/media")).json.sessions[0].status, "playing");
    s.clearOsCalls();
    await s.call("POST", "/system/media", { action: "play-pause" });
    assert.equal((await s.call("GET", "/system/media")).json.sessions[0].status, "paused");
    await s.call("POST", "/system/media", { action: "next" });
    assert.notEqual(await mediaTitle(s), first);

    assert.equal((await s.call("POST", "/system/audio", { output: 20, muted: true })).json.output, 20);
    const audio = (await s.call("GET", "/system/audio")).json;
    assert.deepEqual([audio.output, audio.muted], [20, true]);
    const mixer = (await s.call("GET", "/system/mixer")).json;
    assert.deepEqual([mixer.master.volume, mixer.master.muted], [20, true]);
    const app = mixer.apps[0];
    await s.call("POST", "/system/mixer", { id: app.id, volume: 33 });
    assert.equal((await s.call("GET", "/system/mixer")).json.apps[0].volume, 33);
    const scene = await s.call("POST", "/system/mixer/scenes", { name: "Réunion" });
    assert.equal(scene.status, 200);
    assert.equal((await s.call("GET", "/system/mixer/scenes")).json.scenes.length, 1);
    const perf = (await s.call("GET", "/system/perf")).json;
    assert.ok(perf.cpu > 0 && perf.ram.total > 0);
    assert.deepEqual(s.osCalls(), []);

    await s.call("DELETE", "/demo");
    assert.equal(await mediaTitle(s), "Morceau privé");
    assert.equal(await exists(s, "mixer.json"), false);
  });

  it("aucun appel, courrier, notifications, presse-papiers et agenda du persona", async () => {
    const s = await start();
    assert.deepEqual((await s.call("GET", "/deck/live")).json.apps, []);
    s.setOs({ clipboard: { kind: "text", text: "Presse-papiers privé" } });

    // Pas d'appel en démo, même chez un persona qui a Teams : la tablette ne passe pas seule sur Réunion.
    for (const persona of ["gwen", "camille", "aimee"]) {
      await s.call("POST", "/demo/persona", { persona });
      const live = (await s.call("GET", "/deck/live")).json;
      assert.deepEqual([live.apps, live.open], [[], []]);
    }
    await s.call("POST", "/demo/persona", { persona: "nour" });

    const mail = (await s.call("GET", "/mail/summary")).json;
    assert.equal(mail.status, "ok");
    assert.ok(mail.unread > 0 && mail.recent.length >= 2);
    const notes = (await s.call("GET", "/notifications")).json.items;
    assert.ok(notes.length >= 2);
    assert.equal((await s.call("DELETE", `/notifications/${notes[0].id}`)).status, 200);
    assert.equal((await s.call("GET", "/notifications")).json.items.length, notes.length - 1);
    const clip = (await s.call("GET", "/system/clipboard")).json;
    assert.equal(clip.kind, "text");
    assert.notEqual(clip.text, "Presse-papiers privé");
    assert.ok((await s.call("GET", "/system/clipboard/history")).json.items.length >= 1);

    const week = (await s.call("GET", "/calendar?days=7")).json;
    assert.equal(week.source, "local");
    assert.ok(week.events.some((e: { title: string }) => e.title === "Point clôture mensuelle"));

    await s.call("DELETE", "/demo");
    assert.equal((await s.call("GET", "/system/clipboard")).json.text, "Presse-papiers privé");
    assert.equal((await s.call("GET", "/mail/summary")).json.status, "no-account");
  });

  it("une démo en cours survit à un redémarrage du compagnon", async () => {
    // Dossier donné : gardé à l'arrêt du serveur (voir test/server.ts).
    const dataDir = await fs.mkdtemp(path.join(os.tmpdir(), "companion-demo-"));
    try {
      const first = await startServer({ dataDir });
      await first.call("POST", "/demo/persona", { persona: "sacha" });
      await first.stop();
      server = await startServer({ dataDir });
      assert.equal((await server.call("GET", "/gitlab/mrs")).json.connected, true);
      await server.call("DELETE", "/demo");
      assert.equal((await server.call("GET", "/gitlab/mrs")).json.connected, false);
    } finally {
      await server?.stop();
      server = undefined;
      await fs.rm(dataDir, { recursive: true, force: true });
    }
  });
});
