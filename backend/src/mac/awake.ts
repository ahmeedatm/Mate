import { spawn, type ChildProcess } from "node:child_process";

let child: ChildProcess | undefined;

/**
 * Écran du Mac gardé allumé par `caffeinate -d`. `-w` : il s'arrête de lui-même si le compagnon meurt, l'écran ne
 * reste donc jamais éveillé sans nous.
 */
export async function keepAwake(on: boolean): Promise<void> {
  if (!on) {
    child?.kill();
    child = undefined;
    return;
  }
  if (child) return;
  const started = spawn("caffeinate", ["-d", "-w", String(process.pid)], { stdio: "ignore" });
  await new Promise<void>((resolve, reject) => {
    started.once("spawn", resolve);
    started.once("error", reject);
  });
  started.once("exit", () => {
    if (child === started) child = undefined;
  });
  child = started;
}
