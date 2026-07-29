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
| B1 | protocol dispatch, monomorphic | **within 2× of JVM** — JVM's cached path is 1 compare + interface call; ours is 3 loads + `call_ref` | **half right.** V8 **0.58×** (faster than JVM); wasmtime **5.61×**. The prediction holds on V8 and fails on wasmtime. The reasoning is wrong on both: the cost is neither the `call_ref` nor the three loads, but the first of them. |
| B2 | protocol dispatch, 10 receiver types at one site | **faster than JVM** — the JVM's per-call-site cache thrashes; a vtable slot has no cache to thrash | _pending_ |
| B3 | `i31` inline arithmetic vs JVM boxed `(+ a b)` | **faster than JVM** — JVM does a double dispatch through `Numbers.ops`; ours is two `ref.test`s and an add | _pending_ |
| B4 | `ref.cast` at hierarchy depth 2 vs 6 | **depth matters measurably** — enough to justify a flat type graph | _pending_ |
| — | V8 vs wasmtime on the same module | **V8 meaningfully faster on B1/B2** — it has speculative inlining; wasmtime has no adaptive tier | **confirmed on B1, by more than expected: 9.8×** (0.865 vs 8.434 ns/op). |

If a prediction is wrong, the design note it came from gets amended and the
amendment says which prediction failed.

## B1 — measured 2026-07-29

```
bb bench-s0
Darwin arm64 · Apple M4 Pro
wasmtime 47.0.1 · wasm-opt 129 · wasm-tools 1.254.0 · node v24.18.0 · openjdk 25.0.3
n=20,000,000  reps=20  warmup=5 · medians
```

Four exports over one module, differing only in **how far the call target is
from the receiver**. ns per dispatch:

| lane | B1c direct | B1i 0 loads | B1L 1 load | B1 3 loads |
|---|---|---|---|---|
| JVM Clojure | 1.386 | — | — | **1.504** |
| V8 (node) | 0.737 | 0.783 | 0.813 | **0.865** |
| V8, `wasm-opt -O3` | 0.736 | 0.787 | 0.814 | 0.866 |
| wasmtime | 2.346 | 2.475 | 8.326 | **8.434** |
| wasmtime, `wasm-opt -O3` | 1.081 | 2.519 | 8.659 | 8.676 |

Repeated full runs agree within ~2% on the dispatch rows and ~4% on the JVM
control, so nothing below turns on a difference smaller than that.

**What it says.**

1. **The design passes on V8 and fails on wasmtime.** Under the stop condition
   as it stood at the time — ~10× JVM Clojure — both lanes passed at 5.6×
   worst. That bar was withdrawn on 2026-07-29 for being unable to
   discriminate, and under its replacement (dispatch overhead under 1 ns,
   absolute, per lane) V8 passes at 0.13 and wasmtime fails at 6.08. See
   `doc/roadmap.md`.
2. **On V8 the dispatch is free** — 0.13 ns over the direct-call control across
   all three levels, and V8 still beats JVM Clojure outright. Speculative
   inlining reaches us.
3. **On wasmtime the dispatch costs 6.1 ns, and none of it is the indirect
   call or the extra levels.** The curve is the whole finding:

   | wasmtime step | cost |
   |---|---|
   | direct call → `call_ref` with the target in hand | 0.13 ns |
   | 0 loads → **1** load off the receiver | **5.85 ns** |
   | 1 load → 3 loads (two structs and a bounds-checked `array.get`) | 0.11 ns |

   The entire cost appears at the **first** load off the receiver and is flat
   after it. What is expensive is not indirection depth but the
   **load-to-indirect-branch recurrence**: until the target is loaded, the
   branch predictor has nothing to run ahead on, and the second and third loads
   hide behind the first because they are no longer on that critical path.
4. Therefore **flattening the vtable buys nothing.** Collapsing three levels to
   one — the `bench_one_load` export, a funcref field directly on `$obj`, one
   more word on every object — is worth **0.11 ns**, inside the run-to-run
   spread. The lever on wasmtime is **making the target statically known** so
   there is no load at all, which is what `doc/design/0004-*` already proposes
   under call-site specialization, and is B1c's 2.35 ns.
5. **`wasm-opt -O3` cannot do that for us.** It halves the direct-call control
   (2.346 → 1.081; it inlines the callee) and does nothing to any `call_ref`
   case. It sees through a static call and not through an indirect one — the
   same boundary the compiler has to move by itself. Its 8.434 → 8.676 on B1 is
   within spread and is not read as a regression.

**How point 3 was arrived at, because the first answer was wrong.** The
originally committed version of this note had only B1, B1i and B1c, and
concluded from `B1 − B1i = 6.0` that "the three dependent loads" cost ~2 ns
each and that collapsing levels was the lever. A difference between two
variants says what those two differ by — here, four things at once — and not
which of them accounts for it. `B1L` is the settling control, it cost about
thirty lines, and it says the per-level reading was wrong by a factor of fifty.
This is the same failure this note exists to prevent, committed inside the note
that records it; see `.claude/rules/measurement.md`.

**Threats to validity, recorded so the number is not over-read.**

- The benchmark is a **dependency chain** — each dispatch's result is the next
  one's receiver. That measures dispatch *latency*, with no room for the engine
  to overlap independent calls, and is the pessimistic reading. Seq traversal
  has this shape; a `reduce` over an already-realised vector does not. It is
  also why the recurrence in point 3 dominates; a site whose receiver is not
  itself the previous call's result would hide more of that load.
- The JVM baseline is monomorphic and **C2 appears to devirtualise it** —
  1.504 against 1.386 for a control with no dispatch at all. That gap is close
  to the JVM row's own run-to-run spread, so it is weak evidence; the bytecode
  was not disassembled to confirm the call site emits Clojure's cached fast
  path. Either way it is the JVM at its best, which is what B1 is supposed to
  compare against, and it is why B2 exists.
- **`dispatch = B1 − B1c` means different things per row.** `wasm-opt` inlines
  the callee in the direct-call control and wasmtime does not inline it
  otherwise, so the control is not the same floor in every lane. The wasmtime
  conclusions above are drawn from the `call_ref` columns among themselves,
  where the callee is identical.
- The mechanism in point 3 is **inferred from the shape of the curve**, not
  from a performance counter. What is measured is where the step is; that it is
  branch-prediction is the explanation, not the observation.
- **wasmtime is timed by process slope** — wall time at n and 2n, difference
  over n — because there is no clock in the guest. Checked linear over
  n = 5/10/15/20/40 M with an intercept of ~2.5 ms.
- **One machine, arm64.** CI runs `bb check` only, so no number in this repo
  has been produced on x86_64 Linux.
