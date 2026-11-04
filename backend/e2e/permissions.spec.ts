import type { Page } from "@playwright/test";
import { expect, test } from "./fixtures.ts";

// Écran « Autorisations du Mac » de la page locale : la route /local/permissions est simulée (le vrai macOS n'est pas
// sollicité) ; ce que la page envoie à POST /local/permissions/:id/request est relevé.

type Item = {
  id: string;
  title: string;
  why: string;
  state: string;
  essential: boolean;
  action: "request" | "settings" | null;
  appear: string;
};

const item = (over: Partial<Item> & { id: string }): Item => ({
  title: over.id,
  why: "Pour tester.",
  state: "not-asked",
  essential: false,
  action: "request",
  appear: "macOS affiche une fenêtre.",
  ...over,
});

const FRESH: Item[] = [
  item({ id: "accessibility", title: "Accessibilité", essential: true, why: "Pour placer les fenêtres." }),
  item({
    id: "automation-com.apple.systemevents",
    title: "Automatisation de System Events",
    essential: true,
    appear: "macOS affiche une fenêtre : « Mate » souhaite contrôler « System Events ».",
  }),
  item({ id: "automation-com.google.Chrome", title: "Automatisation de Google Chrome", state: "denied", action: "settings" }),
  item({ id: "full-disk", title: "Accès complet au disque", action: "settings" }),
  item({ id: "screen", title: "Enregistrement de l'écran", state: "granted", action: null }),
];

async function simulate(page: Page, initial: Item[]) {
  const state = { items: initial, requests: [] as string[] };
  await page.route("**/local/permissions", (route) => route.fulfill({ json: { items: state.items } }));
  await page.route("**/local/permissions/*/request", async (route) => {
    const id = decodeURIComponent(new URL(route.request().url()).pathname.split("/")[3]);
    state.requests.push(id);
    const item = state.items.find((i) => i.id === id)!;
    state.items = state.items.map((i) => (i.id === id ? { ...i, state: "granted", action: null } : i));
    await route.fulfill({ json: { ok: true, action: item.action } });
  });
  return state;
}

test.describe("autorisations du Mac", () => {
  test("une autorisation indispensable manque : l'écran s'ouvre seul, indispensables d'abord", async ({ page }) => {
    await simulate(page, FRESH);
    await page.goto("/");
    const dialog = page.locator("#permdlg");
    await expect(dialog).toBeVisible();
    await expect(dialog.locator(".label")).toHaveText(["Indispensables", "Facultatives"]);
    await expect(dialog.locator(".perm").first()).toContainText("Accessibilité");
    await expect(dialog.locator('[data-id="screen"] .state')).toHaveText("Accordée");
    await expect(dialog.locator('[data-id="automation-com.google.Chrome"] .state')).toHaveText("Refusée");
    await expect(dialog.locator('[data-id="full-disk"] .state')).toHaveText("Pas encore demandée");
    await expect(dialog.locator('[data-id="screen"] button')).toHaveCount(0);
    // « Ouvrir les Réglages » quand macOS ne montrera pas de fenêtre ou que c'est refusé.
    await expect(dialog.locator('[data-id="full-disk"] button')).toHaveText("Ouvrir les Réglages");
    await expect(dialog.locator('[data-id="automation-com.google.Chrome"] button')).toHaveText("Ouvrir les Réglages");
    await expect(dialog.locator('[data-id="accessibility"] button')).toHaveText("Autoriser");
    // Un nom accessible propre à chaque ligne : les boutons sont identiques à l'écran.
    await expect(dialog.getByRole("button", { name: "Autoriser Accessibilité" })).toHaveCount(1);
    await expect(dialog.getByRole("button", { name: "Ouvrir les Réglages pour Accès complet au disque" })).toHaveCount(1);
    await expect(dialog.getByRole("button", { name: "Ouvrir les Réglages pour Automatisation de Google Chrome" })).toHaveCount(1);
  });

  test("Autoriser explique d'abord ce qui va apparaître, puis déclenche la fenêtre de macOS", async ({ page }) => {
    const state = await simulate(page, FRESH);
    await page.goto("/");
    const row = page.locator('#permdlg [data-id="automation-com.apple.systemevents"]');
    await row.getByRole("button", { name: "Autoriser" }).click();
    await expect(row.locator(".appear")).toContainText("souhaite contrôler « System Events »");
    expect(state.requests).toEqual([]);
    await row.getByRole("button", { name: "Annuler" }).click();
    await expect(row.locator(".appear")).toHaveCount(0);
    await row.getByRole("button", { name: "Autoriser" }).click();
    await row.getByRole("button", { name: "Continuer" }).click();
    expect(state.requests).toEqual(["automation-com.apple.systemevents"]);
    await expect(page.locator("#permmsg")).toContainText("Réponds à la fenêtre de macOS");
    // Relu seul : l'état passe à Accordée sans recharger.
    await expect(row.locator(".state")).toHaveText("Accordée", { timeout: 6_000 });
  });

  test("Ouvrir les Réglages demande tout de suite, sans explication", async ({ page }) => {
    const state = await simulate(page, FRESH);
    await page.goto("/");
    await page.locator('#permdlg [data-id="full-disk"]').getByRole("button", { name: "Ouvrir les Réglages" }).click();
    expect(state.requests).toEqual(["full-disk"]);
    await expect(page.locator("#permmsg")).toContainText("Réglages Système est ouvert");
  });

  test("Plus tard : l'écran ne se rouvre pas, le rappel et la carte restent", async ({ page }) => {
    await simulate(page, FRESH);
    await page.goto("/");
    await expect(page.locator("#permlater")).toHaveText("Plus tard");
    await page.locator("#permlater").click();
    await expect(page.locator("#permdlg")).toBeHidden();
    await page.reload();
    await expect(page.locator("#autorisations")).toBeVisible();
    await expect(page.locator("#permdlg")).toBeHidden();
    // Rappel en tête et ligne dans la barre de gauche.
    await expect(page.locator("#access")).toBeVisible();
    await expect(page.locator("#accessline")).toBeVisible();
    // Le rappel nomme ce qui manque, au pluriel quand il en manque plusieurs.
    await expect(page.locator("#accesstitle")).toHaveText("Des autorisations manquent sur ce Mac");
    await expect(page.locator("#accesstext")).toContainText("Accessibilité, Automatisation de System Events");
    await expect(page.locator("#permsummary")).toContainText("1 autorisation sur 5 accordée");
    await expect(page.locator("#menu a", { hasText: "Autorisations du Mac" })).toHaveAttribute("href", "#autorisations");
    await page.locator("#accessopen").click();
    await expect(page.locator("#permdlg")).toBeVisible();
  });

  test("tout est accordé : rien ne s'ouvre, pas de rappel, la carte reste accessible", async ({ page }) => {
    await simulate(
      page,
      FRESH.map((i) => ({ ...i, state: "granted", action: null })),
    );
    await page.addInitScript(() => localStorage.setItem("mate-perm-later", "1"));
    await page.goto("/");
    await expect(page.locator("#autorisations")).toBeVisible();
    await expect(page.locator("#permdlg")).toBeHidden();
    // « Plus tard » d'avant effacé : un futur manque rouvrira l'écran.
    await expect.poll(() => page.evaluate(() => localStorage.getItem("mate-perm-later"))).toBeNull();
    await expect(page.locator("#access")).toBeHidden();
    await expect(page.locator("#accessline")).toBeHidden();
    await page.locator("#permopen").click();
    await expect(page.locator("#permlater")).toHaveText("Terminé");
    await page.locator("#permlater").click();
    await expect(page.locator("#permdlg")).toBeHidden();
  });

  test("hors Mac (liste vide) : aucune carte, aucun rappel", async ({ page }) => {
    await page.goto("/");
    await expect(page.locator("#name")).toHaveText("Ordi de test");
    await expect(page.locator("#autorisations")).toBeHidden();
    await expect(page.locator("#permdlg")).toBeHidden();
    await expect(page.locator("#access")).toBeHidden();
  });

  test("un seul indispensable manque : le rappel est au singulier et le nomme", async ({ page }) => {
    await simulate(
      page,
      FRESH.map((i) => ({
        ...i,
        state: i.id === "accessibility" ? "denied" : "granted",
        action: i.id === "accessibility" ? "request" : null,
      })),
    );
    await page.goto("/");
    await expect(page.locator("#accesstitle")).toHaveText("Une autorisation manque sur ce Mac");
    await expect(page.locator("#accesstext")).toContainText("Accessibilité.");
  });
});
