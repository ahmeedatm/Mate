import type { Page } from "@playwright/test";
import { expect, test } from "./fixtures.ts";
import type { TestServer } from "../test/server.ts";

// Page locale, sections Profils et Barres : une fausse tablette répond aux ordres `layout` (get / set) du compagnon.
// Les sections restent masquées (et hors de la barre de gauche) tant qu'aucune disposition n'a été lue.

const STATE = { mode: "home", app: "1.4.0", name: "Tablette", model: "Unowhy 4.2", screen: "1280x800", battery: null, settings: {} };
const SIZES = [
  { key: "S", w: 1, h: 1, label: "Petit" },
  { key: "M", w: 2, h: 1, label: "Moyen" },
  { key: "L", w: 2, h: 2, label: "Grand" },
  { key: "V", w: 1, h: 2, label: "Vertical" },
  { key: "W", w: 4, h: 1, label: "Bandeau" },
  { key: "XL", w: 4, h: 2, label: "Très grand" },
];
const by = (...keys: string[]) => keys.map((k) => SIZES.find((s) => s.key === k)!);
const CATALOG = [
  { key: "clock", title: "Horloge", computer: false, offered: true, sizes: by("L", "XL", "S", "M", "W") },
  { key: "note", title: "Note", computer: false, offered: true, sizes: by("M", "S", "L") },
  { key: "keys", title: "Touches", computer: true, offered: true, sizes: by("M", "S", "L", "V", "XL") },
  { key: "agents", title: "Agents", computer: true, offered: false, sizes: by("M", "L") },
];
const TOP: Record<string, string> = { music: "Lecture en cours", clock: "Heure", date: "Date", wifi: "WiFi" };
const TOOLS: Record<string, string> = { focus: "Focus", docs: "Ordi" };

/** Tablette simulée : garde sa disposition, la rend à `get`, applique `set` en refusant ce qui sort de la grille. */
function fakeTablet(server: TestServer) {
  let layout: any = {
    v: 2,
    pages: 2,
    names: ["Accueil", "Réunion"],
    icons: [null, null],
    home: 0,
    deck: -1,
    call: 1,
    roles: true,
    widgets: [{ t: "clock", s: "L", p: 0, c: 0, r: 0, cfg: {} }],
  };
  let top = ["clock"];
  let rail = { labels: true, tools: ["focus"] };
  const sets: any[] = [];
  let running = true;
  const pick = (map: Record<string, string>, keys: string[]) => keys.map((key) => ({ key, label: map[key] }));
  const data = () => ({
    layout,
    cadran: true,
    computerId: "pc-1",
    nameMax: 30,
    cols: 4,
    rows: 4,
    sizes: SIZES,
    catalog: CATALOG,
    topBar: {
      items: pick(TOP, top),
      addable: pick(
        TOP,
        Object.keys(TOP).filter((k) => !top.includes(k)),
      ),
    },
    rail: {
      labels: rail.labels,
      tools: pick(TOOLS, rail.tools),
      available: pick(
        TOOLS,
        Object.keys(TOOLS).filter((k) => !rail.tools.includes(k)),
      ),
    },
  });
  const apply = (c: any) => {
    if (c.action === "get") return { ok: true, data: data() };
    sets.push(c);
    for (const w of c.layout?.widgets ?? []) {
      const s = SIZES.find((x) => x.key === w.s)!;
      if (w.c + s.w > 4 || w.r + s.h > 4) return { ok: false, error: `Le widget ${w.t} sort de la grille (4 × 4).` };
    }
    if (c.layout) layout = c.layout;
    if (c.topBar) top = c.topBar;
    if (c.rail) rail = { ...rail, ...c.rail };
    return { ok: true, data: data() };
  };
  void (async () => {
    while (running) {
      try {
        const res = await server.call("POST", "/tablet/poll", { state: STATE });
        for (const order of res.json.commands ?? []) {
          if (order.command.type === "layout")
            await server.call("POST", "/tablet/result", { id: order.id, ...apply(order.command), state: STATE });
        }
      } catch {
        await new Promise((r) => setTimeout(r, 200));
      }
    }
  })();
  return { sets, stop: () => (running = false) };
}

test.describe.configure({ mode: "serial" });

/** Une seule tablette pour la suite : elle garde ce que chaque test lui a envoyé. */
let tablet: ReturnType<typeof fakeTablet> | undefined;
const connected = (server: TestServer) => (tablet ??= fakeTablet(server));
test.afterAll(() => tablet?.stop());

/** Régler le widget choisi : ouvre sa modale ; Échap la ferme et rend la main à la grille. */
const openWidget = (page: Page) => page.locator("#pf-sel-edit").click();
const closeDialog = (page: Page) => page.keyboard.press("Escape");

test.describe("page locale : profils et barres", () => {
  test("sans disposition lue, Profils et Barres sont masqués et absents de la barre de gauche", async ({ page }) => {
    await page.goto("/");
    await expect(page.locator("#menu a").first()).toBeVisible();
    await expect(page.locator("#pf-profiles")).toHaveAttribute("data-title", "Profils");
    await expect(page.locator("#pf-profiles")).toHaveAttribute("data-icon", "grid");
    await expect(page.locator("#pf-bars")).toHaveAttribute("data-title", "Barres");
    await expect(page.locator("#pf-bars")).toHaveAttribute("data-icon", "bars");
    await expect(page.locator("#pf-profiles")).toBeHidden();
    await expect(page.locator("#pf-bars")).toBeHidden();
    await expect(page.locator("#menu a", { hasText: "Profils" })).toHaveCount(0);
    await expect(page.locator("#menu a", { hasText: "Barres" })).toHaveCount(0);
  });

  test("affiche la disposition de la tablette et protège l'Accueil et Réunion", async ({ page, server }) => {
    connected(server);
    {
      await page.goto("/");
      const chips = page.locator("#pf-chips .cx");
      await expect(chips).toHaveCount(2);
      await expect(chips.nth(0)).toContainText("Accueil");
      await expect(chips.nth(1)).toContainText("Réunion");
      await expect(page.getByRole("button", { name: "Horloge, Grand" })).toBeVisible();
      await expect(page.locator("#pf-profiles")).toBeVisible();
      await expect(page.locator("#menu a", { hasText: "Profils" })).toHaveAttribute("href", "#pf-profiles");
      await expect(page.locator("#menu a", { hasText: "Barres" })).toHaveAttribute("href", "#pf-bars");
      await expect(page.locator("#pf-banner")).toBeHidden();
      // Accueil et Réunion ne se suppriment pas.
      await expect(page.locator("#pf-del")).toBeDisabled();
      await chips.nth(1).click();
      await expect(page.locator("#pf-del")).toBeDisabled();
      // Rien à envoyer tant que rien n'a changé.
      await expect(page.locator("#pf-send")).toBeDisabled();
    }
  });

  test("crée un profil, ajoute et règle des widgets, puis envoie à la tablette", async ({ page, server }) => {
    const tablet = connected(server);
    {
      await page.goto("/");
      await expect(page.locator("#pf-chips .cx")).toHaveCount(2);
      await page.locator("#pf-new").click();
      await expect(page.locator("#pf-chips .cx").nth(2)).toContainText("Profil 2");
      await page.locator("#pf-rename").click();
      await page.getByLabel("Nom du profil").fill("Travail");
      await page.getByRole("button", { name: "Terminé" }).click();
      await expect(page.locator("#pf-chips .cx").nth(2)).toContainText("Travail");

      // « Agents » n'est pas proposé par cet ordinateur : absent du catalogue.
      await expect(page.locator("#pf-add option")).toHaveText(["Horloge", "Note", "Touches"]);
      await page.locator("#pf-add").selectOption("note");
      await page.locator("#pf-add-go").click();
      const note = page.getByRole("button", { name: "Note, Moyen" });
      await expect(note).toBeVisible();

      // Taille : seulement celles qui tiennent. Moyen (2 × 1) en haut à gauche : Grand tient, rien ne gêne.
      await openWidget(page);
      await page.getByRole("button", { name: "Grand (2 × 2)" }).click();
      await closeDialog(page);
      await expect(page.getByRole("button", { name: "Note, Grand" })).toBeVisible();

      // Déplacement sur une case libre.
      await page.getByRole("button", { name: "Case libre, colonne 4, ligne 3" }).click();
      await expect(page.locator("#pf-msg")).toHaveText("Ce widget (2 × 2) ne tient pas à cet endroit.");
      await page.getByRole("button", { name: "Case libre, colonne 3, ligne 3" }).click();
      await expect(page.getByRole("button", { name: "Note, Grand" })).toHaveCSS("grid-column-start", "3");

      // Un second widget prend la première case libre ; posé sur la ligne de la Note, il ne peut plus devenir Très grand.
      await page.locator("#pf-add").selectOption("clock");
      await page.locator("#pf-add-go").click();
      await expect(page.getByRole("button", { name: "Horloge, Grand" })).toBeVisible();
      await page.getByRole("button", { name: "Note, Grand" }).click();
      await openWidget(page);
      await expect(page.locator("#pf-widget .pf-size")).toHaveText(["Moyen (2 × 1)", "Petit (1 × 1)", "Grand (2 × 2)"]);
      await closeDialog(page);
      await page.getByRole("button", { name: "Horloge, Grand" }).click();
      await page.getByRole("button", { name: "Case libre, colonne 1, ligne 3" }).click();
      await openWidget(page);
      await expect(page.getByRole("button", { name: "Très grand (4 × 2)" })).toBeDisabled();
      await expect(page.getByRole("button", { name: "Petit (1 × 1)" })).toBeEnabled();
      await closeDialog(page);

      await page.locator("#pf-send").click();
      await expect(page.locator("#pf-msg")).toHaveText("Envoyé à la tablette.");
      expect(tablet.sets).toHaveLength(1);
      const sent = tablet.sets[0].layout;
      expect(sent.names).toEqual(["Accueil", "Réunion", "Travail"]);
      expect(sent.pages).toBe(3);
      expect(sent.widgets.filter((w: any) => w.p === 2).map((w: any) => [w.t, w.s])).toEqual([
        ["note", "L"],
        ["clock", "L"],
      ]);
      // La disposition enregistrée par la tablette est celle affichée : plus rien à envoyer.
      await expect(page.locator("#pf-send")).toBeDisabled();

      // Retirer.
      await page.getByRole("button", { name: "Note, Grand" }).click();
      await openWidget(page);
      await page.getByRole("button", { name: "Retirer le widget" }).click();
      await expect(page.getByRole("button", { name: "Note, Grand" })).toHaveCount(0);
      await page.locator("#pf-reset").click();
      await expect(page.getByRole("button", { name: "Note, Grand" })).toBeVisible();
    }
  });

  test("monte, descend et supprime un profil avec ses widgets", async ({ page, server }) => {
    const tablet = connected(server);
    {
      await page.goto("/");
      const chips = page.locator("#pf-chips .cx");
      await expect(chips).toHaveCount(3);
      await chips.nth(2).click();
      await page.locator("#pf-up").click();
      await expect(chips.nth(1)).toContainText("Travail");
      await expect(chips.nth(2)).toContainText("Réunion");
      await page.locator("#pf-send").click();
      await expect(page.locator("#pf-msg")).toHaveText("Envoyé à la tablette.");
      // Réunion a suivi : son index est celui envoyé.
      expect(tablet.sets.at(-1).layout.call).toBe(2);
      expect(tablet.sets.at(-1).layout.names).toEqual(["Accueil", "Travail", "Réunion"]);

      await expect(page.locator("#pf-del")).toBeEnabled();
      await page.locator("#pf-del").click();
      await expect(chips).toHaveCount(2);
      await page.locator("#pf-send").click();
      await expect(page.locator("#pf-msg")).toHaveText("Envoyé à la tablette.");
      expect(tablet.sets.at(-1).layout.widgets.every((w: any) => w.p < 2)).toBe(true);
    }
  });

  test("choisit les touches d'un widget Touches parmi celles du Deck, pour l'ordinateur actif", async ({ page, server }) => {
    const tablet = connected(server);
    {
      await page.goto("/");
      await expect(page.locator("#pf-chips .cx")).toHaveCount(2);
      await page.locator("#pf-add").selectOption("keys");
      await page.locator("#pf-add-go").click();
      await expect(page.getByRole("button", { name: "Touches, Moyen" })).toBeVisible();
      await openWidget(page);
      await expect(page.getByText("Aucune touche choisie.")).toBeVisible();

      const select = page.getByLabel("Touche à ajouter");
      await select.selectOption({ index: 0 });
      await page.locator("#pf-widget").getByRole("button", { name: "Ajouter", exact: true }).click();
      await select.selectOption({ index: 0 });
      await page.locator("#pf-widget").getByRole("button", { name: "Ajouter", exact: true }).click();
      const rows = page.locator("#pf-widget .pf-list .row .grow");
      await expect(rows).toHaveCount(2);
      const [first, second] = await rows.allTextContents();
      await page
        .locator("#pf-widget")
        .getByRole("button", { name: /^Descendre / })
        .first()
        .click();
      await expect(rows).toHaveText([second, first]);
      await closeDialog(page);
      await page.locator("#pf-send").click();
      await expect(page.locator("#pf-msg")).toHaveText("Envoyé à la tablette.");

      const widget = tablet.sets.at(-1).layout.widgets.find((w: any) => w.t === "keys");
      const keys = widget.cfg.byComputer["pc-1"].keys;
      expect(keys).toHaveLength(2);
      expect(keys[0].id).not.toBe(keys[1].id);
      expect(keys.map((k: any) => k.label || k.id)).toEqual([second, first]);
      for (const k of keys) expect(Object.keys(k)).toEqual(expect.arrayContaining(["id", "label", "glyph", "color"]));
    }
  });

  test("refus de la tablette : l'erreur s'affiche, le brouillon reste", async ({ page, server }) => {
    connected(server);
    {
      await page.goto("/");
      await expect(page.locator("#pf-chips .cx")).toHaveCount(2);
      // Moyen (2 × 1) en haut à droite sort de la grille sur une page de 4 colonnes si on le force à la main : on triche
      // via la page elle-même en agrandissant un widget posé sur la dernière colonne.
      await page.locator("#pf-add").selectOption("note");
      await page.locator("#pf-add-go").click();
      await page.route("**/local/tablet/layout", (route) => {
        if (route.request().method() !== "PUT") return route.continue();
        const body = JSON.parse(route.request().postData()!);
        body.layout.widgets[body.layout.widgets.length - 1].c = 3;
        return route.continue({ postData: JSON.stringify(body) });
      });
      await page.locator("#pf-send").click();
      await expect(page.locator("#pf-msg")).toHaveText("Le widget note sort de la grille (4 × 4).");
      await expect(page.locator("#pf-msg")).toHaveAttribute("data-kind", "ko");
      await expect(page.locator("#pf-send")).toBeEnabled();
    }
  });

  test("règle le bandeau du haut et la barre de gauche", async ({ page, server }) => {
    const tablet = connected(server);
    {
      await page.goto("/");
      const pills = page.locator("#pf-strip .pf-pill");
      await expect(pills).toHaveCount(1);
      await expect(page.locator("#pf-reserve .pf-pill")).toHaveText(["Lecture en cours", "Date", "WiFi"]);
      await page.getByRole("button", { name: "Ajouter Date" }).click();
      await page.getByRole("button", { name: "Ajouter WiFi" }).click();
      // Ajoutés au début, comme sur la tablette : WiFi, Date, Heure.
      await expect(pills).toHaveText(["WiFi", "Date", "Heure"]);
      await page.getByRole("button", { name: "Retirer Date" }).click();
      // Flèche droite : WiFi passe après Heure, et garde le focus.
      await pills.first().locator(".pf-pill-main").focus();
      await page.keyboard.press("ArrowRight");
      await expect(pills).toHaveText(["Heure", "WiFi"]);
      await expect(pills.nth(1).locator(".pf-pill-main")).toBeFocused();

      await page.getByLabel("Noms sous les icônes").uncheck();
      await page.getByLabel("Outil Ordi").check();
      await expect(page.locator("#pf-bars-send")).toBeEnabled();
      await page.locator("#pf-bars-send").click();
      await expect(page.locator("#pf-bars-msg")).toHaveText("Envoyé à la tablette.");

      const set = tablet.sets.at(-1);
      expect(set.topBar).toEqual(["clock", "wifi"]);
      expect(set.rail).toEqual({ labels: false, tools: ["focus", "docs"] });
      expect(set.layout).toBeUndefined();
      await expect(page.locator("#pf-bars-send")).toBeDisabled();
    }
  });

  test("glisse un widget : ombre calée sur la grille, place invalide = retour, toucher = sélection", async ({ page, server }) => {
    const tablet = connected(server);
    {
      await page.goto("/");
      const widget = page.getByRole("button", { name: "Horloge, Grand" });
      await expect(widget).toBeVisible();
      await centered(page, "#pf-grid");
      const geo = await gridGeometry(page);
      const box = (await widget.boundingBox())!;
      const grab = { x: box.x + box.width / 2, y: box.y + box.height / 2 };

      // Sous le seuil de 6 px : c'est un toucher, le widget est choisi et ne bouge pas.
      await page.mouse.move(grab.x, grab.y);
      await page.mouse.down();
      await page.mouse.move(grab.x + 3, grab.y + 2);
      await page.mouse.up();
      await expect(widget).toHaveAttribute("aria-pressed", "true");
      await expect(page.locator(".pf-shadow")).toHaveCount(0);
      await expect(page.locator("#pf-send")).toBeDisabled();

      // Glisser vers le bas (colonne 1, ligne 3) : l'ombre se cale sur ces cases, le widget suit le pointeur.
      const target = cellBox(geo, 0, 2);
      await page.mouse.move(grab.x, grab.y);
      await page.mouse.down();
      await page.mouse.move(grab.x + (target.x - box.x) + 7, grab.y + (target.y - box.y) - 5, { steps: 6 });
      const shadow = page.locator(".pf-shadow");
      await expect(shadow).toBeVisible();
      await expect(shadow).not.toHaveClass(/pf-bad/);
      // L'ombre glisse vers sa case (150 ms) : on attend qu'elle y soit.
      await expect
        .poll(async () => {
          const sb = (await shadow.boundingBox())!;
          return Math.round(Math.abs(sb.x - target.x) + Math.abs(sb.y - target.y));
        })
        .toBeLessThan(3);
      const lifted = (await widget.boundingBox())!;
      expect(lifted.y).toBeGreaterThan(box.y + 50);
      await page.mouse.up();
      await expect(page.locator(".pf-shadow")).toHaveCount(0);
      await expect(widget).toHaveCSS("grid-row-start", "3");
      await expect(widget).toHaveCSS("grid-column-start", "1");
      await page.locator("#pf-send").click();
      await expect(page.locator("#pf-msg")).toHaveText("Envoyé à la tablette.");
      const sent = tablet.sets.at(-1).layout.widgets.find((w: any) => w.t === "clock");
      expect([sent.c, sent.r]).toEqual([0, 2]);

      // Sur le widget Touches (colonne 3, ligne 1) : chevauchement, ombre rouge, retour à sa place.
      const moved = (await widget.boundingBox())!;
      const from = { x: moved.x + moved.width / 2, y: moved.y + moved.height / 2 };
      const over = cellBox(geo, 2, 0);
      await page.mouse.move(from.x, from.y);
      await page.mouse.down();
      await page.mouse.move(from.x + (over.x - moved.x), from.y + (over.y - moved.y), { steps: 6 });
      await expect(shadow).toHaveClass(/pf-bad/);
      await page.mouse.up();
      await expect(page.locator("#pf-msg")).toHaveText("Ce widget (2 × 2) ne tient pas à cet endroit.");
      await expect(widget).toHaveCSS("grid-row-start", "3");
      await expect(page.locator("#pf-send")).toBeDisabled();

      // Hors de la grille : même chose (une fois le retour fini, 200 ms).
      await expect(widget).toHaveCSS("transform", "none");
      await page.mouse.move(from.x, from.y);
      await page.mouse.down();
      await page.mouse.move(geo.x + geo.width + 40, from.y, { steps: 6 });
      await expect(shadow).toHaveClass(/pf-bad/);
      await page.mouse.up();
      await expect(widget).toHaveCSS("grid-column-start", "1");
      await expect(page.locator("#pf-send")).toBeDisabled();
      // Rien n'a bougé côté tablette.
      expect(tablet.sets.at(-1).layout.widgets.find((w: any) => w.t === "clock").r).toBe(2);
    }
  });

  test("glisse les pastilles du bandeau : ajouter à l'endroit lâché, réordonner, retirer", async ({ page, server }) => {
    const tablet = connected(server);
    {
      await page.goto("/");
      const pills = page.locator("#pf-strip .pf-pill");
      await expect(pills).toHaveText(["Heure", "WiFi"]);
      await centered(page, "#pf-top-hint");

      // De la réserve vers la bande, avant WiFi.
      const date = page.getByRole("button", { name: "Ajouter Date" });
      const wifi = (await pills.nth(1).boundingBox())!;
      await dragBetween(page, date, { x: wifi.x + 4, y: wifi.y + wifi.height / 2 });
      await expect(pills).toHaveText(["Heure", "Date", "WiFi"]);

      // Dans la bande : WiFi passe avant Heure.
      const first = (await pills.first().boundingBox())!;
      await dragBetween(page, pills.nth(2).locator(".pf-pill-main"), { x: first.x + 4, y: first.y + first.height / 2 });
      await expect(pills).toHaveText(["WiFi", "Heure", "Date"]);

      // Hors de la bande : retiré, la réserve s'allume pendant le glisser.
      const heure = pills.nth(1).locator(".pf-pill-main");
      const hb = (await heure.boundingBox())!;
      const res = (await page.locator("#pf-reserve").boundingBox())!;
      await page.mouse.move(hb.x + 10, hb.y + 10);
      await page.mouse.down();
      await page.mouse.move(res.x + 40, res.y + res.height / 2, { steps: 8 });
      await expect(page.locator("#pf-reserve")).toHaveClass(/pf-over/);
      await expect(page.locator(".pf-ghost")).toBeVisible();
      await page.mouse.up();
      await expect(pills).toHaveText(["WiFi", "Date"]);
      await expect(page.locator("#pf-reserve .pf-pill")).toHaveText(["Heure", "Lecture en cours"]);

      // Rien n'est parti avant « Envoyer ».
      const sets = tablet.sets.length;
      await page.locator("#pf-bars-send").click();
      await expect(page.locator("#pf-bars-msg")).toHaveText("Envoyé à la tablette.");
      expect(tablet.sets).toHaveLength(sets + 1);
      expect(tablet.sets.at(-1).topBar).toEqual(["wifi", "date"]);
    }
  });

  test("glisse les profils pour changer l'ordre ; Accueil et Réunion suivent leur profil", async ({ page, server }) => {
    const tablet = connected(server);
    {
      await page.goto("/");
      const chips = page.locator("#pf-chips .cx");
      await expect(chips).toHaveCount(2);
      await page.locator("#pf-new").click();
      await expect(chips).toHaveCount(3);
      await centered(page, "#pf-chips .cx >> nth=1");
      const newTile = page.locator("#pf-new");
      const newBefore = (await newTile.boundingBox())!;

      // Profil 2 passe entre Accueil et Réunion : un repère marque la place pendant le glisser.
      const a = (await chips.nth(1).boundingBox())!;
      const b = (await chips.nth(2).boundingBox())!;
      await page.mouse.move(b.x + b.width / 2, b.y + b.height / 2);
      await page.mouse.down();
      await page.mouse.move(a.x + a.width / 2, a.y + a.height / 2, { steps: 8 });
      await expect(page.locator("#pf-chips .pf-ph")).toHaveCount(1);
      await expect(page.locator(".pf-ghost")).toBeVisible();
      expect(await newTile.boundingBox()).toEqual(newBefore);
      await page.mouse.up();
      await expect(chips).toHaveText([/Accueil/, /Profil 2/, /Réunion/]);
      await page.locator("#pf-send").click();
      await expect(page.locator("#pf-msg")).toHaveText("Envoyé à la tablette.");
      let sent = tablet.sets.at(-1).layout;
      expect(sent.names).toEqual(["Accueil", "Profil 2", "Réunion"]);
      expect([sent.home, sent.call]).toEqual([0, 2]);

      // Accueil, avec son widget, passe en dernier : home et call suivent.
      await centered(page, "#pf-chips .cx >> nth=1");
      const home = (await chips.nth(0).boundingBox())!;
      const last = (await chips.nth(2).boundingBox())!;
      await page.mouse.move(home.x + home.width / 2, home.y + home.height / 2);
      await page.mouse.down();
      await page.mouse.move(last.x + last.width / 2, last.y + last.height / 2, { steps: 10 });
      await page.mouse.up();
      await expect(chips).toHaveText([/Profil 2/, /Réunion/, /Accueil/]);
      await expect(page.locator("#pf-send")).toBeEnabled();
      await page.locator("#pf-send").click();
      await expect(page.locator("#pf-msg")).toHaveText("Envoyé à la tablette.");
      sent = tablet.sets.at(-1).layout;
      expect(sent.names).toEqual(["Profil 2", "Réunion", "Accueil"]);
      expect([sent.home, sent.call]).toEqual([2, 1]);
      expect(sent.widgets.filter((w: any) => w.p === 2).length).toBeGreaterThan(0);
      expect(sent.widgets.filter((w: any) => w.p === 0)).toHaveLength(0);

      // Un simple clic sur une tuile la choisit toujours.
      await chips.nth(0).click();
      await expect(chips.nth(0)).toHaveAttribute("aria-pressed", "true");
    }
  });
});

/** Place l'élément au milieu de la fenêtre : la souris de Playwright ne défile pas toute seule. */
const centered = (page: Page, selector: string) =>
  page.locator(selector).evaluate((e) => e.scrollIntoView({ block: "center", behavior: "instant" }));

/** Cases de la grille de l'aperçu : même calcul que la page (marges et espaces lus dans le style). */
async function gridGeometry(page: Page) {
  return page.evaluate(() => {
    const g = document.getElementById("pf-grid")!;
    const r = g.getBoundingClientRect();
    const cs = getComputedStyle(g);
    const pl = parseFloat(cs.paddingLeft);
    const pt = parseFloat(cs.paddingTop);
    const gx = parseFloat(cs.columnGap);
    const gy = parseFloat(cs.rowGap);
    const cw = (r.width - pl - parseFloat(cs.paddingRight) - gx * 3) / 4;
    const ch = (r.height - pt - parseFloat(cs.paddingBottom) - gy * 3) / 4;
    return { x: r.left, y: r.top, width: r.width, left: r.left + pl, top: r.top + pt, cw, ch, gx, gy };
  });
}
const cellBox = (g: Awaited<ReturnType<typeof gridGeometry>>, c: number, r: number) => ({
  x: g.left + c * (g.cw + g.gx),
  y: g.top + r * (g.ch + g.gy),
});

/** Prend l'élément en son centre et le lâche au point donné (par étapes, comme un doigt). */
async function dragBetween(page: Page, source: ReturnType<Page["locator"]>, to: { x: number; y: number }) {
  const b = (await source.boundingBox())!;
  await page.mouse.move(b.x + b.width / 2, b.y + b.height / 2);
  await page.mouse.down();
  await page.mouse.move(to.x, to.y, { steps: 10 });
  await page.mouse.up();
}
