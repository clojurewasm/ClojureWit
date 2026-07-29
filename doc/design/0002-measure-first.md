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
| B3 | `i31` inline arithmetic vs JVM boxed `(+ a b)` | **faster than JVM** — JVM does a double dispatch through `Numbers.ops`; ours is two `ref.test`s and an add | **confirmed, on both lanes, by 3.2×.** JVM 2.982; V8 0.927 and wasmtime 0.912, both **0.31×**. The first benchmark wasmtime wins. The overflow check is free (0.912 against 0.917 without it). |
| B4 | `ref.cast` at hierarchy depth 2 vs 6 | **depth matters measurably** — enough to justify a flat type graph | **falsified.** Depths 2, 3, 4 and 5 cost 3.669, 3.667, 3.672 and 3.698 ns on wasmtime — flat to within 1%. Depth is free; the prediction named the wrong axis, and so did the design guidance it supported. |
| B4b | the axis B2 questioned: `ref.cast` cost vs *variety of input types*, depth held fixed | **recorded 2026-07-29, before the run, and it contradicts B4's own prediction above.** Both engines implement a cast as a constant-time supertype-array probe, so **neither depth nor variety should matter measurably**, and B4's original prediction is wrong. The 6.81-vs-2.33 gap in B2 that raised the question was confounded — it varied the ring *and* the callee — and I expect it to be the mixed ring's dispatch, not the cast. Falsified if either axis moves the number more than the run-to-run spread. | **half right.** Depth: flat, as predicted. Variety: **+2.14 ns on wasmtime** (3.718 → 5.860), so B2's lead was real and this prediction is falsified on that axis. On V8 neither moves anything (0.843 → 0.844). |
| B5 | guarded call-site specialisation, on wasmtime | **recorded 2026-07-29, before the run.** B1 says the whole server-lane cost is the load-to-indirect-branch recurrence, and a guard removes it on the hit path. So: **at 100% hit, within 1 ns of the direct-call control (2.33)** — passing the stop condition. **At a low hit rate, worse than generic dispatch**, because the guard is paid and the vtable path taken anyway. **On V8, no material change**, since it already speculates the same shape. | **1 and 2 confirmed, 3 wrong.** At 100% hit wasmtime is **2.390** against a 2.331 floor. That difference — 0.06 ns — is *below this benchmark's own resolution* (a re-run gives 0.15), so the honest statement is **indistinguishable from the direct-call floor**, which is what the 1 ns budget needs. At 2/11 hit it is **12.37**, worse than generic dispatch's 9.22. On V8 there *is* a change: guarded is 0.733 against generic's 0.894, landing on the floor. |
| B7 | the specialisation crossover — guarded cost vs guard hit rate | **recorded 2026-07-29, before the run.** Solving B5's two points gives a per-miss cost of ~14.6 ns against generic dispatch's 9.2, and ~5 ns is far too much for a `ref.test`. So the guard's cost is **branch misprediction, not the test** — which predicts the curve is **not monotonic in hit rate**: cheap at 100% *and* at 0% (both perfectly predictable), worst in the middle. If instead it rises monotonically as hits fall, the cost is the test and this reasoning is wrong. | **wrong, and the stated falsifier is what happened.** The curve is **monotonic** on both lanes; 0% hit is the *worst* point (12.05) despite being perfectly predictable. Crossover roughly **25% on wasmtime, 80% on V8**, ±5 points — the **3× ratio between lanes is the robust part** (a re-run reproduces 3.06× against 3.04×); the individual figures are not, for three reasons recorded below. |
| B7b | is B7's generic-control hump signal or noise? k = 1, 2, 4, 5 | **recorded 2026-07-29, before the run.** The hump is **real, and it is indirect-branch prediction on the generic path**: at k=0 and k=11 the ring holds one type so `call_ref` always goes to the same target and is predicted; in between it alternates between two, and mispredicts. That predicts the new points **trace a smooth hump rather than a flat 8.5**, and the wasmtime crossover stays near 25% rather than moving to 41%. Falsified if k=1 and k=2 come in flat at the endpoint value. It does not explain why B2's *ten*-type ring (9.22) is cheaper than this two-type one (10.12), and that stays open. | **confirmed, and the lane asymmetry confirms the mechanism.** The generic control traces a smooth hump — 8.51, 9.09, 9.31, 10.12, 10.12, 10.55, 10.31, 9.24, 8.50 — rising from both single-type endpoints toward the middle. **On V8 there is no hump at all** (0.856–0.936 across every ring), which is what "the engine speculates, so indirect-branch predictability does not dominate" predicts. The wasmtime crossover lands at **26.6%**, so ~25% stands and 41% is ruled out. The ten-vs-two-type anomaly is untouched and stays open. |
| B6 | the component boundary: what an aggregate argument costs to lower into linear memory | **recorded 2026-07-30, before the run.** WasmGC has **no bulk copy from a GC array into linear memory** — `array.copy` is array→array and `array.new_data`/`init_data` read a *data segment*, not memory (checked against zwasm's opcode set, a full Wasm 3.0 GC implementation). So the Canonical ABI's lowering is a per-element loop, and the prediction is: **`(array i8)` ≈ 0.5 ns/byte on wasmtime; `(array i64)` ≈ 8× better** because it moves eight bytes per iteration; **`memory.copy` ≈ 0.02 ns/byte**, memcpy-class, which is what a linear-memory language pays. If so the byte path is ~25× a Rust component's and the i64 path ~3×, and `0008` licenses choosing the wider representation. **Falsified if `(array i64)` is not ~8× `(array i8)`** — then the cost is not per-element loop overhead and the representation lever is worthless. | **confirmed on the lever, wrong on the ratios.** `(array i64)` is **7.5×** `(array i8)` on wasmtime and 7.1× on V8, so the representation lever is real. But `memory.copy` came in at **0.0086 ns/byte**, 2.3× better than predicted, so the gap to a linear-memory language is **worse** than predicted: 72× naive and **9.6× even with the i64 representation**, against the predicted 25× and 3×. |
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
   absolute, per lane) V8 passes at 0.13 and wasmtime fails at ~6.1. See
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

## B5 — measured 2026-07-29. The server lane passes, on a cliff.

Same module as B2, so the type graph and both rings are provably identical to
the baselines. A guarded site tests the receiver against the expected type,
calls directly on a hit, and falls through to the unchanged vtable path on a
miss — the fallback has to be there or this measures an unsound transformation.

ns per dispatch, wasmtime | V8:

| | generic | **guarded** | direct-call floor |
|---|---|---|---|
| one receiver type (mono ring) | 8.489 \| 0.894 | **2.390 \| 0.733** | 2.331 \| 0.739 |
| ten receiver types, 2/11 hit | 9.216 \| 1.987 | **12.368 \| 1.879** | — |

**1. The lever works, and it is the whole game.** On a site the analysis gets
right, guarded specialisation costs **0.06 ns over a direct call on wasmtime**
and **nothing on V8** — it lands on the floor on both lanes. The 6.16 ns of
dispatch overhead B1 found is gone, and the `br_on_cast` guard that replaces it
is free at this hit rate. **Both lanes now pass the S0 stop condition**, which
generic dispatch failed on the server by 6×.

**2. It is a cliff, not a slope.** At a 2-in-11 hit rate wasmtime is 12.37 —
**worse than not specialising at all** (9.22), because the guard is paid and the
vtable path taken anyway. Specialising a site the analysis is wrong about is
more expensive than leaving it generic.

**3. So S0's answer is conditional on per-site guard *precision*, and `0004`'s
coverage report stops being a nicety.** Two quantities get conflated here and
should not be: B5 and B7 measure **hit rate** — what fraction of dynamic
executions at one site the guard wins — while **coverage**, what fraction of
call sites the analysis can prove precise enough to specialise at all, is what
a compiler controls and is unmeasured. A 0-CFA target set does not carry a hit
rate. The design is viable exactly to the extent that analysis can bridge
those, and nothing here says it can. Where the crossover sits —
the hit rate at which specialisation stops paying — is **not measured**: the two
rows above walk different rings, so they cannot be interpolated. That needs
rings of varying type mix and is the next thing to measure.

**4. V8 gains a little too**, which the prediction denied: 0.894 → 0.733, and
its residual 0.155 ns of dispatch overhead disappears. V8 speculates the generic
shape well but not perfectly, and a static guard beats it.

## B7 — measured 2026-07-29. The crossover, and it differs 3× between lanes.

Five rings of eleven nodes, k of them the guarded-for type and the rest one
other type — so the only thing varying is how often the guard hits.
Megamorphism is deliberately absent; B2 priced that separately. Each ring is
measured both ways, so every comparison is within one ring.

ns per dispatch, **wasmtime**:

| hits | guarded | generic | guarded − generic |
|---|---|---|---|
| 0/11 | 12.045 | 8.505 | **+3.54** |
| 3/11 | 10.006 | 10.123 | −0.12 |
| 6/11 | 7.264 | 10.314 | −3.05 |
| 9/11 | 4.081 | 9.241 | −5.16 |
| 11/11 | 2.448 | 8.496 | **−6.05** |

**V8**:

| hits | guarded | generic | guarded − generic |
|---|---|---|---|
| 0/11 | 1.412 | 0.893 | **+0.52** |
| 3/11 | 1.278 | 0.936 | +0.34 |
| 6/11 | 1.111 | 0.925 | +0.19 |
| 9/11 | 0.888 | 0.898 | −0.01 |
| 11/11 | 0.739 | 0.917 | **−0.18** |

**1. The prediction was wrong and its own falsifier fired.** The curve is
monotonic in hit rate on both lanes — no hump in the middle — and the *worst*
point is 0% hit, where the branch is perfectly predictable. So the guard's cost
is the test plus taking the slow path anyway, not misprediction.

**2. The crossover is roughly 25% on wasmtime and 80% on V8 — ±5 points, and
softer than two figures imply.** By interpolation wasmtime turns profitable just
under 3-in-11 and V8 not until nearly 9-in-11; a re-run at n=4M gave 24.8% and
76.0%. Three caveats, all of which make the figures softer:

- **V8's rests on an endpoint of −0.01 ns**, which is inside V8's own spread.
  The 9/11 point cannot be told apart from the crossover itself, so the data
  bounds V8's crossover only to somewhere around 70–90%.
- **The interpolation spans 27 percentage points with no intermediate point**,
  over a guarded curve whose per-node slope varies by 60%.
- **The generic control is not flat** — it humps ~20% in the middle, and the
  wasmtime crossover sits inside the steepest part of it. **Settled by
  measuring k = 1, 2, 4, 5** (B7b): the hump is real and smooth, so the
  crossover is **26.6%** rather than the ~41% a flat control would have given.

With those points the wasmtime curve is:

| k/11 | 0 | 1 | 2 | 3 | 4 | 5 | 6 | 9 | 11 |
|---|---|---|---|---|---|---|---|---|---|
| guarded | 12.05 | 11.46 | 10.76 | 10.01 | 9.04 | 8.21 | 7.26 | 4.08 | 2.45 |
| generic | 8.51 | 9.09 | 9.31 | 10.12 | 10.12 | 10.55 | 10.31 | 9.24 | 8.50 |

**The hump is indirect-branch prediction on the generic path**, and the lanes
prove it: a one-type ring sends every `call_ref` to the same target and is
predicted; a two-type ring alternates and mispredicts. **V8 shows no hump**
(0.856–0.936 across all nine rings) because it speculatively inlines, so
predictability of the indirect branch never dominates there. The V8 crossover
is unchanged at ~80% and still rests on a −0.01 ns endpoint inside its spread,
so that figure remains the soft one.

**An unexplained anomaly under the threshold, recorded rather than smoothed
over:** B7's generic path on a *two-type* ring costs 10.12 ns at 3/11, while
B2's generic path on a *ten-type* ring costs 9.22. Bimorphic dispatch measuring
~10% slower than megamorphic, reproducibly, in the same module. That number is
the numerator of the crossover subtraction and nothing accounts for it.

**A compiler tuned to one lane makes the wrong call on the other** — at a 50%
site, specialising wins 3 ns on wasmtime and loses 0.2 ns on V8.

**3. V8 punishes speculative specialisation and wasmtime rewards it.** V8's
generic path is flat in type mix (0.89–0.94 across every ring) because it
speculates well; its guarded path degrades from 0.74 to 1.41. wasmtime's
generic path is flat too (8.5–10.3) but four times slower, so there is far more
for a correct guard to recover.

**4. What this means for the design.** `0004`'s call-site specialisation needs a
*threshold*, and there is no single right one. Three options, none free:
target the conservative lane (80%, leaving 3–6 ns on the table for wasmtime),
emit per-lane builds (a second artifact, and `0009` already constrains what
those can be), or make it a build knob and document the trade. That is a
decision for S3, and it is now grounded rather than guessed.

## B4 — measured 2026-07-29. Depth is free; the design guidance was backwards.

A straight chain `$obj → $d2 → … → $d5` with ten leaf types at depth 6, all
carrying the same fields, so the cast target and the input variety can be moved
independently. `cast_none` is the floor — the same ring walk with no cast.

ns per step, **wasmtime | V8**, with the delta over the floor:

| | ns | over floor |
|---|---|---|
| no cast (floor) | 0.920 \| 0.677 | — |
| cast to depth 2 | 3.669 \| 0.774 | 2.75 \| 0.10 |
| cast to depth 3 | 3.667 \| 0.783 | 2.75 \| 0.11 |
| cast to depth 4 | 3.672 \| 0.838 | 2.75 \| 0.16 |
| cast to depth 5 | 3.698 \| 0.837 | 2.78 \| 0.16 |
| **cast to depth 6 — the object's own leaf type** | **1.007 \| 0.732** | **0.09 \| 0.06** |
| cast to depth 5, 1 input type | 3.718 \| 0.843 | 2.80 \| 0.17 |
| **cast to depth 5, 10 input types** | **5.860 \| 0.844** | **4.94 \| 0.17** |

**1. Depth costs nothing.** Flat to within 1% from depth 2 to depth 5 on
wasmtime, and inside the spread on V8. The recorded B4 prediction is falsified.

**2. What costs is casting to a type that has subtypes.** Same object, same
ring, same depth-6 input: casting to `$d5` costs 2.80 ns and casting to `$v0` —
the object's own leaf type, which nothing extends — costs 0.09. That is a
single-variable comparison, and it is a 30× difference.

**3. And it costs again when the input varies.** Target held at `$d5`, ring
changed from one leaf type to ten: **+2.14 ns**. B2's lead was real. V8 shows
none of this — 0.843 against 0.844 — so it is a Cranelift property, not a Wasm
one.

**4. The design guidance in `0004` was aimed at the wrong axis, and pointed the
wrong way.** "Keep the type graph shallow and wide" is exactly backwards:
shallow buys nothing, and *wide* is what a cast pays for. The rule the numbers
support is **cast to leaves, and keep the set of types reaching a cast site
small** — which is the same thing call-site specialisation already does, so B4
and B5 are the same lever seen twice. A specialised site casts to a known leaf
type and pays 0.09 ns; a generic one casts to a broad supertype over many
inputs and pays up to 4.94.

## B3 — measured 2026-07-29. The predicted profile, both halves of it.

Accumulate by one, n times, as a dependency chain. The i31 path is inline —
two `br_on_cast_fail`, two `i31.get_s`, an `i32.add` and a real overflow check
— with a reachable slow path, because a fast path measured without its fallback
is a different program.

ns per add:

| | JVM Clojure | V8 | wasmtime |
|---|---|---|---|
| **boxed / `i31`** | 2.982 | **0.927** (0.31×) | **0.912** (0.31×) |
| `i31`, overflow check removed | — | 0.898 | 0.917 |
| **unboxed floor** | **0.109** | 0.229 (2.10×) | 0.242 (2.22×) |

**1. The prediction is confirmed, and this is the first benchmark wasmtime
wins.** Boxed arithmetic costs both lanes 0.31× what it costs JVM Clojure —
`Numbers.add(Object, Object)`'s double dispatch against two casts and an add.
Notably wasmtime matches V8 here: there is no indirect call, so the
load-to-indirect-branch recurrence that dominates B1 never arises.

**2. The overflow check is free.** 0.912 with it, 0.917 without, on wasmtime —
inside the spread. It does not need to be an opt-out.

**3. `doc/roadmap.md`'s stated profile is confirmed on both halves at once** —
"better on dispatch-heavy and boxed-arithmetic code, worse where the JVM can use
primitives". Boxed: we win 3.2×. Unboxed: we lose 2.1×. One benchmark, both
directions, which is a better test of that claim than either half alone.

## B6 — measured 2026-07-30. The boundary costs ~10× a linear-memory language.

4 KB payload, `n` counting whole payload copies, so the driver's ns/op is ns
per 4 KB. `bb bench-s0 B6l8 B6l64 B6lift B6mc B6ac --n 20011 --reps 20`.

| | wasmtime ns/4 KB | ns/byte | V8 ns/4 KB | ns/byte |
|---|---|---|---|---|
| `(array i8)` → memory, byte loop | **2543.6** | 0.621 | 1288.4 | 0.315 |
| `(array i64)` → memory, 8 bytes/iter | **338.9** | 0.083 | 180.7 | 0.044 |
| memory → `(array i8)`, lifting | 2224.0 | 0.543 | 1592.4 | 0.389 |
| `memory.copy` — the linear-memory floor | **35.3** | 0.0086 | 36.8 | 0.0090 |
| `array.copy` — GC to GC, for reference | 51.1 | 0.0125 | 66.7 | 0.0163 |

**1. The representation lever works and `0008` licenses it.** Holding byte
payloads as `(array i64)` instead of `(array i8)` is **7.5× cheaper** at the
boundary on wasmtime (7.1× on V8), because the copy is a per-element loop and
each element carries eight bytes instead of one. No program can observe the
difference, so `0008` says it is ours to choose — this is that principle paying
for itself.

**2. The boundary still costs ~10× what a linear-memory language pays.** A Rust
component moves a 4 KB argument for **35 ns**; a Clojure one for **339 ns** at
best and 2544 ns done naively. That is the price of `0007`'s finding, quantified.

**3. The gap has an exact shape: the missing instruction.** `array.copy` moves
the same 4 KB GC-to-GC for **51 ns** — near memcpy class. The bulk move exists;
it simply cannot reach linear memory, because WasmGC has no array↔memory copy
at all (`array.new_data`/`init_data` read a *data segment*, a compile-time
constant). **If that instruction existed, the boundary would cost ~51 ns rather
than 339 — a further 6.6×.** That is a concrete thing to want from the spec, and
it is a smaller ask than [component-model#525], which would remove the copy
entirely.

**4. Scalar-only exports still cost nothing**, per `0007`. This is a cost per
*aggregate* argument, and a component whose interface is scalars pays none of it.

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
- **B5's guard tests one type.** A real specialised site with several candidates
  needs several guards or a switch, and nothing here says what the second and
  third cost. The 0.06 ns result is the best case by construction.
- **B6 measures throughput on a 4 KB payload, not per-call cost.** Real WIT
  arguments are often tens of bytes, where a fixed per-call constant would
  dominate instead. **That constant was measured on 2026-07-30 and is ~2.5 µs**
  (`doc/design/0011-*`), which is 7× what moving 4 KB costs — so for any payload
  under roughly 30 KB the *call* dominates the copy, and B6's ~10× ratio is a
  large-payload statement in the strong sense.
- **B6's copies are hot and aligned.** Both buffers stay resident across
  20,011 iterations, so nothing here prices a cold destination or the
  `cabi_realloc` call that a real lowering makes first.
- **B3's unboxed floor is the weakest number in the set.** JVM Clojure's is
  0.109 ns — about 0.4 cycles per iteration — which is fast enough that C2 has
  probably strength-reduced or vectorised a loop whose result is a linear
  function of the trip count. Ours may be partly reduced too. So "2.1× slower
  unboxed" is a statement about what two optimizers did to an easy loop, not
  about arithmetic. The boxed row is not affected: boxing blocks that
  rewriting, which is why it is the headline. Settling it needs an accumulator
  the optimizer cannot fold — data-dependent, not a constant stride.
- **B4's "leaf type" reading is inferred.** What is measured is that a cast to
  `$v0` (which nothing extends) is 30× cheaper than a cast to `$d5` (which ten
  types extend). Whether Cranelift keys that on the target being a leaf, on it
  being effectively final, or on something else was not established; no
  disassembly was read.
- **B7's rings hold two types, B5x's held ten.** They are not points on one
  curve, and treating them as such is how B7's prediction went wrong: the
  per-miss cost extrapolated from B5's two points was 14.6 ns, and B7 measured
  12.0. Fourth time in this project that two rings got compared as if they were
  one variable.
- **The arrangement within a ring is unexplored.** B7 clusters the guarded-for
  type at the head; interleaving it would give the same hit rate with a
  different branch pattern. Since the misprediction theory was falsified this
  probably matters little, but "probably" is not a measurement.
- **B5's fallback is the generic path, not a slow path that re-specialises.**
  A compiler that re-profiles would behave differently, and this project does
  not have one (`0003`: we do not write a JIT).
