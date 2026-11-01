import { createHash, randomBytes } from "node:crypto";
import { pairingCode } from "../test/server.ts";
import { expect, test } from "./fixtures.ts";

test.describe("page locale GET /", () => {
  test("affiche le nom et l'adresse de l'ordinateur", async ({ page, server }) => {
    await page.goto("/");
    await expect(page.locator("#name")).toHaveText("Ordi de test");
    await expect(page.locator("#os")).toContainText("Compagnon prêt");
    await expect(page.locator("#addr")).toHaveText(`127.0.0.1:${new URL(server.url).port}`);
    await expect(page.locator("#state")).toHaveText("Tablette non connectée");
    await expect(page.locator("#pairing")).toBeHidden();
    // Police Figtree servie par le compagnon lui-même (hors ligne).
    expect(await page.evaluate(async () => (await document.fonts.load("16px Figtree")).length)).toBeGreaterThan(0);
  });

  test("jetons Console : fond ardoise la nuit, papier chaud le jour, police Figtree", async ({ page }) => {
    const style = () =>
      page.evaluate(() => ({ bg: getComputedStyle(document.body).backgroundColor, font: getComputedStyle(document.body).fontFamily }));
    await page.emulateMedia({ colorScheme: "dark" });
    await page.goto("/");
    expect(await style()).toMatchObject({ bg: "rgb(23, 24, 27)" });
    expect((await style()).font).toContain("Figtree");
    await page.emulateMedia({ colorScheme: "light" });
    expect(await style()).toMatchObject({ bg: "rgb(244, 242, 238)" });
  });

  test("barre de gauche : sans tablette, « Connecter une tablette » en tête, le trait suit la section", async ({ page }) => {
    await page.goto("/");
    const links = page.locator("#menu a");
    await expect(links.first()).toHaveText("Connecter une tablette");
    await expect(links.filter({ hasText: "Tablette" })).toHaveCount(1);
    await expect(page.locator("#menu a", { hasText: "Connecteurs" })).toHaveAttribute("href", "#connecteurs");
    await page.setViewportSize({ width: 1280, height: 400 });
    await page.locator("#menu a", { hasText: "Connecteurs" }).click();
    await expect(page.locator("#menu a", { hasText: "Connecteurs" })).toHaveAttribute("aria-current", "true");
  });

  test("compagnon arrêté : la page le dit et cache le code et les réglages", async ({ page }) => {
    await page.goto("/");
    await expect(page.locator("#os")).toContainText("Compagnon prêt");
    await page.route("**/local/status", (route) => route.abort());
    await expect(page.locator("#os")).toHaveText("Compagnon arrêté : rouvre « Mate »");
    await expect(page.locator("#srvdot")).toHaveClass(/ko/);
    await expect(page.locator("#state")).toHaveText("Tablette : état inconnu");
    await expect(page.locator("body")).toHaveClass(/down/);
  });

  test("Connecter une IA : adresse de la porte MCP et configurations à copier", async ({ page, server, context }) => {
    await context.grantPermissions(["clipboard-read", "clipboard-write"]);
    await page.goto("/");
    const url = `http://127.0.0.1:${new URL(server.url).port}/mcp`;
    await expect(page.locator("#menu a", { hasText: "Connecter une IA" })).toHaveAttribute("href", "#ia");
    await expect(page.locator("#mcpurl")).toHaveText(url);
    await expect(page.locator("#mcpcode")).toHaveText(`claude mcp add --transport http mate ${url}`);
    const desktop = JSON.parse((await page.locator("#mcpdesktop").textContent())!);
    expect(desktop.mcpServers.mate.args).toContain(url);
    expect(desktop.mcpServers.mate.args[0]).toMatch(/mcp-bridge\.(ts|mjs)$/);
    await page.locator('[data-copy="mcpurl"]').click();
    await expect(page.locator("#mcpmsg")).toHaveText("Copié.");
    expect(await page.evaluate(() => navigator.clipboard.readText())).toBe(url);
  });

  test("cache la carte « Cette appli » hors appli portable", async ({ page }) => {
    await page.goto("/");
    await expect(page.locator("#state")).toHaveText("Tablette non connectée");
    await expect(page.locator("#panel")).toBeHidden();
  });

  test("carte « Cette appli » : la ligne de la température du processeur suit l'état du capteur", async ({ page }) => {
    const panel = (capteurs: boolean | null) =>
      page.route("**/local/panel", (route) =>
        route.fulfill({
          json: { portable: true, app: "C:/Mate", version: "1", autostart: false, firewall: null, capteurs, extension: "x" },
        }),
      );
    const check = async (capteurs: boolean | null) => {
      await page.unroute("**/local/panel");
      await panel(capteurs);
      await page.goto("/");
      await expect(page.locator("#panel")).toBeVisible();
    };
    await check(null);
    await expect(page.locator("#capteursrow")).toBeHidden();
    await check(false);
    await expect(page.locator("#capteursrow")).toBeVisible();
    await expect(page.locator("#capteursstate")).toContainText("désactivée");
    await expect(page.locator("#capteursopen")).toBeVisible();
    await check(true);
    await expect(page.locator("#capteursstate")).toContainText("activée dans le widget");
    await expect(page.locator("#capteursopen")).toBeHidden();
  });

  test("reçoit le code affiché par la tablette et la connecte", async ({ page, server }) => {
    await page.goto("/");
    await expect(page.locator("#pairing")).toBeHidden();
    const tablet = randomBytes(32).toString("base64url");
    const commit = createHash("sha256").update(tablet).digest("base64url");
    const req = await server.call("POST", "/pair/request", { tablet: "Tablette QA", commit }, false);
    expect(req.status).toBe(200);
    expect((await server.call("POST", "/pair/reveal", { request: req.json.request, nonce: tablet }, false)).status).toBe(200);
    const code = pairingCode("", tablet, req.json.nonce);

    // Sans recharger : la page se rafraîchit seule, la case a le focus.
    await expect(page.locator("#pairing")).toBeVisible();
    await expect(page.locator("#tablet")).toHaveText("Tablette QA");
    await expect(page.locator("#paircode")).toBeFocused();
    // Ligne du jour : le point a commencé à avancer sur les 2 minutes du code.
    const progress = Number(await page.locator("#pairtimer").evaluate((el) => (el as HTMLElement).style.getPropertyValue("--p")));
    expect(progress).toBeGreaterThanOrEqual(0);
    expect(progress).toBeLessThan(0.2);

    await page.locator("#paircode").fill("000 000" === code ? "111 111" : "000 000");
    await page.getByRole("button", { name: "Connecter la tablette" }).click();
    await expect(page.locator("#pairmsg")).toHaveText("Code incorrect");

    await page.locator("#paircode").fill(`${code.slice(0, 3)} ${code.slice(3)}`);
    await page.getByRole("button", { name: "Connecter la tablette" }).click();
    await expect(page.locator("#pairmsg")).toHaveText("Tablette connectée : « Tablette QA ».");
    expect((await server.call("POST", "/pair/confirm", { request: req.json.request }, false)).json.token).toBeTruthy();
    await expect(page.locator("#pairing")).toBeHidden();
  });

  test("connecteurs : Microsoft pas encore disponible, Figma bientôt, Jira à régler sur l'ordinateur", async ({ page }) => {
    await page.goto("/");
    const tile = (name: string) => page.locator(".cx", { hasText: name });
    await expect(tile("Microsoft")).toContainText("Non connecté");
    await expect(tile("Microsoft")).toHaveClass(/na/);
    await expect(tile("Figma")).toBeDisabled();
    await expect(tile("Figma")).toContainText("Bientôt");
    await expect(page.locator(".cxpanel:visible")).toHaveCount(0);

    await tile("Microsoft").click();
    await expect(page.locator("#agenda")).toBeVisible();
    await expect(tile("Microsoft")).toHaveAttribute("aria-pressed", "true");
    await expect(page.locator("#cxtitle")).toHaveText("Relier Microsoft");
    await expect(page.locator("#cxnote")).toHaveText("Cette connexion n'est pas encore disponible sur cet ordinateur.");
    await page.getByRole("button", { name: "Relier par un lien d'agenda" }).click();
    await expect(tile("Lien d'agenda")).toHaveAttribute("aria-pressed", "true");
    await expect(page.locator("#cxlist li")).toHaveCount(3);
    await expect(page.locator("#calurl")).toBeFocused();

    // La modale se ferme par Échap ; une autre icône en ouvre une nouvelle.
    await page.keyboard.press("Escape");
    await expect(page.locator("#cxdialog")).toBeHidden();
    await expect(tile("Lien d'agenda")).toHaveAttribute("aria-pressed", "false");
    await expect(tile("Jira")).toContainText("Non connecté");
    await tile("Jira").click();
    await expect(page.locator("#cxdialog")).toBeVisible();
    await expect(page.locator("#agenda")).toBeHidden();
    await expect(page.locator("#cxtitle")).toHaveText("Relier Jira");
    await expect(page.locator("#jira #cxlist li")).toHaveCount(3);
    await expect(page.locator("#jirastate")).toBeHidden();
    await expect(page.locator("#plugon")).toBeDisabled();
    await expect(page.locator("#plugmsg")).toContainText("Jira n'est pas relié");
    // Aucun détail technique dans les états des icônes.
    await expect(page.locator(".connectors")).not.toContainText(".env");
  });

  test("enregistre, refuse puis retire un lien ICS", async ({ page }) => {
    await page.goto("/");
    await page.locator(".cx", { hasText: "Lien d'agenda" }).click();
    await expect(page.locator("#calurl")).toBeFocused();
    await expect(page.locator("#calstate")).toHaveText("Aucun agenda : la tablette propose de le relier.");
    // Sans compte Microsoft/Google configuré, le lien ICS est la seule voie : déjà déplié.
    await expect(page.locator("#ics")).toHaveAttribute("open", "");

    await page.locator("#calurl").fill("ftp://agenda.example/cal.ics");
    await page.getByRole("button", { name: "Enregistrer" }).click();
    await expect(page.locator("#calmsg")).toHaveText(/^Lien refusé : /);

    await page.locator("#calurl").fill("demo");
    await page.getByRole("button", { name: "Enregistrer" }).click();
    await expect(page.locator("#calmsg")).toHaveText(/^Agenda enregistré : \d+ événement\(s\) aujourd'hui\.$/);
    await expect(page.locator("#calstate")).toHaveText("Lien enregistré : agenda d'exemple");
    await expect(page.locator(".cx", { hasText: "Lien d'agenda" })).toContainText("Connecté");
    await expect(page.locator("#calurl")).toHaveValue("");

    await page.getByRole("button", { name: "Retirer" }).click();
    await expect(page.locator("#calmsg")).toHaveText("Agenda retiré.");
    await expect(page.locator("#calstate")).toHaveText("Aucun agenda : la tablette propose de le relier.");
  });

  test("n'affiche que l'hôte d'un lien ICS, jamais le lien entier", async ({ page, server }) => {
    // Lien injoignable : enregistré quand même, signalé illisible.
    const secret = "http://127.0.0.1:9/secret-token/basic.ics";
    const saved = await server.call("PUT", "/local/calendar", { urls: [secret] }, false);
    expect(saved.status).toBe(200);
    await page.goto("/");
    await expect(page.locator("#calstate")).toHaveText("Lien enregistré : 127.0.0.1:9");
    await expect(page.locator("body")).not.toContainText("secret-token");
    await server.call("PUT", "/local/calendar", { urls: [] }, false);
  });

  // Après les tests qui veulent la tablette déconnectée : la fausse tablette reste vue connectée 35 s.
  test("règle la tablette connectée depuis la carte Tablette", async ({ page, server }) => {
    await page.goto("/");
    await expect(page.locator("#tabletcard")).toBeHidden();

    // Fausse tablette : un poll ouvert, chaque ordre exécuté sur son état puis renvoyé avec le résultat.
    let settings = { theme: "CONSOLE", brightness: 0.6, volume: 0.4, nightDim: true, focusDnd: true };
    const received: Record<string, unknown>[] = [];
    const state = () => ({
      mode: "home",
      app: "1.4.0",
      name: "Tablette 4.2",
      model: "Tab 4.2",
      battery: { level: 82, charging: true },
      settings,
    });
    let running = true;
    const tablet = (async () => {
      while (running) {
        const { json } = await server.call("POST", "/tablet/poll", { state: state() });
        for (const { id, command } of json.commands ?? []) {
          // Les ordres venus de l'ordinateur (disposition, appli au premier plan) ne sont pas des réglages.
          if (command.type !== "settings") {
            await server.call("POST", "/tablet/result", { id, ok: true, state: state() });
            continue;
          }
          const { type, ...change } = command;
          received.push(change);
          settings = { ...settings, ...change };
          await server.call("POST", "/tablet/result", { id, ok: true, state: state() });
        }
      }
    })();

    await expect(page.locator("#tabletcard")).toBeVisible();
    await expect(page.locator("#tabletinfo")).toHaveText("Tab 4.2 · appli 1.4.0 · batterie 82 % (en charge)");
    // Tablette connectée : sa carte passe avant l'état et l'adresse, et l'onglet porte son nom.
    await expect(page).toHaveTitle("Compagnon · Tablette 4.2");
    const top = (id: string) => page.locator(id).evaluate((el) => el.getBoundingClientRect().top);
    expect(await top("#tabletcard")).toBeLessThan(await top("#connect"));
    await expect(page.locator("#tabletname")).toHaveText("Tablette 4.2");
    await expect(page.locator("#menu a").first()).toHaveText("Tablette");
    await expect(page.getByRole("button", { name: "Console", exact: true })).toHaveAttribute("aria-pressed", "true");
    await expect(page.locator("#brightnessvalue")).toHaveText("60 %");
    await expect(page.locator("#nightdim")).toBeChecked();

    await page.getByRole("button", { name: "Console jour" }).click();
    await expect(page.locator("#tabletmsg")).toHaveText("Réglé sur la tablette.");
    await expect(page.getByRole("button", { name: "Console jour" })).toHaveAttribute("aria-pressed", "true");

    // Un seul bouton reste pressé.
    await page.getByRole("button", { name: "Contraste élevé" }).click();
    await expect(page.getByRole("button", { name: "Contraste élevé" })).toHaveAttribute("aria-pressed", "true");
    await expect(page.getByRole("button", { name: "Console jour" })).toHaveAttribute("aria-pressed", "false");

    // Hush : aperçu à ses couleurs (accent rose).
    await page.getByRole("button", { name: "Hush" }).click();
    await expect(page.getByRole("button", { name: "Hush" })).toHaveAttribute("aria-pressed", "true");
    await expect(page.getByRole("button", { name: "Contraste élevé" })).toHaveAttribute("aria-pressed", "false");
    await expect(page.locator("#twintheme")).toHaveText("Hush");
    expect(await page.locator("#twin").evaluate((el) => el.style.getPropertyValue("--t-now"))).toBe("#FF6EB4");

    await page.locator("#nightdim").uncheck();
    await expect(page.locator("#tabletmsg")).toHaveText("Réglé sur la tablette.");
    await page.locator("#volume").fill("75");
    await expect(page.locator("#volumevalue")).toHaveText("75 %");
    await expect.poll(() => received.length).toBe(5);
    expect(received).toEqual([{ theme: "CONSOLE_JOUR" }, { theme: "CONTRASTE" }, { theme: "HUSH" }, { nightDim: false }, { volume: 0.75 }]);
    await expect(page.locator("#nightdim")).not.toBeChecked();

    // Ouverte depuis « Régler depuis l'ordi » : la carte est mise en avant.
    expect((await server.call("POST", "/local/open", { section: "tablette" })).status).toBe(200);
    await page.goto("/?section=tablette");
    await expect(page.locator("#tabletcard .card")).toHaveClass(/focus/);

    // Son dernier poll (jusqu'à 25 s) finit sans elle.
    running = false;
    void tablet.catch(() => {});
  });

  test("cherche puis installe la mise à jour de la tablette depuis la carte", async ({ page, server }) => {
    // Fausse tablette en kiosque, rien cherché encore : la page lance la recherche seule.
    let app = "0.2.11";
    let update = { checked: false, available: null as string | null, installing: false, error: null, kiosk: true };
    const actions: string[] = [];
    const state = () => ({ mode: "home", app, model: "Tab 4.2", battery: null, settings: null, update });
    let running = true;
    const tablet = (async () => {
      while (running) {
        const { json } = await server.call("POST", "/tablet/poll", { state: state() });
        for (const { id, command } of json.commands ?? []) {
          // Les autres ordres (disposition lue par la carte Profils) ne comptent pas ici.
          if (command.type !== "update") {
            await server.call("POST", "/tablet/result", { id, ok: true, state: state() });
            continue;
          }
          actions.push(command.action);
          const available = app === "0.2.11" ? "0.2.12" : null;
          update = command.action === "check" ? { ...update, checked: true, available } : { ...update, installing: true };
          await server.call("POST", "/tablet/result", { id, ok: true, state: state() });
        }
      }
    })();

    await page.goto("/");
    await expect(page.locator("#updatestate")).toHaveText("Version 0.2.12 disponible (installée : 0.2.11).");
    expect(actions).toEqual(["check"]);
    await page.getByRole("button", { name: "Mettre à jour" }).click();
    await expect(page.locator("#updatestate")).toHaveText(/^Installation de la version 0\.2\.12 en cours/);
    await expect(page.locator("#updatebtn")).toBeHidden();
    expect(actions).toEqual(["check", "install"]);

    // Appli remplacée puis relancée : nouvelle version, rien cherché encore, la page relance la recherche.
    app = "0.2.12";
    update = { checked: false, available: null, installing: false, error: null, kiosk: true };
    // Premier poll de l'appli relancée : il remplace celui en cours, qui rend la main à la boucle.
    void server.call("POST", "/tablet/poll", { state: state() }).catch(() => {});
    await expect(page.locator("#updatestate")).toHaveText("Appli à jour (0.2.12).");
    await expect(page.getByRole("button", { name: "Rechercher" })).toBeVisible();
    expect(actions).toEqual(["check", "install", "check"]);

    running = false;
    void tablet.catch(() => {});
  });

  test("le bouton « Faire sonner » lance puis arrête la sonnerie de la tablette", async ({ page, server }) => {
    const rings: boolean[] = [];
    const settings = { theme: "CONSOLE", brightness: 0.6, volume: 0.4, nightDim: true, focusDnd: true };
    const state = { mode: "home", app: "0.2.12", model: "Tab 4.2", battery: null, settings, update: null };
    let running = true;
    const tablet = (async () => {
      while (running) {
        const { json } = await server.call("POST", "/tablet/poll", { state });
        for (const { id, command } of json.commands ?? []) {
          if (command.type === "ring") rings.push(command.on);
          await server.call("POST", "/tablet/result", { id, ok: true, state });
        }
      }
    })();

    await page.goto("/");
    await page.getByRole("button", { name: "Faire sonner" }).click();
    await expect(page.locator("#tabletmsg")).toHaveText("La tablette sonne : touche son écran pour l'arrêter.");
    await page.getByRole("button", { name: "Arrêter la sonnerie" }).click();
    await expect(page.locator("#tabletmsg")).toHaveText("Sonnerie arrêtée.");
    await expect(page.getByRole("button", { name: "Faire sonner" })).toBeVisible();
    expect(rings).toEqual([true, false]);

    running = false;
    void tablet.catch(() => {});
  });

  test("?section=agenda met la carte Agenda en avant", async ({ page }) => {
    await page.goto("/?section=agenda");
    await expect(page.locator("#agenda")).toHaveClass(/focus/);
    await expect(page.locator("#icshelp")).toHaveAttribute("open", "");
    await expect(page.locator("#calurl")).toBeFocused();
  });
});
