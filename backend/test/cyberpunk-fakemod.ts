import fs from "node:fs";
import os from "node:os";
import path from "node:path";

/** Faux dossier Steam avec Cyber Engine Tweaks : `steam` à donner en MATE_STEAM_PATH, `modDir` = dossier du mod Mate. */
export function fakeSteam() {
  const steam = fs.mkdtempSync(path.join(os.tmpdir(), "mate-cp-"));
  const plugins = path.join(steam, "steamapps", "common", "Cyberpunk 2077", "bin", "x64", "plugins");
  fs.mkdirSync(path.join(plugins, "cyber_engine_tweaks", "mods"), { recursive: true });
  fs.writeFileSync(path.join(plugins, "cyber_engine_tweaks.asi"), "");
  fs.writeFileSync(path.join(steam, "steamapps", "appmanifest_1091500.acf"), '"AppState"\n{\n\t"installdir"\t\t"Cyberpunk 2077"\n}\n');
  return { steam, modDir: path.join(plugins, "cyber_engine_tweaks", "mods", "mate") };
}

/**
 * Faux mod : relit `commands.json` toutes les 40 ms, comme logic.lua (ignore ce qui est déjà là à son premier passage,
 * n'exécute que les `id` plus grands que le dernier), tient la liste des repères et réécrit `state.json` avec les `acks`.
 */
export function fakeMod(modDir: string, opts: { silent?: boolean; session?: number } = {}) {
  let last: number | null = null;
  let seq = 0;
  let session = opts.session ?? 3;
  let pins: { pid: number; x: number; y: number; z: number }[] = [];
  let acks: { id: number; ok: boolean; res: string }[] = [];
  let inGame = true;
  const executed: any[] = [];
  const write = () => {
    const state = { v: 1, seq: ++seq, at: Math.floor(Date.now() / 1000), inGame, session, mod: "1.0.0", pins, acks, errors: {} };
    fs.mkdirSync(modDir, { recursive: true });
    fs.writeFileSync(path.join(modDir, "state.json.tmp"), JSON.stringify(state));
    fs.renameSync(path.join(modDir, "state.json.tmp"), path.join(modDir, "state.json"));
  };
  const tick = () => {
    let cmds: any[] = [];
    try {
      cmds = JSON.parse(fs.readFileSync(path.join(modDir, "commands.json"), "utf8")).cmds;
    } catch {
      // pas encore de fichier
    }
    if (last === null) last = Math.max(0, ...cmds.map((c) => c.id));
    else
      for (const c of [...cmds].sort((a, b) => a.id - b.id)) {
        if (c.id <= (last ?? 0)) continue;
        last = c.id;
        executed.push(c);
        if (opts.silent) continue;
        if (c.t === "pin" && !pins.some((p) => p.pid === c.pid)) pins.push({ pid: c.pid, x: c.x, y: c.y, z: c.z });
        if (c.t === "unpin") pins = pins.filter((p) => p.pid !== c.pid);
        acks.push({ id: c.id, ok: true, res: c.t });
        acks = acks.slice(-20);
      }
    write();
  };
  tick();
  const timer = setInterval(tick, 40);
  return {
    executed,
    pins: () => pins,
    stop: () => clearInterval(timer),
    /** Nouvelle partie chargée : session +1, plus aucun repère. */
    newSession: () => {
      session++;
      pins = [];
    },
    /** Mod rechargé : compteur reparti de zéro, plus aucun repère, premier passage à refaire. */
    reload: () => {
      last = null;
      seq = 0;
      pins = [];
    },
    setInGame: (v: boolean) => (inGame = v),
  };
}

export const until = async (cond: () => boolean | Promise<boolean>, ms = 4000) => {
  for (const end = Date.now() + ms; Date.now() < end;) {
    if (await cond()) return;
    await new Promise((r) => setTimeout(r, 30));
  }
  throw new Error("condition jamais remplie");
};
