import assert from "node:assert/strict";
import { test } from "node:test";
import { openAppExe } from "../src/win/index.ts";

const windows = [
  { app: "Discord", exe: "C:\\Users\\a\\AppData\\Local\\Discord\\app-1.0\\Discord.exe" },
  { app: "VALORANT-Win64-Shipping", exe: "C:\\Riot Games\\VALORANT\\live\\ShooterGame\\Binaries\\Win64\\VALORANT-Win64-Shipping.exe" },
  { app: "Riot Client", exe: "C:\\Riot Games\\Riot Client\\RiotClientServices.exe" },
];

test("icône Windows : appli hors du menu Démarrer → exe de sa fenêtre ouverte", () => {
  assert.equal(openAppExe(windows, "Riot Client"), "C:\\Riot Games\\Riot Client\\RiotClientServices.exe");
  assert.equal(
    openAppExe(windows, "VALORANT-Win64-Shipping"),
    "C:\\Riot Games\\VALORANT\\live\\ShooterGame\\Binaries\\Win64\\VALORANT-Win64-Shipping.exe",
  );
});

test("icône Windows : aucune fenêtre de ce nom → pas d'exe", () => {
  assert.equal(openAppExe(windows, "Steam"), undefined);
  assert.equal(openAppExe([], "Riot Client"), undefined);
});

test("icône Windows : exe illisible (vide, relatif, pas un .exe) → pas d'exe", () => {
  for (const exe of ["", "RiotClientServices.exe", "\\\\serveur\\partage\\jeu.exe", "C:\\Riot Games\\riot.lnk"]) {
    assert.equal(openAppExe([{ app: "Riot Client", exe }], "Riot Client"), undefined, exe);
  }
});
