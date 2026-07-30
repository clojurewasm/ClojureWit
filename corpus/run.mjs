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
  if (e instanceof WebAssembly.RuntimeError) console.log(`trap ${e.message}`);
  else throw e;
}
