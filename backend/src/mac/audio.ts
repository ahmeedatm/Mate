import fs from "node:fs";
import { planAudio } from "../driver-common.ts";
import type { AudioChange, AudioOutput, AudioState, MixerChange, MixerState } from "../screens.ts";
import { PROGRAM } from "./menubar.ts";
import { osascript, run } from "./run.ts";

// Volume micro mémorisé pour pouvoir le rétablir après un "couper le micro".
let micRestoreLevel = 75;

export async function getAudio(): Promise<AudioState> {
  const raw = await osascript(
    'set s to get volume settings\nreturn (output volume of s as text) & "|" & (input volume of s as text) & "|" & (output muted of s as text)',
  );
  const [out, input, muted] = raw.split("|");
  const output = Number.parseInt(out, 10);
  const inputLevel = Number.parseInt(input, 10) || 0;
  return {
    output: Number.isNaN(output) ? null : output,
    muted: muted === "true",
    input: inputLevel,
    micMuted: inputLevel === 0,
  };
}

export async function setAudio(change: AudioChange): Promise<AudioState> {
  const current = await getAudio();
  const { output, muted } = planAudio(change, current);
  if (output !== undefined) await osascript("on run argv\nset volume output volume (item 1 of argv as integer)\nend run", [String(output)]);
  if (muted !== undefined) await osascript(`set volume ${muted ? "with" : "without"} output muted`);

  if (change.micMuted !== undefined && change.micMuted !== current.micMuted) {
    if (change.micMuted) micRestoreLevel = current.input || micRestoreLevel;
    const level = change.micMuted ? 0 : micRestoreLevel;
    await osascript("on run argv\nset volume input volume (item 1 of argv as integer)\nend run", [String(level)]);
  }
  return getAudio();
}

/** Mélangeur : sortie et micro seulement (macOS n'a ni volume par appli, ni niveau sonore lisible sans pilote). */
export async function mixer(change?: MixerChange): Promise<MixerState> {
  if (change?.id === "master") await setAudio({ output: change.volume, muted: change.muted });
  if (change?.id === "mic") {
    if (change.volume !== undefined) {
      await osascript("on run argv\nset volume input volume (item 1 of argv as integer)\nend run", [String(change.volume)]);
      if (change.volume > 0) micRestoreLevel = change.volume;
    }
    if (change.muted !== undefined) await setAudio({ micMuted: change.muted });
  }
  const audio = await getAudio();
  return {
    master: { id: "master", name: "master", volume: audio.output, muted: audio.muted, level: null },
    mic: { id: "mic", name: "mic", volume: audio.micMuted ? micRestoreLevel : audio.input, muted: audio.micMuted, level: null },
    apps: null,
  };
}

/** Un micro du Mac est-il utilisé par une appli (en appel) ? macOS ne dit pas laquelle. */
export async function micInUse(): Promise<boolean> {
  const raw = await osascript(
    `ObjC.import("AVFoundation");
const ds = $.NSClassFromString("AVCaptureDevice").devicesWithMediaType("soun");
let used = false;
for (let i = 0; i < ds.count; i++) used = used || ds.objectAtIndex(i).inUseByAnotherApplication;
String(used)`,
    [],
    "JavaScript",
  );
  return raw.trim() === "true";
}

/** Lecture de la liste écrite par `MateMenu --audio-outputs` : une entrée sans id ou sans nom est écartée. */
export function parseAudioOutputs(text: string): AudioOutput[] {
  const list: unknown = JSON.parse(text);
  if (!Array.isArray(list)) throw new Error("Liste des sorties audio illisible");
  return list.flatMap((o) =>
    typeof o?.id === "string" && o.id && typeof o?.name === "string" ? [{ id: o.id, name: o.name, current: o.current === true }] : [],
  );
}

/** Core Audio n'est pas joignable depuis AppleScript : c'est le binaire de la barre des menus qui lit et change la sortie. */
async function menuTool(args: string[]): Promise<AudioOutput[]> {
  if (!fs.existsSync(PROGRAM)) throw Object.assign(new Error("Outil audio du Mac absent (MateMenu)"), { statusCode: 501 });
  return parseAudioOutputs(await run(PROGRAM, args, 5_000));
}

/** Sorties audio du Mac (haut-parleurs, casque, écran, carte son) ; `current` : la sortie par défaut. */
export const audioOutputs = () => menuTool(["--audio-outputs"]);

/** Fait de la sortie `id` (UID Core Audio) la sortie par défaut ; rend la liste à jour. */
export const setAudioOutput = (id: string) => menuTool(["--audio-set", id]);
