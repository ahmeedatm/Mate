import { expect, test as base } from "@playwright/test";
import { FAKE_CLIENT_ID, startFakeGitlab, type FakeGitlab } from "../test/fake-gitlab.ts";
import { startServer, type TestServer } from "../test/server.ts";

/** Connexion GitLab par la page locale : un faux GitLab, un compagnon relié à lui, le navigateur fait le parcours. */
const test = base.extend<{}, { gitlab: FakeGitlab; server: TestServer }>({
  gitlab: [
    async ({}, use) => {
      const gitlab = await startFakeGitlab();
      await use(gitlab);
      await gitlab.stop();
    },
    { scope: "worker" },
  ],
  server: [
    async ({ gitlab }, use) => {
      const server = await startServer({ env: { GITLAB_URL: gitlab.url, GITLAB_CLIENT_ID: FAKE_CLIENT_ID } });
      await use(server);
      await server.stop();
    },
    { scope: "worker" },
  ],
  baseURL: async ({ server }, use) => use(server.url),
});

test.describe.configure({ mode: "serial" });

test("connecter, voir le compte, déconnecter", async ({ page }) => {
  await page.goto("/?section=gitlab");
  const card = page.locator("#gitlab");
  await expect(card).toBeVisible();
  await expect(card).toHaveClass(/focus/);
  await expect(card.locator("#gitlabstate")).toHaveText("Non connecté.");

  await card.getByRole("link", { name: "Se connecter avec GitLab" }).click();
  await expect(page.getByRole("heading", { name: "Autoriser UnoMate ?" })).toBeVisible();
  await page.getByRole("link", { name: "Autoriser" }).click();

  await expect(page).toHaveURL(/\/\?section=gitlab&gitlab=ok$/);
  await expect(page.locator("#gitlabmsg")).toHaveText(/^✓ GitLab connecté/);
  await expect(card.locator(".account")).toContainText("GitLab · camille");
  await expect(card.getByRole("link", { name: "Se connecter avec GitLab" })).toHaveCount(0);

  await card.getByRole("button", { name: "Déconnecter GitLab" }).click();
  await expect(card.locator(".account")).toHaveCount(0);
  await expect(card.getByRole("link", { name: "Se connecter avec GitLab" })).toBeVisible();

  // L'icône du connecteur ouvre la modale des étapes, puis le bouton ouvre la connexion.
  await page.getByRole("button", { name: "Fermer" }).click();
  const tile = page.locator(".cx", { hasText: "GitLab" });
  await expect(tile).toContainText("Non connecté");
  await tile.click();
  await expect(page.locator("#cxtitle")).toHaveText("Relier GitLab");
  await expect(card.locator("#cxlist li")).toHaveCount(4);
  await card.getByRole("link", { name: "Se connecter avec GitLab" }).click();
  await expect(page.getByRole("heading", { name: "Autoriser UnoMate ?" })).toBeVisible();
});

test("autorisation refusée sur GitLab : message d'annulation, pas de compte", async ({ page }) => {
  await page.goto("/oauth/gitlab/start");
  await page.getByRole("link", { name: "Refuser" }).click();
  await expect(page).toHaveURL(/\/\?section=gitlab&gitlab=refused$/);
  await expect(page.locator("#gitlabmsg")).toHaveText("Connexion annulée.");
  await expect(page.locator("#gitlab .account")).toHaveCount(0);
});

test("retour en erreur : message d'échec", async ({ page }) => {
  await page.goto("/oauth/gitlab/callback?code=abc&state=inconnu");
  await expect(page).toHaveURL(/\/\?section=gitlab&gitlab=error$/);
  await expect(page.locator("#gitlabmsg")).toHaveText("La connexion à GitLab a échoué. Réessaie.");
});

test("icône GitLab grisée quand rien n'est réglé", async ({ page }) => {
  const bare = await startServer({ env: { GITLAB_TOKEN: "" } });
  try {
    await page.goto(bare.url + "/");
    await expect(page.locator("#name")).toHaveText("Ordi de test");
    const tile = page.locator(".cx", { hasText: "GitLab" });
    await expect(tile).toContainText("Non connecté");
    await expect(tile).toHaveClass(/na/);
    await tile.click();
    await expect(page.locator("#gitlabstate")).toHaveText("Non connecté.");
  } finally {
    await bare.stop();
  }
});
