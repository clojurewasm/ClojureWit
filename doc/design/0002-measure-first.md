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
| B2 | protocol dispatch, 10 receiver types at one site | **faster than JVM** — the JVM's per-call-site cache thrashes; a vtable slot has no cache to thrash | **half right, and the mechanism is right.** V8 **0.61×** — faster than JVM, prediction holds; wasmtime **2.84×** — slower, prediction fails. But megamorphism costs wasmtime **+9%** against the JVM's **+114%**, so "no cache to thrash" is confirmed. V8 degrades **+122%**, because it *does* speculate and loses it. |
| B3 | `i31` inline arithmetic vs JVM boxed `(+ a b)` | **faster than JVM** — JVM does a double dispatch through `Numbers.ops`; ours is two `ref.test`s and an add | _pending_ |
| B4 | `ref.cast` at hierarchy depth 2 vs 6 | **depth matters measurably** — enough to justify a flat type graph | _pending_ — but B2 raised a question about the axis: see below |
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

## B2 — measured 2026-07-29

Same machine, tools and sizes as B1. Ten receiver types at one site, in a ring
of eleven so the ring length stays prime.

| lane | B2c direct | B2m 1 type | B2 10 types |
|---|---|---|---|
| JVM Clojure | — | 1.517 | **3.241** |
| V8 (node) | 0.739 | 0.894 | **1.987** |
| wasmtime | 2.331 | 8.489 | **9.216** |

**Read B2 against B2m, not against B1.** Ten types need a shared supertype to
carry the walk's fields, which puts the objects a level deeper than B1's, and
the deeper `ref.cast` is not free — an earlier draft of this benchmark compared
B2 to B1 and to a control that cast one level shallower, and priced the
controls against each other. `bench_mono` is B1's shape rebuilt inside B2's
type graph; with it, B2c lands on B1c (2.331 against 2.35 on wasmtime, 0.739
against 0.748 on V8), which is the check that the controls are now built alike.

**What it says.**

1. **The mechanism in `0004` is confirmed.** Megamorphism costs wasmtime
   **+0.73 ns (+9%)** against JVM Clojure's **+1.72 ns (+114%)**. A vtable slot
   really has no cache to thrash, and the design is very nearly indifferent to
   how many types a site sees.
2. **The prediction still fails on the server lane**, because indifference is
   not enough when the starting point is bad: wasmtime is 2.84× JVM at a
   megamorphic site, entirely inherited from B1's 6.16 ns of monomorphic
   dispatch overhead, which B2m reproduces to within 1%.
3. **V8 degrades like the JVM — +1.09 ns, +122%.** This is the surprise. V8
   wins B1 *because* it speculates, so at a megamorphic site it has speculation
   to lose, exactly as the JVM does. The "no cache to thrash" advantage is real
   but it accrues to the engine that never had one. Against JVM Clojure V8 is
   0.61× here and 0.58× at B1: the gap did **not** widen, which is what the
   prediction's reasoning implied it would.
4. **A lead for B4, deliberately not a finding.** An earlier control in this
   module walked the *mixed* ring through a callee casting to the shared
   supertype and cost 6.81 ns on wasmtime, where the present control — mono
   ring, callee casting to a concrete type — costs 2.33. Two things differ, so
   that gap attributes to neither. If what a `ref.cast` costs turns out to
   depend on **the variety of input types it sees** rather than on hierarchy
   depth, B4's prediction is aimed at the wrong axis and the note that made it
   needs amending, not just filling in.
5. **At a megamorphic site there is no direct-call floor to measure against.**
   A direct call is monomorphic in target by definition, so B2 − B2c (1.25 ns
   on V8, 6.89 on wasmtime) prices megamorphism *and* dispatch together and
   cannot be split further. The reachable floor at such a site is a guarded
   specialised call — which is B5, and this is a second reason S0 cannot
   conclude without it.

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
