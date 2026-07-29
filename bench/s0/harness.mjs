// V8 lane. Instantiates a benchmark module and times its exported `bench`
// in-process, so no process-spawn cost is folded into the number.
//
//   node harness.mjs <module.wasm> <export> <n> <reps> <warmup>
//
// Prints one line of JSON: {"samples":[<ns per run>],"checksum":k,"result":r}.
// `checksum` xors every run so nothing the engine could delete goes unconsumed;
// `result` is the last run's value, which is what the driver checks — an xor
// over an even number of identical runs is 0 whatever they computed.

import { readFileSync } from "node:fs";

const [, , path, exportName, nArg, repsArg, warmupArg] = process.argv;

// Missing or junk counts must fail here rather than produce an empty sample set
// and a result of 0 — which for a ring walk is a value the driver might well be
// expecting, so it would read as a pass.
const count = (name, s, min) => {
  const v = Number(s);
  if (!Number.isInteger(v) || v < min) {
    throw new Error(`${name}: expected an integer >= ${min}, got ${JSON.stringify(s)}`);
  }
  return v;
};

const n = count("n", nArg, 1);
const reps = count("reps", repsArg, 1);
const warmup = count("warmup", warmupArg, 0);

const instance = new WebAssembly.Instance(
  new WebAssembly.Module(readFileSync(path)),
  {},
);
const bench = instance.exports[exportName];
if (typeof bench !== "function") {
  throw new Error(`${path} does not export a function named ${exportName}`);
}

let checksum = 0;
for (let i = 0; i < warmup; i++) checksum ^= bench(n);

const samples = [];
let result = 0;
for (let i = 0; i < reps; i++) {
  const t0 = process.hrtime.bigint();
  const v = bench(n);
  const t1 = process.hrtime.bigint();
  checksum ^= v;
  result = v;
  samples.push(Number(t1 - t0));
}

console.log(JSON.stringify({ samples, checksum, result }));
