# Status

_Short by design. If this file is long, something belongs in `doc/design/` or
`doc/roadmap.md` instead._

**Updated:** 2026-07-29 · **Phase:** feasibility (pre-alpha, no compiler)

## Where we are

Repository, toolchain, gate, and CI are in place and verified end to end
(`bb check` green in 0.1s; `nix develop` builds and satisfies all seven tools —
wasmtime 47.0.1, binaryen 129, wasm-tools 1.254.0; both hooks tested including
their failure paths). **CI is green on a fresh Linux runner in 50s** — a clone
with nothing installed but Nix reaches the same gate. The design is written
down in `doc/design/` and is **entirely unmeasured**.

`wasmtime` and `binaryen` are not required yet — `tools.json` marks them
optional until S0 needs them, and `bb bench-s0` names them if they are missing.

The next thing that happens is **S0** — four hand-written WAT benchmarks that
decide whether the dispatch design in `doc/design/0004-dispatch-design.md` is
viable. Not the compiler. Not the host library. S0.

## Next

**S0 — dispatch benchmarks.** See `bench/s0/README.md` for the contract.
Four measurements, each on both V8 (`node`) and `wasmtime`:

| | Measures | Decides |
|---|---|---|
| B1 | vtable-slot protocol dispatch | whether the whole design survives |
| B2 | the same site with 10 receiver types | whether we beat the JVM where it hurts |
| B3 | `i31` inline arithmetic | whether boxed math can be cheap |
| B4 | `ref.cast` cost vs type-hierarchy depth | how to shape the type graph |

**Predictions are recorded in `doc/design/0002-measure-first.md` before the
first run.** That file is the honesty mechanism; write the numbers you expect,
then find out.

## Blocked / needs a decision from outside

- Nothing yet.

## Verified

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
