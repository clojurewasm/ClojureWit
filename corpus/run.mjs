// V8 corpus lane (doc/design/0023): instantiate one compiled entry module
// and print `result <value>` or `trap <message>`. A trap is an expected,
// classified outcome — exit 0 either way; the harness compares it against
// corpus/trap_table.edn. Anything else (bad file, missing export) throws
// and exits non-zero, which the harness treats as a broken lane.

import { readFileSync } from "node:fs";

const [, , path] = process.argv;
const instance = new WebAssembly.Instance(
  new WebAssembly.Module(readFileSync(path)),
  {},
);
try {
  console.log(`result ${instance.exports.entry()}`);
} catch (e) {
  // An uncaught Clojure throw is a WebAssembly.Exception on our tag;
  // its payload's class is read back through Wasm, since GC structs
  // are opaque to JS (0027). The tag must be THIS instance's — tag
  // identity is per-instance.
  if (e instanceof WebAssembly.Exception && e.is(instance.exports["clj-exn"])) {
    console.log(`exn ${instance.exports.exn_class(e.getArg(instance.exports["clj-exn"], 0))}`);
  }
  // V8 reports wasm stack exhaustion as RangeError, not RuntimeError
  // (0024) — both are classified outcomes, not harness failures.
  else if (e instanceof WebAssembly.RuntimeError || e instanceof RangeError)
    console.log(`trap ${e.message}`);
  else throw e;
}
