import { spawn, type ChildProcess } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { backendRoot, config } from "../config.ts";

/**
 * Icône du compagnon dans la barre des menus du Mac (src/mac/menubar/MenuBar.swift, compilé en src/mac/UnowhyMenu) :
 * un clic ouvre la page locale, le clic droit montre l'état de la tablette. Lancée avec le compagnon, elle lit
 * GET /local/status et s'arrête seule quand le compagnon disparaît. Sous Windows, c'est le lanceur qui porte l'icône.
 */
const PROGRAM = path.join(backendRoot, "src", "mac", "UnowhyMenu");
const RESTART_MS = 10_000;

/** Instance de test ou sans écran (COMPANION_HEADLESS, NO_ALERT) : aucune icône. */
export function startMenuBar(log: (msg: string) => void) {
  if (process.env.COMPANION_HEADLESS === "1" || process.env.NO_ALERT) return;
  if (!fs.existsSync(PROGRAM)) return log(`Icône de la barre des menus absente (${PROGRAM})`);
  let child: ChildProcess | undefined;
  let stopped = false;
  const launch = () => {
    child = spawn(PROGRAM, ["--port", String(config.port)], { stdio: "ignore" });
    child.once("error", (e) => log(`Icône de la barre des menus : ${e.message}`));
    // Fermée sans plantage (sortie 0) : pas relancée. Plantage : une nouvelle tentative.
    child.once("exit", (code) => {
      if (!stopped && code !== 0) setTimeout(launch, RESTART_MS).unref();
    });
  };
  launch();
  process.once("exit", () => {
    stopped = true;
    child?.kill();
  });
}
