# 0010 — S0's verdict: viable, on a condition nobody has measured yet

**Status:** accepted · 2026-07-29

## The question

`doc/roadmap.md` S0: *is the dispatch design in `0004` viable?* Six benchmarks
answer it. The numbers and their controls are in
`doc/design/0002-measure-first.md` and are **not restated here** — this note is
the decision they add up to, plus what it is not allowed to mean.

## The decision

**Yes on both lanes, for production builds, at call sites the compiler can
specialise precisely.** Every qualifier in that sentence is load-bearing.

| | generic dispatch | specialised, 100% hit |
|---|---|---|
| V8 | 0.13 ns over a direct call — passes | indistinguishable from the floor |
| wasmtime | **~6.1 ns — fails the 1 ns budget by 6×** | ≤0.15 ns — passes |

The specialised overhead is quoted as a bound, not a value: the published run
gives 0.06 ns and a re-run at smaller n gives 0.15, and both are inside the
2% run-to-run spread on a ~2.3 ns floor. What is established is that a
guarded specialised site is *not distinguishable* from a direct call, which is
all the budget needs.

**The condition is per-site guard precision.** A guard that mostly misses is
worse than no guard: specialising starts paying at **26.6% hit rate on
wasmtime** and **80% on V8**, each bracketed by adjacent measured points. Below the
threshold, specialising loses on that lane.

**Coverage is a different quantity and is unmeasured.** B7 measured *hit rate*
— what fraction of dynamic executions at one site the guard wins. *Coverage* —
what fraction of call sites the analysis can prove precise enough to specialise
at all — is what a compiler actually controls, and no benchmark here touches
it. A 0-CFA target set does not carry a hit rate. **That gap is the real S0
residue and it belongs to S3.**

## Why

The mechanism, not the totals: B1 attributes wasmtime's whole ~6.1 ns to the
*first* load off the receiver, which the indirect branch cannot run ahead of.
Neither flattening the vtable (0.11 ns, B1L) nor `wasm-opt -O3` moves it, and
neither does wasmtime 47's own inliner — `-C inlining=y` changes B1 by 0.09 ns,
inside the spread, measured 2026-07-29 rather than inferred from release notes.
A guard removes the load, so the cost goes with it.

That is why the answer is "yes" rather than "narrow the scope":

- the browser lane passes **generically** and beats JVM Clojure at both dispatch
  benchmarks (B1 0.58×, B2 0.61×);
- the server lane passes with a lever this project controls, and `0003` had
  already scheduled the adjacent work — though it named the wrong mechanism,
  and now carries an amendment saying so.

Separately, and not conditional on any of the above: **boxed arithmetic costs
0.31× JVM Clojure on both lanes, and the unboxed floor costs 2.1×** (B3). That
is the profile `doc/roadmap.md` predicted, confirmed in both directions at once.
`0002` records the unboxed half as the weakest number in the set.

## What S0 did **not** answer

A verdict that reads as broader than its evidence is worse than no verdict, and
the bounds belong here rather than only in `0002`'s threats.

- **Nothing measured here is a Clojure program.** Every benchmark is a
  synthetic 11-node ring pointer chase — no `seq`, no `reduce`, no allocation
  in the hot loop, no GC pressure, no realistic call-site mix.
- **B5's guard tests one type.** A real site with two or three candidates needs
  a guard chain, and nothing prices the second test. **The specialised number is
  the best case by construction** — this is the single most important bound on
  the table above.
- **This is a verdict about production builds.** `0009` gives development mode
  an open world with no whole-program specialisation, so the server lane there
  is generic dispatch at ~6.1 ns. What that costs the edit-evaluate loop is
  unmeasured, and it is the mode people will spend their time in.
- **B5's rings hold ten types and B7's hold two.** They are not points on one
  curve, and this note places them in one argument.
- **B7's ring arrangement is clustered**, not interleaved — every crossover
  figure is for one arrangement of hits.
- **Two mechanisms here are inferred, not established.** The
  load-to-indirect-branch recurrence, and B4's reading that what a cast pays for
  is the target having subtypes. No disassembly was read and no performance
  counter was sampled. B7b *strengthened* the first — the generic path's cost
  humps with indirect-branch unpredictability on wasmtime and not at all on V8,
  which is the asymmetry the recurrence predicts — but strengthening is not
  establishing.
- **The dependency-chain shape is pessimistic.** Each dispatch feeds the next,
  so nothing overlaps.
- **The table is the unoptimised build.** `wasm-opt -O3` inlines the callee in
  the direct-call control on one lane and not the other, so `-O3` numbers are
  not comparable across lanes (`0002`).
- **The component boundary is unmeasured** (B6). `0007` says every aggregate
  crossing it is a GC-to-linear-memory copy; no S0 benchmark touches it, and it
  is what the project's pitch rests on.
- **One machine, arm64.** CI runs `bb check` only.
- **The JVM baselines may be flattered.** `0002` records that C2 appears to
  devirtualise the monomorphic site, and every ×JVM ratio inherits that.

## Alternatives rejected

- **Declare S0 inconclusive until B6.** Rejected: S0's question is dispatch and
  B6's is the boundary. Holding the stage open across an unrelated question
  stalls S1, which is where the type mapping B6 informs gets designed.
- **Narrow to the browser lane** and accept ~5.6× on the server. Rejected on
  measurement: specialisation closes it, and `0003`'s both-lanes commitment is
  what makes the server lane worth the work.
- **Keep generic dispatch and wait for wasmtime's inliner.** Rejected on
  measurement rather than on schedule: `-C inlining=y` on wasmtime 47.0.1
  changes B1 by 0.09 ns. Re-check at each stage entry.
- **Conclude that no single specialisation policy can serve both lanes.**
  Rejected as too strong, and `0002` says the accurate thing: a single
  conservative threshold at 80% never regresses either lane, and wasmtime still
  collects 5–6 ns per specialised site where it applies. Per-lane builds or a
  build knob buy the rest back at a cost `0009` constrains. **Which trade to
  make is an S3 decision, not one this note gets to close.**
- **Take these numbers as a performance claim.** Rejected explicitly: see the
  section above. They price mechanisms on one machine.

## What would falsify this

- **A realistic Clojure workload where guard precision lands below 27%.** The
  falsifier that matters, and it cannot be run before S3.
- **`bb bench-s0`, at the recorded n and reps, producing a specialised overhead
  above 1 ns, or a wasmtime crossover above 50%.** Stated with tolerances
  because the two figures are not equally solid. **wasmtime's is now measured
  at nine points and lands at 26.6%** — the generic control's ~20% hump turned
  out to be real (indirect-branch prediction, absent on V8, which is what
  confirms the mechanism), so a flat-control reading of ~41% is ruled out.
  **V8's is now bracketed at 80.1%** by points at k = 7, 8 and 10, which closed
  the gap from three of eleven to one. It is 80% give or take a few points
  rather than 70–90%, because the bracketing deltas are small against V8's own
  spread.
- **Any of the six failing to reproduce on x86_64 Linux**, which nothing has
  tried.
