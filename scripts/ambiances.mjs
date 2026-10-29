#!/usr/bin/env node
// Sons d'ambiance de Focus : app/src/main/res/raw/ambiance_*.webm, construits depuis des enregistrements CC0
// (OpenGameArt, Wikimedia Commons ; cités dans assets/licences.txt). À relancer seulement pour changer un son :
// node scripts/ambiances.mjs (demande ffmpeg : brew install ffmpeg).
//
// Chaque son : un extrait, mono 22 kHz, compressé en douceur (un coup de tonnerre ne fait pas sursauter), ramené au
// même volume bas (LOUDNESS, en deux passes) puis bouclé sans couture : les FADE dernières secondes passent en fondu
// sur les premières. Opus 32 kbit/s en WebM (lu par MediaCodec depuis Android 5), décodé une fois puis bouclé par
// NoisePlayer.
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

/** Volume visé (LUFS intégrés), curseur au maximum : bas ; à 50 % (le défaut), environ -30. */
const LOUDNESS = -24;
const FADE = 4;
const OGA = "https://opengameart.org/sites/default/files/";
const SOUNDS = [
  // Pluie et orage : « rain and thunders » de kindland.
  {
    name: "storm",
    url: OGA + "Dark_Rainy_Night%28ambience%29.ogg",
    start: 10,
    length: 150,
  },
  // Forêt : « Park ambiences » (oiseaux) de Thimras.
  {
    name: "forest",
    url: OGA + "park_ambience_birds.wav",
    start: 60,
    length: 120,
  },
  // Japon : « Asianoriental1 » de Tozan.
  { name: "japan", url: OGA + "asianoriental1_0.ogg", start: 0, length: 99 },
  // Mer : « Ocean Waves on a Tropical Beach » de Jarrod Stanley (Wikimedia Commons).
  {
    name: "sea",
    url: "https://upload.wikimedia.org/wikipedia/commons/6/64/Ocean_Waves_on_a_Tropical_Beach.ogg",
    start: 30,
    length: 120,
  },
  // Lofi : « Chill lofi inspired [loop edit] » de qubodup, d'après omfgdude ; déjà en boucle, pas de fondu.
  {
    name: "lofi",
    url: OGA + "chilllofir-loop.ogg",
    start: 0,
    length: 97.2,
    loop: false,
  },
];
const out = path.join(
  import.meta.dirname,
  "..",
  "app",
  "src",
  "main",
  "res",
  "raw",
);
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "ambiances-"));

/** Lance ffmpeg ; rend ce qu'il écrit sur stderr (les mesures de loudnorm y sont). */
function ffmpeg(args) {
  const run = spawnSync("ffmpeg", ["-hide_banner", "-nostats", "-y", ...args], {
    encoding: "utf8",
  });
  if (run.status !== 0) throw new Error(run.stderr.slice(-2000));
  return run.stderr;
}

async function download(url) {
  const res = await fetch(url, {
    headers: { "User-Agent": "UnowhyCompanion/1.0 (https://www.unowhy.com)" },
  });
  if (!res.ok) throw new Error(`${url} : HTTP ${res.status}`);
  const file = path.join(
    tmp,
    decodeURIComponent(path.basename(new URL(url).pathname)),
  );
  fs.writeFileSync(file, Buffer.from(await res.arrayBuffer()));
  return file;
}

/** Extrait mono 22 kHz compressé, en WAV ; `extra` secondes de plus pour le fondu de bouclage. */
function cut(source, { start, length }, extra, file) {
  ffmpeg([
    "-ss",
    String(start),
    "-t",
    String(length + extra),
    "-i",
    source,
    "-af",
    "aresample=22050,pan=mono|c0=0.5*c0+0.5*c1,acompressor=threshold=-28dB:ratio=3:attack=40:release=600",
    "-c:a",
    "pcm_s16le",
    file,
  ]);
}

/** Les `FADE` secondes de la fin en fondu sur le début : la fin de la boucle mène au début sans couture. */
function loop(file, length, looped) {
  ffmpeg([
    "-i",
    file,
    "-filter_complex",
    `[0]atrim=0:${FADE},asetpts=PTS-STARTPTS,afade=t=in:d=${FADE}[head];` +
      `[0]atrim=${length}:${length + FADE},asetpts=PTS-STARTPTS,afade=t=out:d=${FADE}[tail];` +
      `[head][tail]amix=inputs=2:normalize=0[joint];` +
      `[0]atrim=${FADE}:${length},asetpts=PTS-STARTPTS[body];` +
      `[joint][body]concat=n=2:v=0:a=1`,
    "-c:a",
    "pcm_s16le",
    looped,
  ]);
}

/** Volume ramené à LOUDNESS sans déformer (loudnorm en deux passes, linéaire), encodé en Opus. */
function normalize(file, target) {
  const filter = `loudnorm=I=${LOUDNESS}:TP=-4:LRA=11`;
  const report = ffmpeg([
    "-i",
    file,
    "-af",
    `${filter}:print_format=json`,
    "-f",
    "null",
    "-",
  ]);
  const json = report.slice(report.lastIndexOf("{"));
  const m = JSON.parse(json.slice(0, json.indexOf("}") + 1));
  ffmpeg([
    "-i",
    file,
    "-af",
    `${filter}:linear=true:measured_I=${m.input_i}:measured_TP=${m.input_tp}:measured_LRA=${m.input_lra}:measured_thresh=${m.input_thresh}:offset=${m.target_offset},aresample=22050`,
    "-c:a",
    "libopus",
    "-b:a",
    "32k",
    "-ac",
    "1",
    "-map_metadata",
    "-1",
    target,
  ]);
}

fs.mkdirSync(out, { recursive: true });
for (const sound of SOUNDS) {
  const source = await download(sound.url);
  const looping = sound.loop !== false;
  const extract = path.join(tmp, `${sound.name}-cut.wav`);
  cut(source, sound, looping ? FADE : 0, extract);
  let ready = extract;
  if (looping) {
    ready = path.join(tmp, `${sound.name}-loop.wav`);
    loop(extract, sound.length, ready);
  }
  const target = path.join(out, `ambiance_${sound.name}.webm`);
  normalize(ready, target);
  console.log(
    `${path.basename(target)} : ${Math.round(fs.statSync(target).size / 1024)} Ko`,
  );
}
fs.rmSync(tmp, { recursive: true, force: true });
