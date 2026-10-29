import { expect, test } from "./fixtures.ts";

test.describe("page locale GET /", () => {
  test("affiche le nom et l'adresse de l'ordinateur", async ({ page, server }) => {
    await page.goto("/");
    await expect(page.locator("#name")).toHaveText("Ordi de test");
    await expect(page.locator("#os")).toContainText("Compagnon prêt");
    await expect(page.locator("#addr")).toHaveText(`127.0.0.1:${new URL(server.url).port}`);
    await expect(page.locator("#state")).toHaveText("Tablette non connectée");
    await expect(page.locator("#pairing")).toBeHidden();
  });

  test("montre le code d'appairage demandé par une tablette", async ({ page, server }) => {
    await page.goto("/");
    await expect(page.locator("#pairing")).toBeHidden();
    const req = await server.call("POST", "/pair/request", { tablet: "Tablette QA" }, false);
    expect(req.status).toBe(200);
    const { json } = await server.call("GET", "/local/status", undefined, false);
    const code: string = json.pairing.code;

    // Sans recharger : la page se rafraîchit seule.
    await expect(page.locator("#pairing")).toBeVisible();
    await expect(page.locator("#code")).toHaveText(`${code.slice(0, 3)} ${code.slice(3)}`);
    await expect(page.locator("#tablet")).toHaveText("Tablette QA");
  });

  test("enregistre, refuse puis retire un lien ICS", async ({ page }) => {
    await page.goto("/");
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
    let settings = { theme: "CADRAN", brightness: 0.6, volume: 0.4, nightDim: true, focusDnd: true };
    const received: Record<string, unknown>[] = [];
    const state = () => ({ mode: "home", app: "1.4.0", model: "Unowhy 4.2", battery: { level: 82, charging: true }, settings });
    let running = true;
    const tablet = (async () => {
      while (running) {
        const { json } = await server.call("POST", "/tablet/poll", { state: state() });
        for (const { id, command } of json.commands ?? []) {
          const { type, ...change } = command;
          received.push(change);
          settings = { ...settings, ...change };
          await server.call("POST", "/tablet/result", { id, ok: true, state: state() });
        }
      }
    })();

    await expect(page.locator("#tabletcard")).toBeVisible();
    await expect(page.locator("#tabletinfo")).toHaveText("Unowhy 4.2 · appli 1.4.0 · batterie 82 % (en charge)");
    await expect(page.getByRole("button", { name: "Cadran", exact: true })).toHaveAttribute("aria-pressed", "true");
    await expect(page.locator("#brightnessvalue")).toHaveText("60 %");
    await expect(page.locator("#nightdim")).toBeChecked();

    await page.getByRole("button", { name: "Rétro" }).click();
    await expect(page.locator("#tabletmsg")).toHaveText("Réglé sur la tablette.");
    await expect(page.getByRole("button", { name: "Rétro" })).toHaveAttribute("aria-pressed", "true");

    await page.locator("#nightdim").uncheck();
    await expect(page.locator("#tabletmsg")).toHaveText("Réglé sur la tablette.");
    await page.locator("#volume").fill("75");
    await expect(page.locator("#volumevalue")).toHaveText("75 %");
    await expect.poll(() => received.length).toBe(3);
    expect(received).toEqual([{ theme: "RETRO" }, { nightDim: false }, { volume: 0.75 }]);
    await expect(page.locator("#nightdim")).not.toBeChecked();

    // Son dernier poll (jusqu'à 25 s) finit sans elle.
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
