import { run } from "./run.ts";

/**
 * Session du Mac verrouillée : lue dans `IOConsoleUsers` du registre IOKit (ioreg, 30 ms, sans autorisation). La session
 * de l'utilisateur du compagnon porte `CGSSessionScreenIsLocked`=Yes quand l'écran est verrouillé ; `kCGSSessionOnConsoleKey`
 * =No quand un autre utilisateur a pris l'écran. Null : aucune session graphique pour cet utilisateur (rien à protéger).
 */
export async function screenLocked(): Promise<boolean | null> {
  return parseConsoleUsers(await run("ioreg", ["-n", "Root", "-d1"], 5_000), process.getuid?.() ?? -1);
}

/** Sortie texte d'`ioreg -n Root -d1` : une entrée `{…}` par session, séparées par `},{`. */
export function parseConsoleUsers(ioreg: string, uid: number): boolean | null {
  const line = ioreg.split("\n").find((l) => l.includes('"IOConsoleUsers"'));
  if (!line) return null;
  const session = line.split("},{").find((s) => new RegExp(`"kCGSSessionUserIDKey"=${uid}(?!\\d)`).test(s));
  if (!session) return null;
  return session.includes('"CGSSessionScreenIsLocked"=Yes') || session.includes('"kCGSSessionOnConsoleKey"=No');
}
