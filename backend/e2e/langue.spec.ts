import { expect, test } from "./fixtures.ts";

/** Langue de la page locale (src/i18n.ts) : celle du navigateur, sinon le choix Français / English, retenu. */
test.describe.configure({ mode: "serial" });

test("navigateur en français : la page reste en français ; English la passe en anglais, retenu", async ({ page }) => {
  await page.goto("/");
  await expect(page.locator("html")).toHaveAttribute("lang", "fr");
  await expect(page.locator("#state")).toHaveText("Tablette non connectée");

  await page.getByRole("link", { name: "English" }).click();
  await expect(page.locator("html")).toHaveAttribute("lang", "en");
  await expect(page.locator("#state")).toHaveText("Tablet not connected");
  // Texte posé par un script après le chargement (menu bâti depuis les sections) : traduit lui aussi.
  await expect(page.locator("#menu")).toContainText("Connect a tablet");
  // Le nom de chaque langue reste écrit dans sa langue.
  await expect(page.getByRole("link", { name: "Français" })).toBeVisible();

  await page.reload();
  await expect(page.locator("#state")).toHaveText("Tablet not connected");

  await page.getByRole("link", { name: "Français" }).click();
  await expect(page.locator("#state")).toHaveText("Tablette non connectée");
});

test.describe("navigateur en anglais", () => {
  test.use({ locale: "en-GB" });

  test("sans choix : la page s'ouvre en anglais, et ses appels au compagnon demandent l'anglais", async ({ page }) => {
    const asked = page.waitForRequest((req) => req.url().endsWith("/local/status"));
    await page.goto("/");
    await expect(page.locator("#state")).toHaveText("Tablet not connected");
    expect((await asked).headers()["accept-language"]).toBe("en");
  });
});
