# Status

_Short by design, and printed at every session start — so findings live in
`doc/design/`, plans in `doc/roadmap.md`, and only the present tense here._

**Updated:** 2026-07-29 · **Phase:** feasibility (pre-alpha, no compiler)

## Next

1. **Land `doc/design/0010-s0-verdict.md`** — S0's verdict, drafted and under
   adversarial review, currently untracked. Landing it means propagating to
   `doc/roadmap.md`, `doc/design/0004-*` and `bench/s0/README.md`, which still
   describe S0 as in progress.
2. **Record a B6 prediction in `doc/design/0002-*` before writing any of it.**
   B6 has no row in that table yet, and the table is the discipline.
3. **B6 — the component boundary crossing.** A GC-to-linear-memory copy per
   aggregate argument (`doc/design/0007-*`), which is what "a Rust developer
   calls a Clojure component" actually costs, and which no S0 benchmark
   touches. `doc/roadmap.md` places it at the head of S1.

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

Generic dispatch fails the server lane by 6×; guarded specialisation erases it,
and starts paying at ~26% hit rate on wasmtime against ~80% on V8. Boxed
arithmetic wins outright on both. `ref.cast` is free by depth and expensive by
width. `0004` lost four claims to these runs and carries the amendments.

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

Six on 2026-07-29, the day the repo started. Each is written up where it
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

**Four of the six are the same failure**: generalising from an experiment that
varied more than one thing. The rule for it was added the same day and did not
prevent the next occurrence. **What has caught it every time is an independent
reviewer with fresh context**, which is worth more than the rule.

## Deliberately not built

- **A two-tier test gate.** `bb check` takes ~1s and prints its own wall time.
- **A rule directory full of lint rules.** Rules follow incidents.
- **Anything in the compiler.** S0's verdict has to land first.
