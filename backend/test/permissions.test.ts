import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { describe, it } from "node:test";
import { fileURLToPath } from "node:url";
import Fastify from "fastify";
import {
  broadcastOf,
  classifyConnectError,
  createAutomationGate,
  createPermissions,
  defaultDeps,
  OPTIONAL_APPS,
  type Deps,
} from "../src/mac/permissions.ts";
import { setNotificationsRead } from "../src/notifications.ts";
import { permissionRoutes } from "../src/routes/permissions.ts";

// Autorisations du Mac : l'utilitaire natif (MatePermissions) et les ouvertures de Réglages sont remplacés par des
// fonctions injectées ; rien n'est demandé au vrai macOS.

type Status = Awaited<ReturnType<Deps["status"]>>;

function fake({ status, ...over }: Partial<Omit<Deps, "status">> & { status?: Status | (() => Promise<Status>) } = {}) {
  const calls: string[] = [];
  const deps: Deps = {
    supported: true,
    darwinMajor: 25,
    status: typeof status === "function" ? status : async () => status ?? { accessibility: true, screen: true, automation: {} },
    request: async (args) => (calls.push(`request ${args.join(" ")}`), true),
    installed: (name) => ["Safari", "Google Chrome", "Spotify", "Music"].includes(name),
    fullDisk: () => "granted",
    probeLocalNetwork: async () => (calls.push("probe"), "granted"),
    openAccessibility: async () => void calls.push("accessibility"),
    openSettings: async (pane) => void calls.push(`settings ${pane}`),
    wake: async (bundle) => void calls.push(`wake ${bundle}`),
    ...over,
  };
  return { deps, calls };
}

const byId = async (deps: Deps) => new Map((await createPermissions(deps).list()).map((p) => [p.id, p]));

describe("liste des autorisations", () => {
  it("indispensables en tête : Accessibilité puis System Events ; applis installées seulement ; dans l'ordre", async () => {
    const { deps } = fake();
    const list = await createPermissions(deps).list();
    assert.deepEqual(
      list.map((p) => [p.id, p.essential]),
      [
        ["accessibility", true],
        ["automation-com.apple.systemevents", true],
        ["automation-com.apple.Safari", false],
        ["automation-com.google.Chrome", false],
        ["automation-com.spotify.client", false],
        ["automation-com.apple.Music", false],
        ["local-network", false],
        ["full-disk", false],
        ["screen", false],
      ],
    );
    for (const p of list) assert.ok(p.title && p.why && p.appear, p.id);
  });

  it("états : accordé, refusé, pas encore demandé, appli fermée", async () => {
    const { deps } = fake({
      status: {
        accessibility: false,
        screen: true,
        automation: {
          "com.apple.systemevents": "closed",
          "com.apple.Safari": "denied",
          "com.google.Chrome": "granted",
          "com.spotify.client": "not-asked",
        },
      },
    });
    const list = await byId(deps);
    const view = (id: string) => [list.get(id)!.state, list.get(id)!.action];
    assert.deepEqual(view("accessibility"), ["not-asked", "request"]);
    assert.deepEqual(view("automation-com.apple.systemevents"), ["not-asked", "request"]);
    // Refusée : macOS ne redemande plus, seuls les Réglages aident.
    assert.deepEqual(view("automation-com.apple.Safari"), ["denied", "settings"]);
    assert.deepEqual(view("automation-com.google.Chrome"), ["granted", null]);
    assert.deepEqual(view("automation-com.spotify.client"), ["not-asked", "request"]);
    assert.deepEqual(view("screen"), ["granted", null]);
  });

  it("accès complet au disque : seulement les Réglages, jamais de fenêtre ; refusé ou base absente", async () => {
    for (const state of ["denied", "unknown"] as const) {
      const list = await byId(fake({ fullDisk: () => state }).deps);
      assert.deepEqual([list.get("full-disk")!.state, list.get("full-disk")!.action], [state, "settings"]);
    }
  });

  it("utilitaire absent : tout est « unknown », rien ne plante", async () => {
    const { deps } = fake({ status: async () => null });
    const list = await createPermissions(deps).list();
    for (const id of ["accessibility", "automation-com.apple.systemevents", "automation-com.apple.Safari", "screen"]) {
      assert.equal(list.find((p) => p.id === id)!.state, "unknown", id);
    }
    assert.equal(list.find((p) => p.id === "full-disk")!.state, "granted");
  });

  it("hors Mac : liste vide", async () => {
    assert.deepEqual(await createPermissions(fake({ supported: false }).deps).list(), []);
  });

  it("Réseau local seulement depuis macOS 15 (Darwin 24)", async () => {
    assert.ok(!(await byId(fake({ darwinMajor: 23 }).deps)).has("local-network"));
    assert.ok((await byId(fake({ darwinMajor: 24 }).deps)).has("local-network"));
  });

  it("les navigateurs et lecteurs sont ceux que le compagnon pilote (browser.ts, media.ts)", () => {
    const src = (f: string) => fs.readFileSync(path.join(path.dirname(fileURLToPath(import.meta.url)), "..", "src", "mac", f), "utf8");
    const code = src("browser.ts") + src("media.ts");
    for (const a of OPTIONAL_APPS) assert.ok(code.includes(`"${a.folder}"`), a.folder);
  });
});

describe("demande d'une autorisation", () => {
  it("Accessibilité : la demande existante (fenêtre de macOS puis Réglages)", async () => {
    const { deps, calls } = fake({ status: { accessibility: false, screen: true, automation: {} } });
    assert.deepEqual(await createPermissions(deps).request("accessibility"), { ok: true, action: "request" });
    assert.deepEqual(calls, ["accessibility"]);
  });

  it("Automation : l'utilitaire lance la demande, la réponse n'attend pas le choix de l'utilisateur", async () => {
    const { deps, calls } = fake({ status: { accessibility: true, screen: true, automation: { "com.apple.systemevents": "closed" } } });
    const r = await createPermissions(deps).request("automation-com.apple.systemevents");
    assert.deepEqual(r, { ok: true, action: "request" });
    assert.deepEqual(calls, ["request request-automation com.apple.systemevents"]);
  });

  it("Automation refusée : Réglages > Automatisation, pas de nouvelle fenêtre", async () => {
    const { deps, calls } = fake({ status: { accessibility: true, screen: true, automation: { "com.google.Chrome": "denied" } } });
    assert.deepEqual(await createPermissions(deps).request("automation-com.google.Chrome"), { ok: true, action: "settings" });
    assert.deepEqual(calls, ["settings Privacy_Automation"]);
  });

  it("disque : ouvre Accès complet au disque ; écran : demande puis ouvre Enregistrement de l'écran", async () => {
    const a = fake({ fullDisk: () => "denied" });
    await createPermissions(a.deps).request("full-disk");
    assert.deepEqual(a.calls, ["settings Privacy_AllFiles"]);
    const b = fake({ status: { accessibility: true, screen: false, automation: {} } });
    await createPermissions(b.deps).request("screen");
    assert.deepEqual(b.calls, ["request request-screen", "settings Privacy_ScreenCapture"]);
  });

  it("Réseau local : le test de connexion fixe l'état", async () => {
    const { deps, calls } = fake({ probeLocalNetwork: async () => "denied" });
    const permissions = createPermissions(deps);
    assert.equal((await permissions.list()).find((p) => p.id === "local-network")!.state, "not-asked");
    await permissions.request("local-network");
    const after = (await permissions.list()).find((p) => p.id === "local-network")!;
    assert.deepEqual([after.state, after.action], ["denied", "settings"]);
    assert.deepEqual(calls, []);
    await permissions.request("local-network");
    assert.deepEqual(calls, ["settings Privacy_LocalNetwork"]);
  });

  it("Accessibilité encore refusée après une demande : « denied »", async () => {
    const { deps } = fake({ status: { accessibility: false, screen: true, automation: {} } });
    const permissions = createPermissions(deps);
    await permissions.request("accessibility");
    assert.equal((await permissions.list())[0].state, "denied");
  });

  it("déjà accordée : rien à faire ; identifiant inconnu : refusé sans rien lancer", async () => {
    const { deps, calls } = fake();
    const permissions = createPermissions(deps);
    assert.deepEqual(await permissions.request("accessibility"), { ok: true });
    assert.equal((await permissions.request("automation-com.evil.App")).unknownId, true);
    assert.deepEqual(calls, []);
  });

  it("utilitaire absent : message clair, pas de plantage", async () => {
    const { deps } = fake({ request: async () => false, status: { accessibility: true, screen: false, automation: {} } });
    const r = await createPermissions(deps).request("screen");
    assert.equal(r.ok, false);
    assert.match(r.error!, /outil des autorisations/);
  });
});

describe("test du réseau local", () => {
  it("route coupée = refusé ; machine qui répond ou refuse le port = accordé ; délai dépassé = inconnu", () => {
    assert.equal(classifyConnectError("EHOSTUNREACH"), "denied");
    assert.equal(classifyConnectError("ENETUNREACH"), "denied");
    assert.equal(classifyConnectError("ECONNREFUSED"), "granted");
    assert.equal(classifyConnectError("ETIMEDOUT"), "unknown");
    assert.equal(classifyConnectError(undefined), "unknown");
  });

  it("adresse de diffusion d'un sous-réseau", () => {
    assert.equal(broadcastOf("192.168.1.23", "255.255.255.0"), "192.168.1.255");
    assert.equal(broadcastOf("10.4.7.9", "255.255.0.0"), "10.4.255.255");
    assert.equal(broadcastOf("10.4.7.9", "pas une adresse"), null);
  });
});

describe("routes /local/permissions", () => {
  async function app(deps: Deps) {
    const server = Fastify();
    await server.register(permissionRoutes(deps));
    return server;
  }
  const post = (server: Awaited<ReturnType<typeof app>>, id: string, body: unknown = {}) =>
    server.inject({ method: "POST", url: `/local/permissions/${id}/request`, payload: body as object });

  it("liste, puis demande", async () => {
    const { deps, calls } = fake({ status: { accessibility: true, screen: false, automation: {} } });
    const server = await app(deps);
    const list = (await server.inject({ method: "GET", url: "/local/permissions" })).json();
    assert.equal(list.items[0].id, "accessibility");
    const res = await post(server, "screen");
    assert.equal(res.statusCode, 200);
    assert.deepEqual(res.json(), { ok: true, action: "request" });
    assert.deepEqual(calls, ["request request-screen", "settings Privacy_ScreenCapture"]);
  });

  it("hors Mac : liste vide, demande en 404", async () => {
    const server = await app(fake({ supported: false }).deps);
    assert.deepEqual((await server.inject({ method: "GET", url: "/local/permissions" })).json(), { items: [] });
    assert.equal((await post(server, "screen")).statusCode, 404);
  });

  it("identifiant inconnu : 404 ; outil absent : 503 ; jamais 500 quand l'utilitaire manque", async () => {
    const server = await app(fake({ request: async () => false, status: async () => null }).deps);
    assert.equal((await post(server, "inconnue")).statusCode, 404);
    assert.equal((await post(server, "screen")).statusCode, 503);
    const list = await server.inject({ method: "GET", url: "/local/permissions" });
    assert.equal(list.statusCode, 200);
  });

  it("corps JSON exigé : un formulaire d'un autre site est refusé", async () => {
    const server = await app(fake().deps);
    const res = await server.inject({
      method: "POST",
      url: "/local/permissions/screen/request",
      headers: { "content-type": "text/plain" },
      payload: "{}",
    });
    assert.ok([400, 415].includes(res.statusCode), String(res.statusCode));
  });
});

describe("filtre des ordres AppleScript", () => {
  const SE = "com.apple.systemevents";
  const gate = (
    automation: Record<string, string> | null | (() => Record<string, string> | null),
    over: Partial<Omit<Deps, "status">> = {},
  ) => {
    let clock = 0;
    const reads: number[] = [];
    const { deps, calls } = fake({
      status: async () => {
        reads.push(clock);
        const a = typeof automation === "function" ? automation() : automation;
        return a && { accessibility: true, screen: true, automation: a };
      },
      ...over,
    });
    return { gate: createAutomationGate(deps, () => clock), reads, calls, tick: (ms: number) => (clock += ms) };
  };

  it("seules les applis accordées passent ; refusée, pas demandée, inconnue : écartées", async () => {
    const { gate: g } = gate({
      [SE]: "granted",
      "com.spotify.client": "granted",
      "com.google.Chrome": "denied",
      "com.apple.Safari": "not-asked",
    });
    assert.equal(await g.systemEvents(), true);
    assert.deepEqual(await g.names(["Spotify", "Google Chrome", "Safari", "Arc", "Firefox"]), ["Spotify"]);
  });

  it("System Events non accordé : erreur en français, sans demande", async () => {
    const { gate: g, calls } = gate({ [SE]: "not-asked" });
    await assert.rejects(g.requireSystemEvents(), /Autorise Mate à piloter System Events/);
    assert.ok(!calls.some((c) => c.startsWith("request")));
  });

  it("appli fermée : dernier état connu gardé, jamais connu = écartée", async () => {
    let spotify = "granted";
    const { gate: g, tick } = gate(() => ({ [SE]: "granted", "com.spotify.client": spotify, "com.apple.Music": "closed" }));
    assert.deepEqual(await g.names(["Spotify", "Music"]), ["Spotify"]);
    spotify = "closed";
    tick(11_000);
    assert.deepEqual(await g.names(["Spotify", "Music"]), ["Spotify"]);
    spotify = "denied";
    tick(11_000);
    assert.deepEqual(await g.names(["Spotify"]), []);
  });

  it("System Events fermé et jamais lu : lancé en arrière-plan puis relu", async () => {
    let woken = false;
    const { gate: g, calls } = gate(() => ({ [SE]: woken ? "granted" : "closed" }), {
      wake: async (bundle) => void ((woken = true), calls.push(`wake ${bundle}`)),
    });
    assert.equal(await g.systemEvents(), true);
    assert.deepEqual(calls, [`wake ${SE}`]);
  });

  it("état relu au plus toutes les 10 s", async () => {
    const { gate: g, reads, tick } = gate({ [SE]: "granted" });
    await Promise.all([g.systemEvents(), g.systemEvents()]);
    tick(9_000);
    await g.systemEvents();
    assert.equal(reads.length, 1);
    tick(2_000);
    await g.systemEvents();
    assert.equal(reads.length, 2);
  });

  it("hors appli portable ou utilitaire absent : pas de filtre", async () => {
    const off = gate({ [SE]: "denied" }, { supported: false });
    assert.equal(await off.gate.systemEvents(), true);
    assert.deepEqual(await off.gate.names(["Spotify", "Firefox"]), ["Spotify", "Firefox"]);
    assert.equal(off.reads.length, 0);
    const missing = gate(null);
    assert.deepEqual(await missing.gate.names(["Spotify"]), ["Spotify"]);
  });
});

describe("accès complet au disque", () => {
  it("l'état vient de la dernière lecture des notifications, sans ouvrir la base du système", () => {
    const { fullDisk } = defaultDeps();
    setNotificationsRead(false, new Error("illisible"));
    assert.equal(fullDisk(), "denied");
    setNotificationsRead(true);
    assert.equal(fullDisk(), "granted");
  });
});
