// The linked dev-mode session runner (doc/design/0028): instantiate the
// runtime once, then each form module in order against it, threading
// the var ledger. Prints the last form's outcome in the corpus
// vocabulary (result/exn/trap), exit 0 for classified outcomes.
//
//   node corpus/session.mjs rt.wasm form1.wasm form2.wasm ...

import { readFileSync } from "node:fs";

const [, , rtPath, ...formPaths] = process.argv;

const rt = new WebAssembly.Instance(
  new WebAssembly.Module(readFileSync(rtPath)),
  {},
);
const tag = rt.exports["clj-exn"];

// The ledger (0028 §3): starts empty, gains each form's exported var
// globals. A duplicate var export is a compiler bug made loud — the
// silent version is later forms reading a fresh null global as nil.
const vars = {};

const classify = (e) => {
  if (e instanceof WebAssembly.Exception && e.is(tag))
    return `exn ${rt.exports.exn_class(e.getArg(tag, 0))}`;
  if (e instanceof WebAssembly.RuntimeError || e instanceof RangeError)
    return `trap ${e.message}`;
  throw e;
};

let last;
for (const p of formPaths) {
  const inst = new WebAssembly.Instance(
    new WebAssembly.Module(readFileSync(p)),
    { rt: rt.exports, vars },
  );
  for (const [name, val] of Object.entries(inst.exports)) {
    if (name === "entry") continue;
    if (name in vars) throw new Error(`duplicate var export: ${name} (from ${p})`);
    vars[name] = val;
  }
  try {
    last = inst.exports.entry();
  } catch (e) {
    console.log(classify(e));
    process.exit(0);
  }
}
console.log(`result ${last}`);
