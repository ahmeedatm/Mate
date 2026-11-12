// Signature des archives du backend : le compagnon ne pose qu'une archive signée par la CI du projet
// (backend/src/essai-backend.ts, checkSignature). Deux usages :
//
//   node scripts/backend-signe.mjs <dossier>             signe <dossier>/backend-<os>.tar.gz → backend-<os>.tar.gz.sig
//   node scripts/backend-signe.mjs --cle <fichier>       nouvelle paire de clés (une seule fois, ou pour en changer)
//
// Signature : Ed25519 de l'archive entière, en base64 (88 caractères), dans le fichier .sig à côté de l'archive ;
// updates-publish.mjs la recopie dans le manifest, la tablette l'envoie au compagnon (en-tête X-Mate-Signature).
// Clé privée : variable BACKEND_SIGNING_KEY (PEM), secret Actions de l'environnement « signature », jamais dans le dépôt.
// Après la signature, chaque archive est revérifiée avec la clé publique du dépôt (backend/config/backend-signature.pub) :
// un secret qui ne correspond pas arrête la CI au lieu de publier des archives que les compagnons refuseraient.
// --cle écrit la clé privée dans <fichier> (hors du dépôt, à mettre dans le secret puis à effacer) et la clé publique
// dans backend/config/backend-signature.pub (à commiter).
import fs from "node:fs";
import path from "node:path";
import { generateKeyPairSync, sign, verify } from "node:crypto";

const repo = path.resolve(import.meta.dirname, "..");
// BACKEND_SIGNING_PUB : autre clé publique (tests seulement).
const PUBLIC_KEY = process.env.BACKEND_SIGNING_PUB || path.join(repo, "backend", "config", "backend-signature.pub");
const PLATFORMS = ["mac", "windows"];

function stop(message) {
  console.error(message);
  process.exit(1);
}

const [first, second] = process.argv.slice(2);

if (first === "--cle") {
  if (!second) stop("Usage : node scripts/backend-signe.mjs --cle <fichier de la clé privée, hors du dépôt>");
  const target = path.resolve(second);
  if (target.startsWith(repo + path.sep)) stop("La clé privée ne va jamais dans le dépôt : choisis un fichier ailleurs.");
  const { publicKey, privateKey } = generateKeyPairSync("ed25519");
  fs.writeFileSync(target, privateKey.export({ type: "pkcs8", format: "pem" }), { flag: "wx", mode: 0o600 });
  fs.writeFileSync(PUBLIC_KEY, publicKey.export({ type: "spki", format: "pem" }));
  console.log(`Clé privée : ${target} (secret BACKEND_SIGNING_KEY de l'environnement « signature », puis efface ce fichier)`);
  console.log(`Clé publique : ${path.relative(repo, PUBLIC_KEY)} (à commiter)`);
  process.exit(0);
}

if (!first) stop("Usage : node scripts/backend-signe.mjs <dossier> | --cle <fichier>");
const archives = PLATFORMS.map((os) => path.join(first, `backend-${os}.tar.gz`)).filter((a) => fs.existsSync(a));
// Essai d'une PR qui ne touche pas au backend : rien à signer, la clé n'est pas demandée.
if (archives.length === 0) process.exit(0);
if (archives.length !== PLATFORMS.length) stop(`${first} : une archive backend par plateforme ou aucune`);
const key = process.env.BACKEND_SIGNING_KEY;
if (!key) stop("BACKEND_SIGNING_KEY : clé privée de signature attendue (secret de l'environnement « signature »)");
const publicKey = fs.readFileSync(PUBLIC_KEY, "utf8");
for (const archive of archives) {
  const data = fs.readFileSync(archive);
  const signature = sign(null, data, key);
  if (!verify(null, data, publicKey, signature))
    stop(`${archive} : la clé BACKEND_SIGNING_KEY ne correspond pas à ${path.relative(repo, PUBLIC_KEY)}`);
  fs.writeFileSync(`${archive}.sig`, signature.toString("base64") + "\n");
  console.log(`${path.basename(archive)} signée`);
}
