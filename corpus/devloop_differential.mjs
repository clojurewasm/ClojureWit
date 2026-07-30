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

const assemble = (wat) => {
  const mod = binaryen.parseText(wat);
  mod.setFeatures(FEATS);
  const bin = mod.emitBinary();
  mod.dispose();
  return bin;
};

let n = 0, agree = 0;
for (const f of readdirSync(dir).filter((f) => f.endsWith(".wat")).sort()) {
  const wat = readFileSync(join(dir, f), "utf8");
  const reference = new Uint8Array(readFileSync(join(dir, f.replace(/\.wat$/, ".wasm"))));
  const a = run(reference);
  const b = run(assemble(wat));
  n++;
  if (a === b) agree++;
  else console.log(`DISAGREE ${f}\n  wasm-tools: ${a}\n  binaryen:   ${b}`);
}

// The linked sessions (0028): each target/corpus/linked/<id>/ is one
// session — rt + formN in order — run once from wasm-tools' binaries
// and once re-assembled by binaryen, same vocabulary as session.mjs.
const runSession = (bins) => {
  try {
    const rt = new WebAssembly.Instance(new WebAssembly.Module(bins[0]), {});
    const tag = rt.exports["clj-exn"];
    const vars = {};
    let last;
    for (const bin of bins.slice(1)) {
      const inst = new WebAssembly.Instance(new WebAssembly.Module(bin), { rt: rt.exports, vars });
      for (const [name, val] of Object.entries(inst.exports)) {
        if (name === "entry") continue;
        if (name in vars) return `ERROR duplicate var export ${name}`;
        vars[name] = val;
      }
      try {
        last = inst.exports.entry();
      } catch (e) {
        if (e instanceof WebAssembly.Exception && e.is(tag))
          return `exn ${rt.exports.exn_class(e.getArg(tag, 0))}`;
        if (e instanceof WebAssembly.RuntimeError || e instanceof RangeError)
          return `trap ${e.message}`;
        throw e;
      }
    }
    return `result ${last}`;
  } catch (e) {
    return `ERROR ${e.message}`;
  }
};

const linkedRoot = join(dir, "linked");
if (existsSync(linkedRoot)) {
  for (const session of readdirSync(linkedRoot).sort()) {
    const sdir = join(linkedRoot, session);
    const wats = readdirSync(sdir).filter((f) => f.endsWith(".wat"))
      .sort((x, y) => (x === "rt.wat" ? -1 : y === "rt.wat" ? 1 : x.localeCompare(y, "en", { numeric: true })));
    const a = runSession(wats.map((f) => new Uint8Array(readFileSync(join(sdir, f.replace(/\.wat$/, ".wasm"))))));
    const b = runSession(wats.map((f) => assemble(readFileSync(join(sdir, f), "utf8"))));
    n++;
    if (a === b) agree++;
    else console.log(`DISAGREE linked/${session}\n  wasm-tools: ${a}\n  binaryen:   ${b}`);
  }
}

console.log(`${agree}/${n} modules+sessions agree (binaryen.js)`);
process.exit(agree === n ? 0 : 1);
