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
bb bench-s0
```

Output goes to stdout and is pasted into `doc/design/0002-measure-first.md`'s
measured column, along with the machine and tool versions from `bb check-tools`.

## Before trusting any number here

Read `.claude/rules/measurement.md`. The failure modes that matter — the
optimizer deleting unconsumed work, process-spawn overhead swamping short runs,
warm-up effects — all produce plausible-looking wrong numbers rather than
errors.

## Status

Not yet written. This README is the contract they have to satisfy.
