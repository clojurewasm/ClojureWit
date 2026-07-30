// The dev-loop assembler differential (doc/design/0026): every emitted
// corpus module, assembled by binaryen.js — the dev lane's assembler —
// must agree with wasm-tools' binary (on disk, the oracle's reference
// lane) on every outcome, value and trap.
//
// Until the dev lane itself is in the gate, running this is part of
// "done" for any change to the emitted grammar (0026's interim rule).
//
//   clojure -X:test :nses '[cljwit.corpus-test]'   # regenerate target/corpus
//   npm install binaryen                            # once, anywhere on NODE_PATH
//   node corpus/devloop_differential.mjs
//
// Feature flags are explicit and must stay so: Features.All emits exact
// heap types stable V8 rejects, and no features at all silently degrades
// eqref to anyref — both are wrong-binary failures, not errors (0026).

import { readFileSync, readdirSync, existsSync } from "node:fs";
import { join } from "node:path";

const dir = "target/corpus";
if (!existsSync(dir)) {
  console.error("no target/corpus — run the corpus test first (it emits the modules)");
  process.exit(1);
}

let binaryen;
try {
  binaryen = (await import("binaryen")).default;
} catch {
  console.error("binaryen.js not resolvable — npm install binaryen (see header)");
  process.exit(1);
}

const F = binaryen.Features;
const FEATS = F.GC | F.ReferenceTypes | F.TailCall | F.ExceptionHandling |
              F.BulkMemory | F.Multivalue | F.SignExt | F.NontrappingFPToInt |
              F.MutableGlobals;

const run = (bin) => {
  try {
    const i = new WebAssembly.Instance(new WebAssembly.Module(bin), {});
    return "result " + i.exports.entry();
  } catch (e) {
    if (e instanceof WebAssembly.RuntimeError || e instanceof RangeError)
      return "trap " + e.message;
    return "ERROR " + e.message;
  }
};

let n = 0, agree = 0;
for (const f of readdirSync(dir).filter((f) => f.endsWith(".wat")).sort()) {
  const wat = readFileSync(join(dir, f), "utf8");
  const reference = new Uint8Array(readFileSync(join(dir, f.replace(/\.wat$/, ".wasm"))));
  const mod = binaryen.parseText(wat);
  mod.setFeatures(FEATS);
  const bin = mod.emitBinary();
  mod.dispose();
  const a = run(reference);
  const b = run(bin);
  n++;
  if (a === b) agree++;
  else console.log(`DISAGREE ${f}\n  wasm-tools: ${a}\n  binaryen:   ${b}`);
}

console.log(`${agree}/${n} modules agree (binaryen.js ${binaryen.version ?? "?"})`);
process.exit(agree === n ? 0 : 1);
