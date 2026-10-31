import { execFile } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { AnswerError } from "./agents.ts";
import { download } from "./app-update.ts";
import { isMac } from "./os.ts";
import { dataDir } from "./paths.ts";

/**
 * Dictée d'un prompt pour une session Claude, transcrite sur l'ordinateur par whisper.cpp (`whisper-cli`, installé à
 * part : `brew install whisper-cpp`) : rien ne part sur Internet, la tablette n'envoie l'audio qu'à son ordinateur.
 * Modèle large-v3-turbo quantifié (574 Mo), téléchargé une fois depuis le dépôt de whisper.cpp à la première dictée,
 * taille et SHA-256 vérifiés, dans `whisper/` du dossier de données. `WHISPER_BIN` et `WHISPER_MODEL` (un modèle déjà
 * là, jamais téléchargé) remplacent l'un ou l'autre. Une transcription à la fois.
 */

const MODEL = {
  file: "ggml-large-v3-turbo-q5_0.bin",
  url: "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q5_0.bin",
  size: 574_041_195,
  /** Empreinte publiée par Hugging Face (LFS), relevée le 30/10/2026. */
  sha256: "394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2",
};

/** 60 s de 16 kHz mono 16 bits, plus l'en-tête : la tablette s'arrête avant. */
export const MAX_WAV_BYTES = 44 + 60 * 16_000 * 2;

/** `whisper-cli` : WHISPER_BIN, sinon dans le PATH ou Homebrew (le lancement automatique a un PATH réduit). */
export function whisperBin(): string | null {
  if (process.env.WHISPER_BIN) return executable(process.env.WHISPER_BIN) ? process.env.WHISPER_BIN : null;
  const dirs = [...(process.env.PATH ?? "").split(path.delimiter), "/opt/homebrew/bin", "/usr/local/bin"];
  for (const dir of dirs) {
    const bin = dir && path.join(dir, "whisper-cli");
    if (bin && executable(bin)) return bin;
  }
  return null;
}

function executable(file: string) {
  try {
    fs.accessSync(file, fs.constants.X_OK);
    return fs.statSync(file).isFile();
  } catch {
    return false;
  }
}

/** Micro des sessions proposé : Mac (seul système des agents) avec whisper-cli. Le modèle peut encore manquer. */
export const voiceAvailable = () => isMac && whisperBin() !== null;

const modelFile = () => process.env.WHISPER_MODEL || path.join(dataDir, "whisper", MODEL.file);

let downloading: Promise<void> | null = null;

/** Lance le téléchargement du modèle s'il n'est pas en cours (fichier `.part` renommé seulement une fois vérifié). */
function fetchModel(file: string) {
  if (downloading) return;
  const part = `${file}.part`;
  fs.mkdirSync(path.dirname(file), { recursive: true });
  downloading = download(MODEL.url, part, MODEL)
    .then(() => fs.renameSync(part, file))
    .catch((e) => console.warn(`modèle Whisper non téléchargé : ${(e as Error).message}`))
    .finally(() => (downloading = null));
}

/** Modèle prêt, ou téléchargement lancé et une phrase qui dit où il en est. */
function readyModel(): string {
  const file = modelFile();
  if (fs.existsSync(file)) return file;
  if (process.env.WHISPER_MODEL) throw new AnswerError("Modèle de voix introuvable (WHISPER_MODEL).", 409);
  fetchModel(file);
  let done = 0;
  try {
    done = fs.statSync(`${file}.part`).size;
  } catch {
    // pas encore commencé
  }
  const percent = Math.floor((done / MODEL.size) * 100);
  throw new AnswerError(`Le modèle de voix se télécharge sur l'ordinateur (${percent} %) : réessaie dans un moment.`, 409);
}

/** WAV PCM d'un seul tenant (`RIFF`…`WAVE`), comme l'écrit la tablette. */
export const isWav = (b: Buffer) => b.length > 44 && b.toString("ascii", 0, 4) === "RIFF" && b.toString("ascii", 8, 12) === "WAVE";

/** Sortie de whisper-cli : lignes recollées, marques de non-parole (`[BLANK_AUDIO]`, `(musique)`) retirées. */
export function cleanTranscript(out: string): string {
  return out
    .replace(/\[[^\]]*\]|\([^)]*\)/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}

let queue: Promise<unknown> = Promise.resolve();

/** Texte dit dans `wav` (français), vide si rien n'a été compris. */
export function transcribe(wav: Buffer): Promise<string> {
  const bin = whisperBin();
  if (!isMac || !bin) throw new AnswerError("Whisper n'est pas installé sur l'ordinateur (brew install whisper-cpp).", 409);
  if (!isWav(wav) || wav.length > MAX_WAV_BYTES) throw new AnswerError("Enregistrement illisible.", 400);
  const model = readyModel();
  const run = queue.then(() => runWhisper(bin, model, wav));
  queue = run.catch(() => undefined);
  return run;
}

async function runWhisper(bin: string, model: string, wav: Buffer): Promise<string> {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "mate-voix-"));
  const file = path.join(dir, "prompt.wav");
  try {
    fs.writeFileSync(file, wav, { mode: 0o600 });
    const out = await new Promise<string>((resolve, reject) =>
      execFile(bin, ["-m", model, "-f", file, "-l", "fr", "-nt", "-np"], { timeout: 120_000, maxBuffer: 1024 * 1024 }, (err, stdout) =>
        err ? reject(err) : resolve(stdout),
      ),
    );
    return cleanTranscript(out);
  } catch (e) {
    console.warn(`whisper-cli : ${(e as Error).message}`);
    throw new AnswerError("La transcription a échoué sur l'ordinateur.", 500);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
}
