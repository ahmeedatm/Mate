/**
 * Contrôle de non-régression d'un compagnon RÉEL déjà démarré (installé par paquet, ou serveur de test) :
 * un contrôle par feature, avec la réponse attendue selon le système (501 là où il ne gère pas le pilotage).
 *
 *   node qa/sanity.ts --url http://127.0.0.1:3000 --token <jeton> [--os windows|mac|other] [--junit sanity.xml]
 *
 * Aucune dépendance (modules de Node seulement) : lancé tel quel par le node du paquet, sans `npm ci`.
 * Ce qu'il crée (tâche, rendez-vous, agenda ICS) est retiré à la fin ; un agenda déjà relié n'est pas touché.
 * Appairage sans boîte de dialogue : démarrer le compagnon avec COMPANION_NO_DIALOG=1.
 */
import fs from "node:fs";
import http from "node:http";
import type { AddressInfo } from "node:net";
import { pathToFileURL } from "node:url";

export type Os = "mac" | "windows" | "other";
export type Result = { name: string; status: "ok" | "fail" | "skip"; detail: string; ms: number };
type Reply = { status: number; json: any };
type Call = (method: string, route: string, body?: unknown, auth?: boolean) => Promise<Reply>;

class Skip extends Error {}

function expect(cond: unknown, message: string): asserts cond {
  if (!cond) throw new Error(message);
}

function expectStatus(res: Reply, wanted: number, what: string) {
  const said = typeof res.json === "object" ? (res.json?.message ?? res.json?.error) : undefined;
  const error = said ? ` (${said})` : "";
  expect(res.status === wanted, `${what} : ${res.status} au lieu de ${wanted}${error}`);
}

const pad = (n: number) => String(n).padStart(2, "0");

/** Agenda ICS d'un événement aujourd'hui à 12 h, servi en local le temps du contrôle. */
function icsFile(title: string) {
  const d = new Date();
  const day = `${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}`;
  return `BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//sanity//FR\r\nBEGIN:VEVENT\r\nUID:sanity-ics\r\nDTSTAMP:${day}T080000Z\r\nDTSTART:${day}T120000\r\nDTEND:${day}T130000\r\nSUMMARY:${title}\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n`;
}

function serveIcs(body: string): Promise<{ url: string; close: () => void }> {
  return new Promise((resolve) => {
    const srv = http.createServer((_req, res) => res.writeHead(200, { "content-type": "text/calendar" }).end(body));
    srv.listen(0, "127.0.0.1", () => {
      const { port } = srv.address() as AddressInfo;
      resolve({ url: `http://127.0.0.1:${port}/agenda.ics`, close: () => srv.close() });
    });
  });
}

type Check = { name: string; run: () => Promise<string | void> };

export async function runSanity(opts: { url: string; token: string; os?: Os }): Promise<Result[]> {
  const base = opts.url.replace(/\/$/, "");
  const local = /^https?:\/\/(127\.0\.0\.1|localhost|\[::1\])(:\d+)?$/i.test(base);
  const call: Call = async (method, route, body, auth = true) => {
    const headers: Record<string, string> = {};
    if (auth) headers.authorization = `Bearer ${opts.token}`;
    if (body !== undefined) headers["content-type"] = "application/json";
    const res = await fetch(base + route, {
      method, headers, body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(45_000),
    });
    const text = await res.text();
    let json: any = text;
    try {
      json = JSON.parse(text);
    } catch {
      // Page HTML : texte brut.
    }
    return { status: res.status, json };
  };

  let os: Os = opts.os ?? "other";
  let id = "";
  const controlled = () => os !== "other";
  const stamp = `sanity-${Date.now()}`;

  const checks: Check[] = [
    {
      name: "santé et identité",
      run: async () => {
        const open = await call("GET", "/health", undefined, false);
        expectStatus(open, 200, "/health sans jeton");
        expect(open.json.service === "unowhy-companion", `service inattendu : ${open.json.service}`);
        expect(open.json.auth === false, "/health sans jeton devrait dire auth: false");
        if (opts.os) expect(open.json.os === opts.os, `système annoncé « ${open.json.os} », attendu « ${opts.os} »`);
        os = open.json.os === "mac" || open.json.os === "windows" ? open.json.os : "other";
        id = open.json.id;
        expect(typeof id === "string" && id.length > 0, "identifiant de l'ordinateur absent");
        expect((await call("GET", "/health")).json.auth === true, "le jeton donné n'est pas accepté");
        expectStatus(await call("GET", "/tasks", undefined, false), 401, "route protégée sans jeton");
        return `« ${open.json.name} », ${os}, port ${open.json.port}`;
      },
    },
    {
      name: "appairage par code",
      run: async () => {
        const req = await call("POST", "/pair/request", { tablet: "Sanity CI" }, false);
        if (req.status === 409) throw new Skip("un autre appairage est en cours");
        expectStatus(req, 200, "/pair/request");
        const status = await call("GET", "/local/status");
        expectStatus(status, 200, "/local/status");
        const code: string | undefined = status.json.pairing?.code;
        expect(/^\d{6}$/.test(code ?? ""), "code d'appairage absent de la page locale");
        const ok = await call("POST", "/pair/confirm", { request: req.json.request, code }, false);
        expectStatus(ok, 200, "/pair/confirm");
        expect(ok.json.token === opts.token, "le jeton donné à l'appairage n'est pas celui de l'ordinateur");
        expect(ok.json.id === id, "l'appairage renvoie une autre identité que /health");
      },
    },
    {
      name: "deck et deck de départ filtré",
      run: async () => {
        const deck = await call("GET", "/deck");
        if (!controlled()) return expectStatus(deck, 501, "/deck");
        expectStatus(deck, 200, "/deck");
        const pages: { title: string; apps: boolean; editable: boolean; tiles: { app: string | null }[] }[] = deck.json.pages;
        expect(pages.some((p) => p.editable && p.title === "Perso"), "page Perso absente");
        const catalog = await call("GET", "/deck/catalog");
        expectStatus(catalog, 200, "/deck/catalog");
        const installed = (catalog.json.apps as string[]).map((a) => a.toLowerCase());
        const shown = pages.filter((p) => p.apps).flatMap((p) => p.tiles.map((t) => t.app ?? ""));
        // Profil de base : seulement les applis installées (Windows sans bureau : aucune).
        const missing = shown.filter((a) => !installed.some((i) => i === a.toLowerCase() || i.includes(a.toLowerCase())));
        expect(missing.length === 0, `applis du deck absentes de l'ordinateur : ${missing.join(", ")}`);
        return `${pages.length} pages, ${shown.length} applis sur ${installed.length} installées`;
      },
    },
    {
      name: "son",
      run: async () => {
        const audio = await call("GET", "/system/audio");
        if (!controlled()) return expectStatus(audio, 501, "/system/audio");
        expectStatus(audio, 200, "/system/audio");
        expect(typeof audio.json.output === "number", "volume de sortie absent");
        return `volume ${audio.json.output}${audio.json.muted ? ", coupé" : ""}`;
      },
    },
    {
      name: "fenêtres ouvertes",
      run: async () => {
        const open = await call("GET", "/windows/open");
        if (!controlled()) return expectStatus(open, 501, "/windows/open");
        expectStatus(open, 200, "/windows/open");
        expect(Array.isArray(open.json.apps) && Array.isArray(open.json.displays), "réponse sans apps ni displays");
        // Sans bureau (conteneur Windows), la liste est vide : accepté.
        return `${open.json.apps.length} applis, ${open.json.displays.length} écrans`;
      },
    },
    {
      name: "garde de /docs/open (refuse un .exe)",
      run: async () => {
        const res = await call("POST", "/docs/open", { path: "sanity.exe" });
        if (!controlled()) return expectStatus(res, 501, "/docs/open");
        expectStatus(res, 400, "/docs/open d'un .exe");
        // Message en français dans `message` (erreur 400 de Fastify), lu en premier par la tablette.
        const message = res.json.message ?? res.json.error;
        expect(/programme ou un script/.test(message ?? ""), `refus inattendu : ${message}`);
      },
    },
    {
      name: "tâches : créer, lister, cocher",
      run: async () => {
        const added = await call("POST", "/tasks", { label: stamp });
        expectStatus(added, 200, "POST /tasks");
        const taskId: string = added.json.id;
        try {
          const listed = await call("GET", "/tasks");
          expect(listed.json.tasks.some((t: { id: string }) => t.id === taskId), "tâche créée absente de la liste");
          expectStatus(await call("POST", `/tasks/${taskId}/done`, { done: true }), 200, "cocher la tâche");
          const after = await call("GET", "/tasks");
          expect(after.json.tasks.find((t: { id: string }) => t.id === taskId)?.done === true, "tâche cochée mais pas faite");
        } finally {
          await call("DELETE", `/tasks/${taskId}`);
        }
      },
    },
    {
      name: "agenda personnel",
      run: async () => {
        const startMs = Date.now() + 5 * 60_000;
        const added = await call("POST", "/calendar/events", { title: stamp, startMs, allDay: false });
        expectStatus(added, 200, "POST /calendar/events");
        try {
          const cal = await call("GET", "/calendar?days=2");
          expectStatus(cal, 200, "/calendar");
          expect(cal.json.events.some((e: { title: string }) => e.title === stamp), "rendez-vous absent de l'agenda");
        } finally {
          expectStatus(await call("DELETE", `/calendar/events/${added.json.id}`), 200, "supprimer le rendez-vous");
        }
      },
    },
    {
      name: "agenda ICS (fichier de test local)",
      run: async () => {
        if (!local) throw new Skip("compagnon distant : le fichier ICS de test n'est servi qu'en local");
        const status = await call("GET", "/local/status");
        expectStatus(status, 200, "/local/status");
        if (status.json.calendar.saved.length > 0) throw new Skip("un agenda est déjà relié sur cet ordinateur : pas touché");
        const title = `${stamp}-ics`;
        const ics = await serveIcs(icsFile(title));
        try {
          const put = await call("PUT", "/local/calendar", { urls: [ics.url] });
          expectStatus(put, 200, "PUT /local/calendar");
          expect(put.json.ok === true, `agenda ICS illisible : ${put.json.error}`);
          const cal = await call("GET", "/calendar?days=1");
          expect(cal.json.events.some((e: { title: string }) => e.title === title), "événement du fichier ICS absent");
        } finally {
          await call("PUT", "/local/calendar", { urls: [] });
          ics.close();
        }
      },
    },
    {
      name: "visualiseur /camera",
      run: async () => {
        const page = await call("GET", "/camera");
        expectStatus(page, 200, "/camera");
        expect(/<title>Visualiseur<\/title>/.test(page.json), "page du visualiseur inattendue");
        const status = await call("GET", "/camera/status");
        expectStatus(status, 200, "/camera/status");
      },
    },
    {
      name: "page locale",
      run: async () => {
        const page = await call("GET", "/", undefined, local ? false : true);
        expectStatus(page, 200, "/");
        expect(/<title>Compagnon Unowhy<\/title>/.test(page.json), "page locale inattendue");
        const status = await call("GET", "/local/status");
        expectStatus(status, 200, "/local/status");
        expect(Array.isArray(status.json.addresses) && status.json.addresses.length > 0, "aucune adresse affichée");
      },
    },
  ];

  const results: Result[] = [];
  for (const check of checks) {
    const start = Date.now();
    try {
      const detail = await check.run();
      results.push({ name: check.name, status: "ok", detail: detail ?? "", ms: Date.now() - start });
    } catch (e) {
      const status = e instanceof Skip ? "skip" : "fail";
      results.push({ name: check.name, status, detail: (e as Error).message, ms: Date.now() - start });
    }
  }
  return results;
}

const xml = (s: string) => s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");

export function junit(results: Result[], suite: string): string {
  const failures = results.filter((r) => r.status === "fail").length;
  const skipped = results.filter((r) => r.status === "skip").length;
  const time = results.reduce((t, r) => t + r.ms, 0) / 1000;
  const cases = results.map((r) => {
    const open = `  <testcase classname="${xml(suite)}" name="${xml(r.name)}" time="${r.ms / 1000}"`;
    if (r.status === "ok") return `${open}/>`;
    const inner = r.status === "skip" ? `<skipped message="${xml(r.detail)}"/>` : `<failure message="${xml(r.detail)}"/>`;
    return `${open}>${inner}</testcase>`;
  });
  return `<?xml version="1.0" encoding="UTF-8"?>\n<testsuite name="${xml(suite)}" tests="${results.length}" failures="${failures}" skipped="${skipped}" time="${time}">\n${cases.join("\n")}\n</testsuite>\n`;
}

function arg(name: string): string | undefined {
  const i = process.argv.indexOf(`--${name}`);
  return i > 0 ? process.argv[i + 1] : undefined;
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? "").href) {
  const url = arg("url") ?? "http://127.0.0.1:3000";
  const token = arg("token") ?? process.env.COMPANION_TOKEN ?? "";
  const os = arg("os") as Os | undefined;
  if (!token || (os && !["mac", "windows", "other"].includes(os))) {
    console.error("Usage : node qa/sanity.ts --url http://127.0.0.1:3000 --token <jeton> [--os windows|mac|other] [--junit fichier.xml]");
    process.exit(2);
  }
  const results = await runSanity({ url, token, os });
  const mark = { ok: "OK  ", fail: "ECHEC", skip: "SAUTE" };
  for (const r of results) console.log(`${mark[r.status]} ${r.name}${r.detail ? ` : ${r.detail}` : ""}`);
  const failed = results.filter((r) => r.status === "fail").length;
  console.log(`\n${results.length - failed} / ${results.length} contrôles sans échec`);
  const file = arg("junit");
  if (file) fs.writeFileSync(file, junit(results, `sanity-${os ?? "auto"}`));
  process.exit(failed ? 1 : 0);
}
