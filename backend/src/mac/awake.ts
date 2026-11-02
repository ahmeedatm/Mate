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
  // Posé avant d'attendre le lancement : un deuxième « on » ne double pas le processus, un « off » le tue.
  child = started;
  started.once("exit", () => {
    if (child === started) child = undefined;
  });
  await new Promise<void>((resolve, reject) => {
    started.once("spawn", resolve);
    started.once("error", (e) => {
      if (child === started) child = undefined;
      reject(e);
    });
  });
}
