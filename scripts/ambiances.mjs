#!/usr/bin/env node
// Sons d'ambiance de Focus : app/src/main/res/raw/ambiance_*.webm, construits depuis des enregistrements CC0
// (Freesound, OpenGameArt, Wikimedia Commons ; cités dans assets/licences.txt). À relancer seulement pour changer un son :
// node scripts/ambiances.mjs [nom…] (demande ffmpeg : brew install ffmpeg).
//
// Chaque son : un extrait, mono 22 kHz, compressé en douceur (un coup de tonnerre ne fait pas sursauter), ramené à
// un volume bas (`loudness`, en deux passes ; les sons clairsemés plus bas encore) puis bouclé sans couture : les FADE dernières secondes passent en fondu
// sur les premières. Opus 32 kbit/s en WebM (lu par MediaCodec depuis Android 5), décodé une fois puis bouclé par
// NoisePlayer.
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

/** Volume visé par défaut (LUFS intégrés), curseur au maximum ; à 50 % (le défaut), 6 dB de moins. */
const LOUDNESS = -24;
const FADE = 4;
const OGA = "https://opengameart.org/sites/default/files/";
/** Aperçus Freesound (MP3 128 kbit/s), téléchargeables sans compte ; bien assez pour de l'Opus à 32. */
const FREESOUND = "https://cdn.freesound.org/previews/";
const SOUNDS = [
  // Pluie : quelques gouttes et un tonnerre lointain, « RAIN_Light Thunderstorm, Decrescendo, Water Dripping near
  // Building » de HushAndHarmonyNow (Freesound 851881) ; trois coups de tonnerre entre 50 et 200 s.
  {
    name: "storm",
    url: FREESOUND + "851/851881_18160342-hq.mp3",
    start: 50,
    length: 150,
    loudness: -30,
  },
  // Forêt : petits chants d'oiseaux, sans vent ni circulation, « birdsongEvening » de tenkism (Freesound 350456).
  {
    name: "forest",
    url: FREESOUND + "350/350456_2422703-hq.mp3",
    start: 0,
    length: 138,
    loudness: -32,
    // Chants adoucis : aigus perçants retirés, pics rapprochés du fond.
    filter:
      "lowpass=f=6000,acompressor=threshold=-36dB:ratio=6:attack=3:release=150:makeup=1",
  },
  // Japon : un supermarché de Tokyo, « Tokyo - Supermarket » de manuke (Freesound 315897).
  {
    name: "japan",
    url: FREESOUND + "315/315897_5356267-hq.mp3",
    start: 0,
    length: 97,
    loudness: -30,
  },
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
function cut(source, { start, length, filter }, extra, file) {
  ffmpeg([
    "-ss",
    String(start),
    "-t",
    String(length + extra),
    "-i",
    source,
    "-af",
    "aresample=22050,pan=mono|c0=0.5*c0+0.5*c1,acompressor=threshold=-28dB:ratio=3:attack=40:release=600" +
      (filter ? "," + filter : ""),
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

/** Volume ramené à `loudness` sans déformer (loudnorm en deux passes, linéaire), encodé en Opus. */
function normalize(file, target, loudness = LOUDNESS) {
  const filter = `loudnorm=I=${loudness}:TP=-4:LRA=11`;
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
// Noms en arguments (storm, forest…) : seulement ceux-là ; sans argument, tous.
const only = process.argv.slice(2);
for (const sound of SOUNDS.filter(
  (s) => only.length === 0 || only.includes(s.name),
)) {
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
  normalize(ready, target, sound.loudness);
  console.log(
    `${path.basename(target)} : ${Math.round(fs.statSync(target).size / 1024)} Ko`,
  );
}
fs.rmSync(tmp, { recursive: true, force: true });
