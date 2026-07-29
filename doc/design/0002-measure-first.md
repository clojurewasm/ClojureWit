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
| B1 | protocol dispatch, monomorphic | **within 2× of JVM** — JVM's cached path is 1 compare + interface call; ours is 3 loads + `call_ref` | **half right.** V8 **0.58×** (faster than JVM); wasmtime **5.62×**. The prediction holds on V8 and fails on wasmtime. |
| B2 | protocol dispatch, 10 receiver types at one site | **faster than JVM** — the JVM's per-call-site cache thrashes; a vtable slot has no cache to thrash | _pending_ |
| B3 | `i31` inline arithmetic vs JVM boxed `(+ a b)` | **faster than JVM** — JVM does a double dispatch through `Numbers.ops`; ours is two `ref.test`s and an add | _pending_ |
| B4 | `ref.cast` at hierarchy depth 2 vs 6 | **depth matters measurably** — enough to justify a flat type graph | _pending_ |
| — | V8 vs wasmtime on the same module | **V8 meaningfully faster on B1/B2** — it has speculative inlining; wasmtime has no adaptive tier | **confirmed on B1, by more than expected: 9.7×** (0.879 vs 8.564 ns/op). |

If a prediction is wrong, the design note it came from gets amended and the
amendment says which prediction failed.

## B1 — measured 2026-07-29

```
bb bench-s0
Darwin arm64 · Apple M4 Pro
wasmtime 47.0.1 · wasm-opt 129 · wasm-tools 1.254.0 · node v24.18.0 · openjdk 25.0.3
n=20,000,000  reps=20  warmup=5 · medians
```

| lane | B1 dispatch | B1i `call_ref`, no loads | B1c direct call | dispatch = B1 − B1c |
|---|---|---|---|---|
| JVM Clojure | **1.524** | — | 1.442 | 0.08 |
| V8 (node) | **0.879** | 0.786 | 0.748 | 0.13 |
| V8, `wasm-opt -O3` | 0.884 | 0.787 | 0.756 | 0.13 |
| wasmtime | **8.564** | 2.525 | 2.385 | **6.18** |
| wasmtime, `wasm-opt -O3` | 8.750 | 2.526 | 1.085 | 7.67 |

ns per dispatch. B1c is the same ring walk with dispatch removed, so a lane's
dispatch cost is its B1 column minus its B1c column. Two full runs an hour apart
agreed to within 1% on every cell.

**What it says.**

1. **The design is viable on both engines by the S0 stop condition.** The
   roadmap stops the project at ~10× JVM; the worst lane is 5.6×.
2. **On V8 the dispatch is free** — 0.13 ns over the direct-call control, and
   V8 still beats JVM Clojure outright. Speculative inlining reaches us.
3. **On wasmtime the dispatch costs 6.2 ns, and it is not the indirect call.**
   B1i isolates `call_ref` reached through a global: 0.14 ns over a direct
   call. The remaining **6.0 ns is the three dependent loads** — receiver to
   `$vtables`, `$vtables` to the arity array, array to `funcref`. Each is a
   pointer chase the previous one has to complete first, plus an `array.get`
   bounds check.
4. Therefore **flattening the vtable is a real lever on wasmtime and worth
   nothing on V8**, and **no optimizer will do it for us**. `wasm-opt -O3`
   halves the direct-call control (2.385 → 1.085, it inlines the callee) and
   makes the dispatch case slightly *worse* (8.564 → 8.750). It can see through
   a static call and cannot see through a vtable, which is the same boundary
   the design has to move by hand. It is not measured yet whether removing a
   level — the arity array reachable straight from `$obj`, at one more word per
   object — buys the ~2 ns per level this implies.

**Threats to validity, recorded so the number is not over-read.**

- The benchmark is a **dependency chain** — each dispatch's result is the next
  one's receiver. That measures dispatch *latency*, with no room for the engine
  to overlap independent calls, and is the pessimistic reading. Seq traversal
  has this shape; a `reduce` over an already-realised vector does not.
- The JVM baseline is monomorphic, so **C2 devirtualises it completely** —
  1.517 against 1.415 for a control with no dispatch at all. That is the JVM at
  its best, which is what B1 is supposed to compare against, and it is why B2
  exists.
- **wasmtime is timed by process slope** — wall time at n and 2n, difference
  over n — because there is no clock in the guest. Checked linear over
  n = 5/10/15/20 M with an intercept indistinguishable from zero.
- **One machine, arm64.** CI is x86_64 Linux and has not been measured.
