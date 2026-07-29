# Status

_Short by design, and printed at every session start — so findings live in
`doc/design/`, plans in `doc/roadmap.md`, and only the present tense here._

**Updated:** 2026-07-30 · **Phase:** S0 closed, entering S1 (pre-alpha, no compiler)

## Next

**The calling convention is decided and the cost structure is measured**
(`0011`): `MethodHandleProxies/asInterfaceInstance` behind a `definterface`
gives a static call site at **6.2 ns** against `invokeWithArguments`' 400, so
**`cljwit.host` can be pure Clojure** — no bytecode generation, no C shim. But
a scalar component call costs **2471 ns**, so that boxing was only ~16% of it.
The C API offers only the dynamic call path; there is no typed equivalent of
the core module's `_call_unchecked`. **Against B6 this inverts the emphasis: for
payloads under ~30 KB the call dominates the copy.**

**S1's premise is verified end to end** (`0011`): `bb spike-host` builds a
component and calls it from the JVM through FFM — engine, store, component,
linker, instantiate, export lookup, call — returning 42. The flake now exports
`CLJWIT_WASMTIME_LIB` so no committed file carries a machine-specific path;
how a *shipped* library finds the shared object is still open.

Behind that (`0005`, surveyed 2026-07-30): wasmtime's C API
gained the component model between v40 and v43 — 0 exported
`wasmtime_component_*` symbols at 40.0.2, 154 at the pinned 47.0.1 — and the
JVM resolves them through `java.lang.foreign` on Java 25. `tools.json`'s ≥43
minimum, set for WASI 0.3, is also the component minimum; do not lower it.

1. **Decide whether ~2.5 µs per component call is acceptable for what
   `cljwit.host` is for** — and if not, which lever. A typed path does not
   exist in the C API; the alternatives are a Rust shim exposing one, or an API
   that batches. This is a product question as much as a technical one and it
   should be settled before the API hardens.
2. **Design `cljwit.host`'s API**, now that the calling convention is decided
   (interface proxies, pure Clojure) and the cost structure is known
   (per-call-dominated, not per-byte).
3. **Firm up V8's crossover**, the one soft figure left. wasmtime's is now
   nine measured points at 26.6%; V8's interpolates onto a −0.01 ns endpoint
   inside its own spread, so the data bounds it only to 70–90%. Points at
   k = 7, 8, 10 would settle it.

## Where we are

Toolchain, gate and CI verified end to end, including on a fresh clone from
GitHub. **All six S0 benchmarks are measured** — B1–B4 as contracted, plus B5
and B7 which the findings forced. Numbers, controls and what each means are in
`doc/design/0002-measure-first.md`; the verdict they add up to is `0010`.

ns per operation, against JVM Clojure:

| | JVM | V8 | wasmtime |
|---|---|---|---|
| dispatch, monomorphic (B1) | 1.50 | **0.87** (0.58×) | 8.43 (5.61×) |
| dispatch, ten types (B2) | 3.24 | **1.99** (0.61×) | 9.22 (2.84×) |
| dispatch, specialised (B5) | — | **0.73** | **2.39** |
| boxed arithmetic (B3) | 2.98 | **0.93** (0.31×) | **0.91** (0.31×) |

**B6 priced the component boundary** (2026-07-30): a 4 KB aggregate argument
costs **339 ns** to lower against **35 ns** for a linear-memory language — ~10×
— and **2544 ns** if byte payloads are held as `(array i8)` rather than
`(array i64)`. WasmGC has no array↔memory bulk copy, so lowering is a
per-element loop and the element width is the lever. `0008` licenses holding
them wide. `array.copy` does the same bytes GC-to-GC in 51 ns, so an
array↔memory instruction would be worth a further 6.6×.

Generic dispatch fails the server lane by 6×; guarded specialisation erases it,
and starts paying at 26.6% hit rate on wasmtime (nine measured points) against
roughly 80% on V8 (bounded only to 70–90%). Boxed arithmetic wins
outright on both. `ref.cast` is free by depth and expensive by width. `0004` and
`0003` both carry amendments for claims these runs falsified.

**The verdict's condition is per-site guard precision, and what a compiler
actually controls — coverage, how many sites the analysis can prove precise —
is unmeasured.** That is S0's residue and it belongs to S3.

## Blocked / needs a decision from outside

- Nothing.

## Verified

- **A fresh clone from GitHub passes `bb check`** — including the test that
  shells out to `wasm-tools`.
- **The benchmark driver fails on a wrong answer, not just a slow one**, and
  refuses an `n` whose expected value an empty loop would also produce. Both
  confirmed by breaking the benchmark on purpose.
- **wasmtime's process-slope timing is linear** over n = 5…40 M, intercept
  ~2.5 ms.
- **The WasmGC rec-group identity rule is asserted in the gate**
  (`test/cljwit/rec_group_identity_test.clj`), using `wasm-tools` only.
- **A cold-start session works, with caveats.** A fresh agent given only
  `/next` identified the project, the stage, the stop condition, that no
  compiler exists, and what to do next — and found that this file's own "Next"
  section was stale, which is why it now comes first. Re-run after any change
  to `.claude/`.

## Incidents so far

Seven, all on 2026-07-29/30 — the first two days. Each is written up where it
changed something; this is the index.

- **The gate passed locally and failed in CI** — empty `src/` and `test/` that
  git never tracked. Fixed in `bb.edn`.
- **A two-variant difference was written up as an attribution** (B1, wrong by
  50×) → `.claude/rules/measurement.md`, and the general form is now a standing
  constraint in `.claude/CLAUDE.md`.
- **The result check passed a benchmark doing no work** — the ring length
  divided `n` → prime ring lengths and `check-n!` in `bench/s0/run.clj`.
- **A stage was entered without checking what it stood on** — S0 was built
  before anyone asked whether a WasmGC module could be a component at all →
  `/survey`, argued as an exception in `doc/design/0006-*`.
- **A survey's conclusion was over-generalised from one tool path** →
  `doc/design/0007-*` carries the correction.
- **Two design conclusions drawn from one-variable experiments** — `0009`'s rec
  group and `0003`'s runtime table — both corrected in place.
- **A dev-shell convenience took CI from ~50s to ~20min.** `git` was added to
  `flake.nix` so `bb ref` would work inside the shell, and it drags perl and its
  documentation into the closure. `gitMinimal` fixed it — CI back to **58s**,
  as predicted before the change. **Nothing caught it**: the local gate reports
  its own wall time and stayed at ~1s, because the cost was entirely in
  materialising the shell. The second "green locally, different on CI"
  incident, after the untracked empty directories — and the lesson is that a
  flake change is a CI change.

**Four of the six are the same failure**: generalising from an experiment that
varied more than one thing. The rule for it was added the same day and did not
prevent the next occurrence. **What has caught it every time is an independent
reviewer with fresh context**, which is worth more than the rule.

## Deliberately not built

- **A two-tier test gate.** `bb check` takes ~1s and prints its own wall time.
- **A rule directory full of lint rules.** Rules follow incidents.
- **Anything in the compiler.** S0's verdict has to land first.
