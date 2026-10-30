import { execFileSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

/**
 * Compile le pilote Windows (src/win/helper/) et le lanceur (src/win/launcher/), C# .NET Framework 4.8, en
 * src/win/MateHelper.exe et src/win/Mate.exe, depuis le Mac ou Windows. Demande le SDK .NET 8
 * (`dotnet` dans le PATH ou dans ~/.dotnet), figé par backend/global.json (lancé depuis backend/). Les exe sont gardés dans le dépôt : le compagnon et le paquet s'en
 * servent sans SDK.
 */

const backend = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");

function dotnet(): string {
  const home = path.join(os.homedir(), ".dotnet", process.platform === "win32" ? "dotnet.exe" : "dotnet");
  return fs.existsSync(home) ? home : "dotnet";
}

for (const [dir, exe] of [
  ["helper", "MateHelper.exe"],
  ["launcher", "Mate.exe"],
]) {
  const project = path.join(backend, "src", "win", dir);
  const out = path.join(project, "bin", "Release");
  execFileSync(dotnet(), ["build", project, "-c", "Release", "-o", out, "--nologo"], {
    cwd: backend,
    stdio: "inherit",
    env: { ...process.env, DOTNET_CLI_TELEMETRY_OPTOUT: "1", DOTNET_NOLOGO: "1" },
  });
  fs.copyFileSync(path.join(out, exe), path.join(backend, "src", "win", exe));
  console.log(`src/win/${exe} à jour`);
}
