import fs from "node:fs";
import path from "node:path";
import { expect, test } from "./fixtures.ts";

test.describe.configure({ mode: "serial" });

test.beforeEach(async ({ server }) => {
  server.setOs({ installed: ["Notes", "Discord", "Safari"] });
  fs.writeFileSync(
    path.join(server.dataDir, "pages.json"),
    JSON.stringify({ pages: [{ title: "Réunions", slots: [{ label: "Micro", steps: [{ type: "system", command: "mute-mic" }] }] }] }),
  );
  for (const file of ["keys.json", "apps.json", "links.json", "plugins.json"]) fs.rmSync(path.join(server.dataDir, file), { force: true });
});

test.describe("page locale : touches", () => {
  test("crée une macro, la réordonne, la relit au rechargement puis la vide", async ({ page, server }) => {
    await page.goto("/");
    const touches = page.locator("#pf-touches");
    await expect(touches.getByRole("heading", { name: "Touches" })).toBeVisible();
    await expect(touches.locator("#pf-deck-grid .cx")).toHaveCount(14);

    await touches.getByRole("button", { name: "Touche 1 : libre" }).click();
    await touches.locator("#pf-deck-label").fill("Réunion");
    await touches.getByLabel("Symbole").selectOption("videocam");
    await touches.locator("#pf-deck-use-color").check();

    await touches.locator("#pf-deck-add-type").selectOption("url");
    await touches.locator("#pf-deck-add-step").click();
    await touches.locator('[data-step="0"]').getByLabel("Adresse").fill("https://exemple.test/salle");
    await touches.locator("#pf-deck-add-type").selectOption("wait");
    await touches.locator("#pf-deck-add-step").click();
    await touches.locator('[data-step="1"]').getByLabel("Durée en millisecondes (0 à 10000)").fill("1500");
    await touches.locator("#pf-deck-add-type").selectOption("window");
    await touches.locator("#pf-deck-add-step").click();
    await touches.locator('[data-step="2"]').getByLabel("Appli").fill("Safari");
    await touches.locator('[data-step="2"]').getByLabel("Place").selectOption("left");
    // Ordre final : fenêtre, adresse, attente.
    await touches.getByRole("button", { name: "Monter l'étape 3" }).click();
    await touches.getByRole("button", { name: "Monter l'étape 2" }).click();

    await touches.locator("#pf-deck-save").click();
    await expect(page.locator("#pf-key-dialog")).toBeHidden();
    await expect(page.locator("#pf-deck-keys-msg")).toHaveText("Touche enregistrée.");
    await expect(touches.getByRole("button", { name: "Touche 1 : Réunion" })).toContainText("Macro, 3 étapes");

    const saved = (await server.call("GET", "/deck/keys/0")).json.key;
    expect(saved).toMatchObject({ label: "Réunion", symbol: "videocam", color: "#b8f34a" });
    expect(saved.steps).toEqual([
      { type: "window", app: "Safari", layout: "left" },
      { type: "url", url: "https://exemple.test/salle" },
      { type: "wait", ms: 1500 },
    ]);

    await page.reload();
    await touches.getByRole("button", { name: "Touche 1 : Réunion" }).click();
    await expect(touches.locator("#pf-deck-label")).toHaveValue("Réunion");
    await expect(touches.locator("[data-step]")).toHaveCount(3);
    await expect(touches.locator('[data-step="1"]').getByLabel("Adresse")).toHaveValue("https://exemple.test/salle");

    await touches.locator("#pf-deck-clear").click();
    await expect(page.locator("#pf-deck-keys-msg")).toHaveText("Touche vidée.");
    await expect(touches.getByRole("button", { name: "Touche 1 : libre" })).toBeVisible();
    expect((await server.call("GET", "/deck/keys/0")).json.key).toBeNull();
  });

  test("une étape « Appeler le réseau local » ne garde que le nom de la cible", async ({ page, server }) => {
    await page.goto("/");
    const touches = page.locator("#pf-touches");
    await touches.getByRole("button", { name: "Touche 1 : libre" }).click();
    await touches.locator("#pf-deck-label").fill("Prise");
    await touches.locator("#pf-deck-add-type").selectOption("http");
    await touches.locator("#pf-deck-add-step").click();
    await touches.locator('[data-step="0"]').getByLabel("Nom de la cible (LAN_TARGETS du .env)").fill(" prise ");
    await touches.locator("#pf-deck-save").click();
    await expect(page.locator("#pf-deck-keys-msg")).toHaveText("Touche enregistrée.");
    expect((await server.call("GET", "/deck/keys/0")).json.key.steps).toEqual([{ type: "http", name: "prise" }]);
  });

  test("duplique une page nommée et ouvre la copie ; Perso n'a pas de bouton", async ({ page, server }) => {
    await page.goto("/");
    const touches = page.locator("#pf-touches");
    await expect(touches.getByRole("button", { name: "Dupliquer la page" })).toBeHidden();
    await touches.getByRole("button", { name: "Réunions" }).click();
    await touches.getByRole("button", { name: "Dupliquer la page" }).click();
    await expect(page.locator("#pf-deck-keys-msg")).toHaveText("Page dupliquée.");
    await expect(touches.getByRole("button", { name: "Réunions (copie)" })).toHaveAttribute("aria-pressed", "true");
    await expect(touches.getByRole("button", { name: "Touche 1 : Micro" })).toBeVisible();
    const pages = (await server.call("GET", "/deck")).json.pages;
    expect(pages.map((p: { title: string }) => p.title)).toContain("Réunions (copie)");
  });

  test("une touche à cycle garde son cycle quand on la rouvre et l'enregistre", async ({ page, server }) => {
    fs.writeFileSync(
      path.join(server.dataDir, "keys.json"),
      JSON.stringify({
        slots: [
          {
            label: "Thème",
            cycle: true,
            steps: [
              { type: "text", text: "Jour" },
              { type: "text", text: "Nuit" },
            ],
          },
        ],
      }),
    );
    await page.goto("/");
    const touches = page.locator("#pf-touches");
    await expect(touches.getByRole("button", { name: "Touche 1 : Thème" })).toContainText("Cycle, 2 étapes");
    await touches.getByRole("button", { name: "Touche 1 : Thème" }).click();
    await touches.locator("#pf-deck-save").click();
    await expect(page.locator("#pf-deck-keys-msg")).toHaveText("Touche enregistrée.");
    expect((await server.call("GET", "/deck/keys/0")).json.key.cycle).toBe(true);
  });

  test("une touche large garde sa largeur quand on la rouvre et l'enregistre", async ({ page, server }) => {
    fs.writeFileSync(
      path.join(server.dataDir, "keys.json"),
      JSON.stringify({ slots: [{ label: "Thème", span: 2, steps: [{ type: "text", text: "Jour" }] }] }),
    );
    await page.goto("/");
    const touches = page.locator("#pf-touches");
    await touches.getByRole("button", { name: "Touche 1 : Thème" }).click();
    await touches.locator("#pf-deck-save").click();
    await expect(page.locator("#pf-deck-keys-msg")).toHaveText("Touche enregistrée.");
    expect((await server.call("GET", "/deck/keys/0")).json.key.span).toBe(2);
  });

  test("une touche à seconde action garde son appui long quand on la rouvre et l'enregistre", async ({ page, server }) => {
    const hold = [{ type: "text", text: "Nuit" }];
    fs.writeFileSync(
      path.join(server.dataDir, "keys.json"),
      JSON.stringify({ slots: [{ label: "Thème", steps: [{ type: "text", text: "Jour" }], hold }] }),
    );
    await page.goto("/");
    const touches = page.locator("#pf-touches");
    await touches.getByRole("button", { name: "Touche 1 : Thème" }).click();
    await touches.locator("#pf-deck-save").click();
    await expect(page.locator("#pf-deck-keys-msg")).toHaveText("Touche enregistrée.");
    expect((await server.call("GET", "/deck/keys/0")).json.key.hold).toEqual(hold);
  });

  test("signale une touche sans libellé, sans étape ou refusée par le compagnon", async ({ page, server }) => {
    await page.goto("/");
    const touches = page.locator("#pf-touches");
    await touches.getByRole("button", { name: "Touche 2 : libre" }).click();
    await touches.locator("#pf-deck-save").click();
    await expect(page.locator("#pf-key-msg")).toHaveText("Donne un libellé à la touche.");
    await touches.locator("#pf-deck-label").fill("Vide");
    await touches.locator("#pf-deck-save").click();
    await expect(page.locator("#pf-key-msg")).toHaveText("Ajoute au moins une étape.");

    // Raccourci clavier sans raccourci : refusé par le schéma du compagnon.
    await touches.locator("#pf-deck-add-type").selectOption("keys");
    await touches.locator("#pf-deck-add-step").click();
    await touches.locator("#pf-deck-save").click();
    await expect(page.locator("#pf-key-msg")).toHaveText("Demande invalide : mets à jour l'appli de la tablette");
    await expect(page.locator("#pf-key-dialog")).toBeVisible();
    expect((await server.call("GET", "/deck/keys/1")).json.key).toBeNull();
  });

  test("édite une page de touches nommée", async ({ page, server }) => {
    await page.goto("/");
    const touches = page.locator("#pf-touches");
    await touches.getByRole("button", { name: "Réunions" }).click();
    await expect(touches.getByRole("button", { name: "Touche 1 : Micro" })).toBeVisible();
    await touches.getByRole("button", { name: "Touche 2 : libre" }).click();
    await touches.locator("#pf-deck-label").fill("Ne pas déranger");
    await touches.locator("#pf-deck-add-type").selectOption("system");
    await touches.locator("#pf-deck-add-step").click();
    await touches.locator('[data-step="0"]').getByLabel("Commande").selectOption("dnd");
    await touches.locator("#pf-deck-save").click();
    await expect(page.locator("#pf-deck-keys-msg")).toHaveText("Touche enregistrée.");
    expect((await server.call("GET", "/deck/pages/0/keys/1")).json.key).toMatchObject({
      label: "Ne pas déranger",
      steps: [{ type: "system", command: "dnd" }],
    });
    expect((await server.call("GET", "/deck/keys/1")).json.key).toBeNull();
  });
});

test.describe("page locale : plugins dans les connecteurs", () => {
  const tabletPlugin = async (server: { call: (m: string, p: string) => Promise<{ json: any }> }, id: string) =>
    (await server.call("GET", "/plugins")).json.plugins.find((p: { id: string }) => p.id === id);

  test("appli absente : Teams dans la modale Microsoft, interrupteur désactivé avec la phrase pour l'installer", async ({
    page,
    server,
  }) => {
    await page.goto("/");
    await expect(page.locator(".cx", { hasText: "Teams" })).toHaveCount(0);
    await page.locator('.cx[data-cx="microsoft"]').click();
    await expect(page.locator("#pluglabel")).toHaveText("Teams sur la tablette");
    await expect(page.locator("#plugon")).toBeDisabled();
    await expect(page.locator("#plugon")).not.toBeChecked();
    await expect(page.locator("#plugmsg")).toHaveText("Teams n'est pas installé sur cet ordinateur : installe-le pour l'activer.");
    expect(await tabletPlugin(server, "teams")).toBeUndefined();
  });

  test("appli installée : coupe puis rallume Discord depuis sa modale", async ({ page, server }) => {
    await page.goto("/");
    const discord = page.locator(".cx", { hasText: "Discord" });
    await expect(discord).toContainText("Actif");
    await discord.click();
    const on = page.locator("#plugon");
    await expect(on).toBeEnabled();
    await expect(on).toBeChecked();
    await on.uncheck();
    await expect(page.locator("#plugmsg")).toHaveText("Discord coupé.");
    await expect(discord).toContainText("Coupé");
    expect((await tabletPlugin(server, "discord")).on).toBe(false);
    await page.reload();
    await expect(page.locator(".cx", { hasText: "Discord" })).toContainText("Coupé");
    await page.locator(".cx", { hasText: "Discord" }).click();
    await expect(page.locator("#plugon")).not.toBeChecked();
    await page.locator("#plugon").check();
    await expect(page.locator("#plugmsg")).toHaveText("Discord activé.");
    await expect(page.locator(".cx", { hasText: "Discord" })).toContainText("Actif");
    expect((await tabletPlugin(server, "discord")).on).toBe(true);
  });

  test("fonctions de la tablette : coupe puis rallume le mixage du son", async ({ page, server }) => {
    await page.goto("/");
    await expect(page.locator("#pf-plugins")).toHaveCount(0);
    const list = page.locator("#connecteurs").getByRole("heading", { name: "Fonctions de la tablette" });
    await expect(list).toBeVisible();
    const mixer = page.locator("#fnlist").getByLabel("Mixage du son");
    await expect(mixer).toBeChecked();
    await mixer.uncheck();
    await expect(page.locator("#fnmsg")).toHaveText("Mixage du son coupé.");
    expect((await server.call("GET", "/deck/features")).json.off).toEqual(["mixer"]);
    await page.reload();
    await expect(page.locator("#fnlist").getByLabel("Mixage du son")).not.toBeChecked();
    await page.locator("#fnlist").getByLabel("Mixage du son").check();
    await expect(page.locator("#fnmsg")).toHaveText("Mixage du son activé.");
    expect((await server.call("GET", "/deck/features")).json.off).toEqual([]);
  });
});

test.describe("page locale : applis et sites", () => {
  test("ajoute une appli installée puis la retire", async ({ page, server }) => {
    await page.goto("/");
    const section = page.locator("#pf-applis");
    await expect(section.getByRole("button", { name: "Retirer Safari" })).toHaveCount(0);
    await section.getByLabel("Appli installée à ajouter").selectOption("Safari");
    await section.locator("#pf-deck-app-add").click();
    await expect(page.locator("#pf-deck-apps-msg")).toHaveText("Safari ajoutée.");
    await expect(section.locator("#pf-deck-apps").getByText("Safari")).toBeVisible();
    const tiles = async () =>
      (await server.call("GET", "/deck")).json.pages.find((p: { apps: boolean }) => p.apps).tiles.map((t: { app: string }) => t.app);
    expect(await tiles()).toContain("Safari");

    await section.getByRole("button", { name: "Retirer Safari" }).click();
    await expect(page.locator("#pf-deck-apps-msg")).toHaveText("Safari retiré.");
    expect(await tiles()).not.toContain("Safari");

    await section.getByRole("button", { name: "Liste de départ" }).click();
    await expect(page.locator("#pf-deck-apps-msg")).toHaveText("Liste de départ rétablie.");
  });

  test("ajoute un site proposé et une adresse libre, refuse une adresse invalide, retire", async ({ page, server }) => {
    await page.goto("/");
    const section = page.locator("#pf-applis");
    await section.getByLabel("Site proposé à ajouter").selectOption({ label: "YouTube" });
    await section.locator("#pf-deck-link-add").click();
    await expect(page.locator("#pf-deck-links-msg")).toHaveText("Site ajouté.");
    await expect(section.getByRole("button", { name: "Retirer YouTube" })).toBeVisible();

    await section.getByLabel("Adresse du site").fill("exemple.test/radio");
    await section.getByLabel("Nom du site").fill("Radio");
    await section.locator("#pf-deck-link-new").click();
    await expect(section.getByRole("button", { name: "Retirer Radio" })).toBeVisible();
    await expect(section.getByLabel("Adresse du site")).toHaveValue("");
    expect((await server.call("GET", "/local/config/links", undefined, false)).json.links.map((l: { url: string }) => l.url)).toEqual([
      "https://www.youtube.com",
      "https://exemple.test/radio",
    ]);

    await section.getByLabel("Adresse du site").fill("ftp://exemple.test");
    await section.locator("#pf-deck-link-new").click();
    await expect(page.locator("#pf-deck-links-msg")).toHaveText("Adresse invalide (ex. netflix.com)");
    await expect(section.getByLabel("Adresse du site")).toHaveValue("ftp://exemple.test");

    await section.getByRole("button", { name: "Retirer YouTube" }).click();
    await expect(section.getByRole("button", { name: "Retirer YouTube" })).toHaveCount(0);
    await section.getByRole("button", { name: "Retirer Radio" }).click();
    await expect(section.getByText("Aucun site")).toBeVisible();
  });
});
