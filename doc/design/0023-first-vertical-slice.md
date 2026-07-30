# 0023 — The first vertical slice: corpus harness and the representation it pins

**Status:** proposed · 2026-07-30 · adversarially reviewed the same day.
The review verified the WasmGC mechanics it attacked (nullable `ref.eq`,
`if` with a ref result, singleton identity surviving `wasm-opt -O3`) and
changed four things, marked *(review)* below: analyzer-side namespace
isolation, a JVM-message column in the trap table, the i31 rule extended
from literals to intermediates and results, and an overclaimed truthiness
example dropped.

## The question

`0022` decided S3's shape and named its first unit: a corpus harness plus
scalar entries running analyze → emit → both engines, forcing none of the
open decisions (dev-loop format, boxed-i64 lane, throw representation).
Building it still requires pinning things `0022` deliberately left to "the
first corpus entry": the concrete corpus artifacts, and the emitter's value
representation for everything the slice touches.

## The decision

### The harness (implements `0022` B, adds nothing to it)

- **`corpus/s3.edn`** — the committed corpus. An entry is
  `{:id "…" :forms [f1 … fn]}`; forms evaluate in order, the last one's
  value is the entry's result. Day-one entries are scalar (`0022` B.3).
- **`test/cljwit/corpus_test.clj`** — the differential oracle, an ordinary
  `clojure.test` so it is in `bb check` from the first entry
  (CI-mandatory, `0022` B). The oracle lane evaluates each entry in a
  fresh, discarded namespace in the test JVM — which *is* real `clojure`,
  satisfying the isolation rule without a second process. **Analysis is
  isolated the same way** *(review)*: each `analyze-forms` call gets its
  own gensym'd namespace and `create-var` interns only there, so a `def`
  in one entry can never resolve during another entry's analysis — the
  oracle-lane leak rule, one lane over. Compiled lanes: emit WAT →
  `wasm-tools parse` → `wasmtime run --invoke entry` and
  `node corpus/run.mjs`, in both `:dev` and `:prod` modes.
  `--invoke`'s printed result is a warned-experimental interface — the
  same one `bench/s0` already stands on; the pinned toolchain makes that
  acceptable, and a format change fails every entry loudly at once
  *(review)*. On a machine without the `wasmtime` CLI on PATH that one
  lane skips with a printed line — the exact trade the FFM tests already
  make with `CLJWIT_WASMTIME_LIB` — while the oracle, analysis, emission
  and the V8 lane always run; CI runs inside the flake, where nothing
  skips.
- **`corpus/trap_table.edn`** — the numbered trap↔class artifact
  (`0022` B.5). Row 1: `java.lang.ArithmeticException` ↔ the engines'
  division traps, exercised by a corpus entry in the same commit.
  A row carries the **JVM message substring, not just the class**
  *(review)*: `ArithmeticException` also means long overflow, which is
  not a division trap, and class-only matching would equate them the day
  the boxed lane lands. Stack exhaustion's row lands with the first
  depth-sensitive entry, not before (same-commit coverage, `0022` B.1).
- **Value comparison** is structural: the oracle's `pr-str` and the wasm
  lane's printed integer both go through `clojure.edn/read-string` and
  compare with `=`. An oracle value that `pr-str`→`read-string` cannot
  round-trip is a corpus lint error (`0022` B.4) — this falls out of the
  mechanism rather than being a separate linter.

### The representation (the new pins)

1. **Every Clojure value is `(ref null eq)`.** One uniform type; no
   unboxing pass in the slice. Refinement is a prod-mode optimisation
   later, per binaryen's lowering guidance already cited in `0022` A.
2. **Fixnums are `i31`** — B3's measured substrate, verbatim. A literal
   outside i31's signed 31-bit range is a **compile error** in the slice:
   that value belongs to the boxed-i64 lane, which is open (`0022` C)
   precisely because it is unmeasured. Runtime arithmetic computes in
   i64 and re-boxes through one guard; a result outside i31 hits
   `(unreachable)` — the same shape B3 measured, and the same honesty:
   the slow path exists and no corpus entry may reach it yet. The
   corresponding **corpus authoring rule covers intermediates and
   results, not just literals** *(review)*: `(quot -1073741824 -1)` or an
   overflowing left-fold would compile, run, and trap where Clojure's
   longs succeed. The harness classifies `unreachable` and cast-failure
   traps as "entry out of contract — fix the entry", so a violated
   authoring rule cannot read as a compiler divergence.
3. **nil is the null reference.** `(ref null eq)` locals default to it,
   `ref.is_null` is the nil test, and no allocation or global is spent
   on it.
4. **false and true are two singleton empty-struct globals**, distinct by
   identity (`ref.eq`), not by type. Truthiness — the thing `0022` D said
   the first corpus entry pins — is
   `(not (or (ref.is_null v) (ref.eq v $false)))`, emitted as a call to a
   runtime `$truthy`. `(if 0 …)` is therefore truthy by construction,
   matching Clojure with no special cases — and the shape extends to any
   future non-nil, non-false value, though only fixnums can demonstrate
   it while no other representation exists *(review)*.
5. **The entry protocol**: each entry compiles to one self-contained
   module exporting `entry : [] -> [i64]`, the last form's value
   unwrapped by `ref.cast (ref i31)` + `i31.get_s`. A non-fixnum result
   traps the cast — which is the mechanical form of `0022` B.3's rule
   that non-scalar entries wait for printing.
6. **Intrinsics, not a core library**: `+ - * quot <` compile from
   `:invoke`-of-`#'clojure.core/…` (the `:inline`-off contract from
   `0022` E) to runtime functions; `+ - *` fold n-ary to binary with
   Clojure's identities, `<` is binary-only for now. Any other var is a
   loud compile error naming the var.
7. **Both modes exist from day one and currently emit identical bytes.**
   `:dev`/`:prod` thread the whole pipeline and all four compiled lanes
   run, but nothing in the slice's forms diverges by mode — divergence
   starts with `def`/var emission (`0022` D). Stated here so nobody reads
   four green lanes as mode coverage that does not exist yet.

## Why

- Every mechanism above is inherited from something already measured or
  decided: the WAT→`wasm-tools`→both-engines pipeline is `bench/s0`'s,
  the i31 substrate and the `unreachable` slow path are B3's, the oracle
  contract is `0022` B, the analyzer front end is `0022` E's spike grown
  into `src/`.
- The pins themselves are chosen for *reversibility*: null-nil, singleton
  false, and `$truthy` are each one emitter function; the boxed lane can
  replace the `unreachable` arm without touching entry, corpus, or
  comparison machinery.

## Alternatives rejected

- **false as a reserved i31 value.** Free identity test, but it steals a
  value from fixnum space and makes every arithmetic fast-path guard also
  a not-false guard. The sibling's lesson that dispatch guards dominate
  makes polluting the fixnum domain the wrong default.
- **nil as a singleton struct, refs non-nullable.** Binaryen's tips favor
  non-nullable fields, but that is a prod-mode refinement; nullable
  locals need no explicit init, `ref.is_null` is one instruction, and
  nothing measured yet distinguishes them. Revisit when the optimiser
  lane exists.
- **Running compiled lanes through `cljwit.host` instead of spawning
  engines.** Tempting reuse, but corpus modules are core modules, not
  components (`0007`: components are S4's boundary), `cljwit.host` speaks
  components only, and the V8 lane has no host at all. `bench/s0`'s spawn
  mechanism is validated and already fails on wrong answers.
- **A standalone corpus runner (`bb corpus`) instead of a test in the
  gate.** `0022` B's own clause: mandatory-ness alone saved the sibling
  nothing, but an opt-in lane is strictly worse. The gate is where the
  oracle lives; a convenience runner can come later if iteration wants it.
- **Waiting for `fn`/`def` so the slice "does more".** The harness is the
  deliverable; every form it carries today has its corpus lines in the
  same commit, and `fn`'s multi-arity contract (`0022` D) is its own
  unit.

## What would falsify this

- The boxed-lane benchmark (`0022` C) pricing the i31 split out — the
  fixnum pin and the `unreachable` arm both reopen.
- A corpus entry whose truthiness the `$truthy` shape cannot express —
  none is known; finding one would mean the false-singleton choice leaks.
- The trap table failing to distinguish two JVM exception classes that
  map to one wasm trap message — expected eventually (e.g. cast failure
  vs NPE); the table gains columns or the entry gains a numbered
  divergence when it happens.
- Engine trap messages changing across pinned-toolchain upgrades — the
  table rows carry the exact strings, so this fails loudly in the gate.
