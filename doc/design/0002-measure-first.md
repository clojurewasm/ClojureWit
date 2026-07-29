# 0002 — Measure before choosing levers

**Status:** accepted · 2026-07-29

## The incident this comes from

While designing a size-reduction plan for the sibling ClojureWasm project, the
prediction was "dropping the reader/analyzer/compiler will shrink the binary
substantially". Symbol-level measurement showed it was worth **454 KB** — while
the single largest component, the embedded Wasm engine, was **1.92 MB** and had
nothing to do with that lever. The plan's whole priority order was wrong, and
the error was invisible until something was measured.

The failure was not the wrong guess. It was **acting on a guess that was cheap
to check.**

## The rule

For any decision about *where effort should go*:

1. Write the prediction down, in a committed file, **before** measuring.
2. Measure.
3. Leave both numbers visible. A wrong prediction that stays on the page is
   worth more than a right one that was never recorded.

This is not a rule about optimization in general. It is a rule about
**choosing between levers**, which is exactly where intuition about performance
is least reliable.

## S0 predictions — recorded before the first run

Against JVM Clojure as the reference. Filled in by the S0 run
(`bench/s0/`); do not edit the prediction column afterwards.

| # | Measurement | Prediction (2026-07-29) | Measured |
|---|---|---|---|
| B1 | protocol dispatch, monomorphic | **within 2× of JVM** — JVM's cached path is 1 compare + interface call; ours is 3 loads + `call_ref` | _pending_ |
| B2 | protocol dispatch, 10 receiver types at one site | **faster than JVM** — the JVM's per-call-site cache thrashes; a vtable slot has no cache to thrash | _pending_ |
| B3 | `i31` inline arithmetic vs JVM boxed `(+ a b)` | **faster than JVM** — JVM does a double dispatch through `Numbers.ops`; ours is two `ref.test`s and an add | _pending_ |
| B4 | `ref.cast` at hierarchy depth 2 vs 6 | **depth matters measurably** — enough to justify a flat type graph | _pending_ |
| — | V8 vs wasmtime on the same module | **V8 meaningfully faster on B1/B2** — it has speculative inlining; wasmtime has no adaptive tier | _pending_ |

If a prediction is wrong, the design note it came from gets amended and the
amendment says which prediction failed.
