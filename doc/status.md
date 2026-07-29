# Status

_Short by design. If this file is long, something belongs in `doc/design/` or
`doc/roadmap.md` instead._

**Updated:** 2026-07-29 · **Phase:** feasibility (pre-alpha, no compiler)

## Where we are

Repository, toolchain, gate, and CI are in place and verified end to end
(`bb check` green in under 2s; `nix develop` builds and satisfies all seven
tools — wasmtime 47.0.1, binaryen 129, wasm-tools 1.254.0). **CI is green on a
fresh Linux runner in 50s.**

**S0 has started, and B1 is measured.** The dispatch design in
`doc/design/0004-*` survives its first contact with an engine — the worst lane
is 5.6× JVM Clojure against a ~10× stop condition — but it does not survive
intact. The three headline numbers, in ns per protocol dispatch:

| | JVM Clojure | V8 (node) | wasmtime |
|---|---|---|---|
| B1 | 1.52 | **0.88** (0.58×) | **8.56** (5.62×) |

**V8 beats JVM Clojure outright** and its dispatch is free — speculative
inlining reaches us, as `doc/design/0003-*` hoped. **wasmtime pays 6.2 ns**, and
the controls say it is the *three dependent loads*, not the `call_ref`, which
costs 0.14 ns. So the lever on the server lane is the number of indirection
levels, and `wasm-opt -O3` will not find it. That is measured and written up in
`doc/design/0002-measure-first.md`; `doc/design/0004-*` carries an amendment
naming the prediction that failed.

## Next

**S0 — B2, B3, B4.** Same contract, `bench/s0/README.md`; run them with
`bb bench-s0`. Predictions for all four are already recorded in
`doc/design/0002-measure-first.md` and must not be edited.

| | Measures | Decides |
|---|---|---|
| ~~B1~~ | ~~vtable-slot protocol dispatch~~ | **done** — viable, 0.58×/5.62× |
| B2 | the same site with 10 receiver types | whether we beat the JVM where it hurts |
| B3 | `i31` inline arithmetic | whether boxed math can be cheap |
| B4 | `ref.cast` cost vs type-hierarchy depth | how to shape the type graph |

B2 is next and is the highest-value of the three: B1 showed the JVM baseline is
a *fully devirtualised* monomorphic site, which is the case protocols are
supposed to lose. B2 is where the design's actual claim lives.

Then, still open and unmeasured: **does collapsing an indirection level**
(arity array reachable straight from `$obj`, one more word per object) recover
its ~2 ns on wasmtime? Worth a fifth module once B2–B4 are in.

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
- **A cold-start session works.** A fresh agent with no prior context, given
  only `/next`, correctly identified the project, the current stage, that the
  compiler does not exist, and that the next action is to record S0 predictions
  before writing `b1_protocol.wat` — including the roadmap's warning about
  skipping S0. Re-run this check after any change to `.claude/`.

## Incidents so far

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
