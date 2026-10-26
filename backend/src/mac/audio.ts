import { osascript } from "./run.ts";

export type AudioState = {
  /** null quand la sortie ne se pilote pas (ex. certaines sorties HDMI / DisplayLink). */
  output: number | null;
  muted: boolean;
  input: number;
  micMuted: boolean;
};

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

export async function setAudio(change: {
  output?: number;
  outputDelta?: number;
  muted?: boolean;
  micMuted?: boolean;
}): Promise<AudioState> {
  const current = await getAudio();
  const clamp = (v: number) => Math.max(0, Math.min(100, Math.round(v)));

  let output: number | undefined;
  if (change.output !== undefined) output = clamp(change.output);
  else if (change.outputDelta !== undefined && current.output !== null) output = clamp(current.output + change.outputDelta);
  if (output !== undefined) await osascript("on run argv\nset volume output volume (item 1 of argv as integer)\nend run", [String(output)]);

  // Monter le volume réactive le son, comme les touches du clavier.
  const muted = change.muted ?? (output !== undefined && output > 0 && current.muted ? false : undefined);
  if (muted !== undefined) await osascript(`set volume ${muted ? "with" : "without"} output muted`);

  if (change.micMuted !== undefined && change.micMuted !== current.micMuted) {
    if (change.micMuted) micRestoreLevel = current.input || micRestoreLevel;
    const level = change.micMuted ? 0 : micRestoreLevel;
    await osascript("on run argv\nset volume input volume (item 1 of argv as integer)\nend run", [String(level)]);
  }
  return getAudio();
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
