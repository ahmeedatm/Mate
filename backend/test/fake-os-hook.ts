import { registerHooks } from "node:module";

/**
 * Charge test/fake-os.ts à la place de src/os.ts : à importer avant tout module du backend, par
 * `--import ./test/fake-os-hook.ts` (serveur de test/server.ts) ou par un import en tête de fichier de test suivi
 * d'imports dynamiques (`await import("../src/deck.ts")`), les imports statiques étant résolus avant tout code.
 */
const real = new URL("../src/os.ts", import.meta.url).href;
const fake = new URL("./fake-os.ts", import.meta.url).href;

registerHooks({
  resolve(specifier, context, nextResolve) {
    const resolved = nextResolve(specifier, context);
    return resolved.url === real ? { ...resolved, url: fake } : resolved;
  },
});

// Arrêt du serveur de test (child.kill) : sortie normale, sans quoi V8 n'écrit pas la couverture (npm run coverage).
process.on("SIGTERM", () => process.exit(0));
