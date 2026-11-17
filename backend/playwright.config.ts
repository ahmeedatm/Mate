import { defineConfig, devices } from "@playwright/test";

/**
 * Tests des pages web servies par le backend (page locale, visualiseur, retour OAuth) dans un vrai navigateur.
 * Chaque worker lance son serveur (e2e/fixtures.ts) : pas de webServer partagé, l'état reste isolé.
 */
export default defineConfig({
  testDir: "e2e",
  outputDir: "e2e-results/traces",
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: [["list"], ["junit", { outputFile: "e2e-results/junit.xml" }]],
  // Navigateur en français : la page locale suit sa langue tant que Français / English n'a pas été choisi (src/i18n.ts).
  use: { trace: "retain-on-failure", locale: "fr-FR" },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
});
