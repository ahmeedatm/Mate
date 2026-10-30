/**
 * Backend de démo pour les captures des vidéos (video/captures/) : le vrai serveur (backend/test/server.ts) avec le faux
 * pilotage de l'ordinateur (backend/test/fake-os.ts), jamais de donnée réelle. Les données du persona (touches, pages,
 * tâches, agenda, GitLab, Jira, agents, fenêtres, son) viennent de la démo de l'appli : la tablette choisit le persona
 * dans Réglages > Démo, ce qui appelle `POST /demo/persona` (backend/src/demo.ts, demo-mock.ts). Rien n'est écrit ici.
 * Lancer depuis backend/ : `npx tsx ../video/demo-backend.mts`.
 * DEMO_PORT (3940) : le backend, jeton `jeton-de-test` ; DEMO_DIR (/tmp/vdp) : données et dossier partagé.
 * DEMO_OS : `windows` (défaut) ou `mac`. DEMO_NAME : nom de l'ordinateur.
 * DEMO_NOW (heure locale, `2026-10-29T14:29:00`) : instant simulé pour l'âge des fichiers du dossier partagé (défaut : maintenant).
 */
import fs from "node:fs";
import path from "node:path";
import { startServer, TOKEN } from "../backend/test/server.ts";

const PORT = Number(process.env.DEMO_PORT || 3940);
const dir = process.env.DEMO_DIR || "/tmp/vdp";
const now = process.env.DEMO_NOW ? new Date(process.env.DEMO_NOW) : new Date();
const demoOs = process.env.DEMO_OS === "mac" ? "mac" : "windows";
const dataDir = path.join(dir, "data");
const docsDir = path.join(dir, "docs");
fs.mkdirSync(dataDir, { recursive: true });

// Applis « installées » : la démo ne garde dans la page Apps que celles que l'ordinateur a (backend/src/demo.ts).
const INSTALLED = [
  "Microsoft Excel",
  "Microsoft Word",
  "Microsoft Outlook",
  "Microsoft Teams",
  "Microsoft PowerPoint",
  "Google Chrome",
  "Canva",
  "Slack",
  "Notion",
  "Terminal",
  "Visual Studio Code",
  "Docker",
];

// Dossier partagé (Fichiers partagés), extensions neutres
const day = 86_400_000;
const put = (rel: string, daysAgo: number, size = 2048) => {
  const full = path.join(docsDir, rel);
  fs.mkdirSync(path.dirname(full), { recursive: true });
  fs.writeFileSync(full, Buffer.alloc(size, "x"));
  const t = new Date(now.getTime() - daysAgo * day);
  fs.utimesSync(full, t, t);
};
for (const [rel, ago, size] of [
  ["Projet Nova/Brief client.pdf", 0.2, 412_000],
  ["Projet Nova/Planning.csv", 0.5, 88_000],
  ["Projet Nova/Maquettes v3.pdf", 1, 2_300_000],
  ["Projet Nova/Compte rendu atelier.txt", 2, 36_000],
  ["Projet Alpes/Devis signé.pdf", 3, 210_000],
  ["Projet Alpes/Budget.csv", 4, 64_000],
  ["Contrat agence Orion.pdf", 1.2, 54_000],
  ["Suivi des heures octobre.csv", 0.1, 41_000],
  ["Présentation découverte.pdf", 0.8, 3_900_000],
  ["Logo Nova.png", 6, 120_000],
] as [string, number, number][])
  put(rel, ago, size);
for (const d of ["Projet Nova", "Projet Alpes", "Archives 2025", "Modèles"]) fs.mkdirSync(path.join(docsDir, d), { recursive: true });

const server = await startServer({
  os: demoOs,
  port: PORT,
  dataDir,
  env: {
    DOCS_DIR: docsDir,
    COMPUTER_NAME: process.env.DEMO_NAME || (demoOs === "mac" ? "Mac du bureau" : "PC du bureau"),
  },
});
server.setOs({ installed: INSTALLED });

console.log(`Backend de démo (${demoOs}) sur ${server.url}, depuis l'émulateur : 10.0.2.2:${PORT} ; jeton : ${TOKEN}`);

const quit = async () => {
  await server.stop();
  process.exit(0);
};
process.on("SIGTERM", quit);
process.on("SIGINT", quit);
setInterval(() => {}, 1 << 30);
