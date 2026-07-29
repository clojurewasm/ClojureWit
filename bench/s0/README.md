# S0 — does the dispatch design survive contact with an engine?

Four hand-written WAT modules. **No compiler is involved**, deliberately: the
question is about the design in `doc/design/0004-dispatch-design.md`, and a
compiler between the design and the number would only add doubt.

Predictions were recorded in `doc/design/0002-measure-first.md` **before** the
first run. Do not read them until you have your own expectation.

## The four

| | Module | Measures | Decides |
|---|---|---|---|
| **B1** | `b1_protocol.wat` | vtable-slot dispatch: 3 loads + `call_ref`, monomorphic site | Whether the design is viable at all |
| **B1L/B1i/B1c** | (same module) | controls: the same walk at 1 load, 0 loads, and no dispatch | Where in B1 the cost actually is |
| **B2** | `b2_megamorphic.wat` | the same site with 10 receiver types | Whether we beat the JVM where its per-call-site cache thrashes |
| **B3** | `b3_arith.wat` | `i31` fast-path add vs a boxed slow path | Whether boxed arithmetic can be cheap |
| **B4** | `b4_cast_depth.wat` | `ref.cast` at hierarchy depth 2 vs 6 | How flat the type graph has to be |

Each runs on **both** `node` (V8: speculative inlining) and `wasmtime`
(no adaptive tier). Both numbers are reported; neither is "the" answer.

## Baselines

B1–B3 are compared against JVM Clojure equivalents in `bench/s0/jvm/`, run on
the same machine in the same session. A cross-machine comparison is not one.

## Running

```sh
bb bench-s0                            # everything, at the sizes the numbers were taken at
bb bench-s0 B1                         # one benchmark
bb bench-s0 --n 2000000 --reps 5       # a quicker, noisier pass while editing
```

Needs `wasmtime`, `wasm-opt`, `node` and `wasm-tools` — `nix develop` has them
at the pinned versions. The driver prints the machine, the tool versions and the
command to reproduce, which is what gets pasted into
`doc/design/0002-measure-first.md` alongside the numbers.

Every lane's result is checked against the value the benchmark is supposed to
compute, and a mismatch stops the run. The driver also refuses an `n` whose
expected answer is the one an empty loop would return — for a ring walk that is
any multiple of the ring length, and without the check a benchmark doing no work
passes. Both guards were confirmed by breaking the benchmark on purpose.

**Controls are not optional here.** A benchmark and the variant you subtract
from it usually differ in more than one way, and the difference is then not an
attribution — see `.claude/rules/measurement.md`, and the B1 incident in
`doc/status.md` that put the rule there.

## Each module exports

`bench` plus whatever controls it needs, all with the signature
`(i32) -> i32` — take an iteration count, return a value that depends on every
iteration. No imports, so the same file runs unchanged under `node` and
`wasmtime run --invoke`. The JVM counterpart lives in `jvm/`, takes
`<variant> <n> <reps> <warmup>`, and prints EDN.

`wasmtime` has no clock in the guest and no way to loop across invocations, so
its lane is timed by **process slope**: wall time at n and at 2n, difference
over n. Startup, compilation and instantiation are identical in both and cancel.

## Before trusting any number here

Read `.claude/rules/measurement.md`. The failure modes that matter — the
optimizer deleting unconsumed work, process-spawn overhead swamping short runs,
warm-up effects — all produce plausible-looking wrong numbers rather than
errors.

## Status

**B1 done** — numbers and what they mean are in
`doc/design/0002-measure-first.md`. B2, B3 and B4 are not written; this README
is the contract they have to satisfy.
