import assert from "node:assert/strict";
import { it } from "node:test";
import { osascript } from "../src/mac/run.ts";

// Un argument qui ressemble à une option (texte tapé depuis la tablette) reste un argument, jamais un 2e script `-e`.
it("osascript : un argument en -e… arrive tel quel dans argv", { skip: process.platform !== "darwin" && "macOS seulement" }, async () => {
  for (const text of ["-e1+1", "-ex", "-l", "--"]) {
    assert.equal(await osascript("function run(argv) { return argv[0]; }", [text], "JavaScript"), text);
  }
});
