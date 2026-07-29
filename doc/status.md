# Status

_Short by design. If this file is long, something belongs in `doc/design/` or
`doc/roadmap.md` instead._

**Updated:** 2026-07-29 · **Phase:** feasibility (pre-alpha, no compiler)

## Where we are

Repository, toolchain, gate, and CI are in place and verified end to end
(`bb check` green in under 2s; `nix develop` builds and satisfies all seven
tools — wasmtime 47.0.1, binaryen 129, wasm-tools 1.254.0). **CI is green on a
fresh Linux runner in 50s.**

**S0: B1, B2 and B5 measured. Both lanes pass the stop condition — on a
condition.** Generic vtable dispatch fails the server lane by 6×. Guarded
call-site specialisation erases it: **0.06 ns over a direct call on wasmtime,
0.00 on V8**. But at a 2-in-11 hit rate wasmtime costs 12.4 ns against generic
dispatch's 9.2, so **specialising a site the analysis is wrong about is worse
than leaving it alone.** The design is viable exactly to the extent that
whole-program analysis can tell those cases apart.

ns per dispatch:

| | JVM Clojure | V8 (node) | wasmtime |
|---|---|---|---|
| B1 monomorphic, generic | 1.50 | **0.87** (0.58×) | **8.43** (5.61×) |
| B2 ten types, generic | 3.24 | **1.99** (0.61×) | **9.22** (2.84×) |
| B5 specialised, hits | — | **0.73** | **2.39** |
| B5x specialised, mostly misses | — | 1.88 | **12.37** |

**B7 found the crossover, and it is not the same on both lanes:**
specialisation starts paying at **~26% hit rate on wasmtime** and not until
**~80% on V8**. A compiler tuned to one lane makes the wrong call on the other.
The curve is monotonic — 0% hit is the worst point despite being perfectly
predictable — so the guard costs the test plus the slow path, not a branch
misprediction, which is what the recorded prediction claimed.

**V8 beats JVM Clojure outright** and its dispatch is free — speculative
inlining reaches us, as `doc/design/0003-*` hoped. **wasmtime pays 6.1 ns**, and
four exports along a curve say exactly where it goes: the `call_ref` is 0.13 ns,
the *first* load off the receiver is **5.85 ns**, and the second and third
together are 0.11 ns. So **flattening the vtable buys nothing** — the cost is
the load-to-indirect-branch recurrence, not the depth — and the only lever is
removing the load by specializing the call site. `wasm-opt -O3` will not do it.
Written up in `doc/design/0002-measure-first.md`; `doc/design/0004-*` carries an
amendment naming the prediction that failed.

## Surveyed 2026-07-29 — the component boundary is linear memory

The first `/survey` found something S0 had been built on top of without
checking: **no Canonical ABI that any runtime executes carries WasmGC
references across a component boundary.** In the spec, `gc` is not a
`canonopt`; in `wasm-tools 1.254.0` a `(canon lift … gc)` validates behind an
off-by-default flag; in `wasmtime 47.0.1` it panics; and `wasm-tools component
new` has no GC path at all. So the boundary is linear memory: a GC core module
exporting `func(s32,s32)->s32` becomes a component with no memory anywhere,
but change one parameter to `string` and it demands `memory`, then
`cabi_realloc`.

Consequences are in `doc/design/0007-*`: S4 is a lift/lower layer over linear
memory (8,574 lines in the sibling zwasm, no core changes), scalar-only exports
skip it entirely, and **S0 does not measure the crossing the pitch rests on**.
`doc/roadmap.md` carries that as an open question.

Also confirmed while there: WASI 0.3.0 shipped 2026-06-11 and wasmtime 43+
supports it (`tools.json` was already right); the widely repeated "wasmtime
does not support WasmGC" is stale — wasmtime 47 runs B1; and
[Kotlin/sample-wasi-http-kotlin](https://github.com/Kotlin/sample-wasi-http-kotlin)
is an official JetBrains prototype doing **both halves of what this project
does** — a WasmGC module componentized and served by wasmtime. It is the
closest existing artifact and should be read before S4 is designed.

## Direction set 2026-07-29 — three decisions from outside the repo

1. **Parity is at the boundary** (`0008`). If a Clojure program cannot observe
   the difference, there is no difference — so the internals are free to be
   whatever emits good Wasm. This is the permission that `CLAUDE.md`'s
   "semantics are not negotiable" never stated, and it is what makes
   specialisation legitimate rather than a shortcut.
2. **Both lanes are primary, and the budget is absolute** (`0003` amendment,
   `doc/roadmap.md`). Browser and server, the latter with Wasm edge platforms
   in view. "~10× JVM Clojure" is gone — it passed B1 at 5.6×, which it should
   not have — and so is the ratio-to-the-engine's-floor that first replaced it,
   which rewarded a slow floor. Dispatch overhead under **1 ns**, per lane.
3. **Two modes: dynamic in development, static in production** (`0009`).
   Designed together from the start, because the output-format decisions this
   forces cannot be retrofitted once compiled units exist.

The enabling measurement for (3): **independently compiled units share WasmGC
types iff their rec groups canonicalise alike.** On V8, two separate
`WebAssembly.Instance` calls with the reference passed through the host — module
B reads module A's objects; on wasmtime, `wasmtime run --preload`. So a REPL can
compile new code against a running heap.

The rule is **not** "one frozen group": groups are independent, so the invariant
is *minimise what shares one*, and a `deftype` goes in its own group referring
to the shared ones. Sharing is expensive — adding any type to a group changes
the identity of every type in it, which for `0004`'s per-arity `$fnN`/`$vtN`
pairs means **adding one supported arity invalidates every previously compiled
unit**. `test/cljwit/rec_group_identity_test.clj` pins all of it in the gate,
and `.claude/skills/wat` asserted the opposite until it was measured.

## Next

**S0 — B5, then B3 and B4.** Same contract, `bench/s0/README.md`; run them with
`bb bench-s0`. Predictions for all four are already recorded in
`doc/design/0002-measure-first.md` and must not be edited.

| | Measures | Decides |
|---|---|---|
| ~~B1~~ | ~~vtable-slot protocol dispatch~~ | **done** — V8 passes, wasmtime fails |
| ~~B2~~ | ~~the same site with 10 receiver types~~ | **done** — mechanism confirmed, V8 0.61× JVM, wasmtime 2.84× |
| ~~B5~~ | ~~guarded call-site specialisation~~ | **done** — both lanes pass, on a coverage condition |
| ~~B7~~ | ~~the specialisation crossover~~ | **done** — ~26% wasmtime, ~80% V8; no single threshold fits both |
| ~~B3~~ | ~~`i31` inline arithmetic~~ | **done** — 0.31× JVM on both lanes; unboxed we lose 2.1× |
| ~~B4~~ | ~~`ref.cast` cost vs type-hierarchy depth~~ | **done** — depth is free; *width* and input variety are what cost |

**B2 confirmed `0004`'s mechanism and not its conclusion.** Megamorphism costs
wasmtime +9% against JVM Clojure's +114% — a vtable slot really has no cache to
thrash — but wasmtime is still 2.84× JVM, inheriting B1's monomorphic overhead
unchanged. The surprise is V8: it degrades +122%, like the JVM, because winning
B1 by speculating means having speculation to lose. Details in
`doc/design/0002-*`.

**B4 falsified its own prediction and `0004`'s guidance with it.** Depth costs
nothing (3.669 → 3.698 ns from depth 2 to 5). What costs on wasmtime is casting
to a type *that has subtypes* — 2.80 ns, against 0.09 for casting to the
object's own leaf — and another 2.14 when ten input types reach one cast site.
V8 shows none of it. So "keep the type graph shallow and wide" was backwards on
both halves; the rule is **cast to leaves, keep the type set at a cast site
small**, which is the same lever as call-site specialisation seen from the other
side.

**S0's four contracted benchmarks are all measured**, plus B5 and B7 which the
findings added. B3 confirmed the roadmap's stated profile on both halves at
once: boxed arithmetic costs us 0.31× what it costs JVM Clojure, unboxed costs
us 2.1× — and it is the first benchmark wasmtime wins outright, because there is
no indirect call for its missing adaptive tier to matter to.

**Next, outside the original contract:**

- **B6 — the component boundary crossing.** A GC-to-linear-memory copy per
  aggregate argument, unmeasured, and it is what "a Rust developer calls a
  Clojure component" actually costs. Before S1 fixes the type mapping.

**Not worth doing:** collapsing the vtable's indirection levels. B1L measured
it at 0.11 ns. That question is closed.

**Not yet checked:** whether a module *compiled after* a wasmtime store has been
running and allocating behaves like `--preload`'s two-files-at-startup case.
That is the literal REPL case (`0009`). Also unchecked: browser realms (all V8
results are one Node isolate), and `wasm-opt` passes beyond the ones isolated.

## Blocked / needs a decision from outside

- Nothing yet.

## Verified

- **The benchmark driver fails on a wrong answer, not just a slow one.** Every
  lane's result is compared against what the benchmark claims to compute;
  feeding it a deliberately wrong expected value stops the run with a non-zero
  exit. Checked on all three lanes.
- **wasmtime's process-slope timing is linear.** Wall time at n = 5/10/15/20 M
  fits a line with an intercept indistinguishable from zero, so the slope is
  per-iteration cost and not process startup.
- **The WasmGC type-identity rule is asserted in the gate.**
  `test/cljwit/rec_group_identity_test.clj` builds each case and validates it
  with `wasm-tools` alone, so it runs anywhere `bb check` does — including the
  positive case `doc/design/0009` depends on and four ways of breaking it.
- **A cold-start session works.** A fresh agent with no prior context, given
  only `/next`, correctly identified the project, the current stage, that the
  compiler does not exist, and that the next action is to record S0 predictions
  before writing `b1_protocol.wat` — including the roadmap's warning about
  skipping S0. Re-run this check after any change to `.claude/`.

## Incidents so far

- **2026-07-29 — a measurement a whole design note rested on shipped as prose,
  with no way to re-run it.** `0009`'s rec-group result had no `.wat`, no
  script and no command anywhere in the repo, while B1 — a *less* load-bearing
  result — got `bench/s0/` and a task. `bb check` cannot catch that. Fixed by
  landing it as a test; the general lesson is that "the command that produced
  it" applies to pass/fail assertions and not only to timings.
- **2026-07-29 — two design conclusions were drawn from one-variable
  experiments, again.** `0009` concluded "one frozen rec group" from a single
  negative case when the matrix says *minimise what shares a group*; `0003`
  concluded WasmGC "excludes the near-native runtimes" from a table that
  omitted WasmEdge, which supports GC at 1.74×. Both were caught by adversarial
  review before push. The standing constraint in `.claude/CLAUDE.md` covering
  this was added the same day and did not prevent it — the constraint is
  necessary and not sufficient, and the thing that actually caught it both
  times was an independent reviewer with fresh context.

- **2026-07-29 — a stage was entered without checking what it stood on.** S0's
  four benchmarks were designed, and one of them built and measured, before
  anyone asked whether a WasmGC module could be a component at all. The answer
  changes the size of S4. Nothing was wasted — B1 stands — but it could have
  been a whole stage. One incident, not two; `doc/design/0006-*` argues the
  exception rather than pretending to a count, and the fix is the `/survey`
  skill reached from `/next` step 1.
- **2026-07-29 — the survey's own conclusion was over-generalised, and the
  design note stated the opposite of what one more command proved.** `0007`
  first claimed outright that GC references cannot cross a component boundary,
  from three runs of `wasm-tools component new` — a tool with no GC support to
  find. Hand-writing a `(canon lift … gc)` validated in under a minute with the
  same pinned binary. **Third occurrence in one day** of generalising from an
  experiment that varied one thing; the rule moved out of the bench-scoped
  `.claude/rules/measurement.md` into `.claude/CLAUDE.md`, since it has now
  fired outside `bench/`. Caught by adversarial review, before push, again.

- **2026-07-29 — a two-variant difference was written up as an attribution.**
  B1 was first committed concluding that wasmtime's 6 ns went on "the three
  dependent loads, ~2 ns each", from `B1 − B1i`. The two exports differ in four
  ways at once. The settling control (`B1L`, one load instead of three) cost
  thirty lines and says levels are worth 0.11 ns, not 6 — the reading was wrong
  by a factor of fifty, and `doc/status.md` had already queued the work it
  implied. Caught by an adversarial review before it was pushed.
  **This is the second occurrence** of the failure `doc/design/0002-*` exists to
  prevent, so it has earned machinery: `.claude/rules/measurement.md` now says
  that a difference between two variants is not an attribution.
- **2026-07-29 — the result check passed a benchmark doing no work.** The ring
  was 10 nodes and the published n was 20,000,000, so the expected answer was
  the head's own tag — which is also what a loop running zero iterations
  returns. Emptying the loop did not fail the run. Fixed by a prime ring length
  and a driver check that refuses any n where the expected value equals the
  zero-iteration one. Found by fault injection during review, not by the gate.

- **2026-07-29 — the gate passed locally and failed in CI.** `src/` and `test/`
  were empty directories, so git never tracked them; `clj-kondo` failed on a
  fresh clone. Fixed by having the tasks operate on directories that exist.
  Recorded because the second occurrence of "green locally, red on a clone" is
  what would justify machinery, and this is the first.

## Deliberately not built

Recording these so they don't get silently added by habit:

- **A two-tier test gate.** `bb check` takes seconds. Split it when it takes a
  minute, not before. (`bin/hook-gate-before-push` says what replaces it.)
- **A rule directory full of lint rules.** Rules should follow incidents.
- **Anything in the compiler.** S0 first.
