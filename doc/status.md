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
| B1 | 1.50 | **0.87** (0.58×) | **8.43** (5.61×) |

**V8 beats JVM Clojure outright** and its dispatch is free — speculative
inlining reaches us, as `doc/design/0003-*` hoped. **wasmtime pays 6.1 ns**, and
four exports along a curve say exactly where it goes: the `call_ref` is 0.13 ns,
the *first* load off the receiver is **5.85 ns**, and the second and third
together are 0.11 ns. So **flattening the vtable buys nothing** — the cost is
the load-to-indirect-branch recurrence, not the depth — and the only lever is
removing the load by specializing the call site. `wasm-opt -O3` will not do it.
Written up in `doc/design/0002-measure-first.md`; `doc/design/0004-*` carries an
amendment naming the prediction that failed.

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

B2 is next and is the highest-value of the three: B1's JVM baseline is a
monomorphic site the JIT appears to devirtualise entirely, which is the case
protocols are supposed to lose. B2 is where the design's actual claim lives.

**Not worth doing:** collapsing the vtable's indirection levels. B1L measured
it at 0.11 ns. That question is closed.

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
