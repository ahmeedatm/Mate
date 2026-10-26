import { test as base, type Page } from "@playwright/test";
import { startServer, TOKEN, type TestServer } from "../test/server.ts";

/** Un serveur par worker (dossier de données vide), comme les tests d'intégration. */
export const test = base.extend<{}, { server: TestServer }>({
  server: [
    async ({}, use) => {
      const server = await startServer();
      await use(server);
      await server.stop();
    },
    { scope: "worker" },
  ],
  baseURL: async ({ server }, use) => use(server.url),
});
export { expect } from "@playwright/test";

/** Envoie une image au visualiseur comme la tablette (JPEG fabriqué par le navigateur). */
export async function pushFrame(server: TestServer, page: Page) {
  const dataUrl = await page.evaluate(() => {
    const canvas = document.createElement("canvas");
    canvas.width = 64;
    canvas.height = 48;
    const ctx = canvas.getContext("2d")!;
    ctx.fillStyle = "#2f6fed";
    ctx.fillRect(0, 0, 64, 48);
    return canvas.toDataURL("image/jpeg");
  });
  const res = await fetch(`${server.url}/camera/frame`, {
    method: "POST",
    headers: { authorization: `Bearer ${TOKEN}`, "content-type": "image/jpeg" },
    body: Buffer.from(dataUrl.split(",")[1], "base64"),
  });
  if (!res.ok) throw new Error(`/camera/frame : ${res.status}`);
}
