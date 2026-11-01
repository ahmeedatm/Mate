import { execFile, spawn } from "node:child_process";

const isWindows = process.platform === "win32";

/** Arrête la commande et tout ce qu'elle a lancé : son groupe de processus (Mac), son arbre (Windows). */
function killTree(pid: number) {
  if (isWindows) execFile("taskkill", ["/T", "/F", "/PID", String(pid)], { windowsHide: true }, () => {});
  else {
    try {
      process.kill(-pid, "SIGKILL");
    } catch {
      // Déjà fini : rien à arrêter.
    }
  }
}

/**
 * Lance une commande écrite par l'utilisateur dans le shell du système et rend sa sortie. Au délai ou au-delà de
 * `maxBytes`, la commande et les processus qu'elle a lancés sont arrêtés ; aucun détail d'erreur ne sort.
 */
export function runShell(command: string, opts: { timeoutMs: number; maxBytes?: number }): Promise<string> {
  const maxBytes = opts.maxBytes ?? 64 * 1024;
  return new Promise((resolve, reject) => {
    const [file, args] = isWindows ? ["cmd.exe", ["/d", "/s", "/c", `"${command}"`]] : ["/bin/sh", ["-c", command]];
    // detached : la commande devient chef de son propre groupe, que l'on peut arrêter d'un coup.
    const child = spawn(file, args, {
      detached: !isWindows,
      stdio: ["ignore", "pipe", "ignore"],
      windowsHide: true,
      windowsVerbatimArguments: isWindows,
    });
    const chunks: Buffer[] = [];
    let size = 0;
    let settled = false;
    const fail = () => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      if (child.pid) killTree(child.pid);
      reject(Object.assign(new Error("La commande a échoué"), { technical: true }));
    };
    const timer = setTimeout(fail, opts.timeoutMs);
    child.stdout.on("data", (d: Buffer) => {
      size += d.length;
      if (size > maxBytes) return fail();
      chunks.push(d);
    });
    child.on("error", fail);
    child.on("close", (code) => {
      if (settled) return;
      if (code !== 0) return fail();
      settled = true;
      clearTimeout(timer);
      resolve(Buffer.concat(chunks).toString("utf8"));
    });
  });
}
