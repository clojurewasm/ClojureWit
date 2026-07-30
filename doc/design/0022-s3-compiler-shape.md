# 0022 — S3 opens: the compiler's shape, and the one decision it defers

**Status:** proposed · 2026-07-30 · the entry survey for S3, run against the
sibling projects' recorded failures, the ClojureScript compiler's source, and
the toolchain as it exists today. Three decisions are evidenced enough to
make; the analyzer choice is not, and saying so is the point.

## The question

S0's verdict (`0010`) licenses a compiler whose viability rests on per-site
guard precision; S1/S2 built the host side. S3 is `def`, `fn`, `if`, `let`,
`loop`/`recur`, calls and literals, running in both a browser and wasmtime.
What shape does that compiler take — and what does it refuse to decide
before verifying?

## The decision

### A. Emit WAT text; assemble and optimize with the pinned toolchain

The compiler prints WAT; `wasm-tools parse` assembles, `wasm-opt` optimizes
(prod only, per `0009`). Three pieces of evidence, all checked 2026-07-30:

- This repo's whole measured pipeline already is exactly that
  (`bench/s0/run.clj`, the `wat` skill) — S3 inherits validated
  infrastructure, including the GC/tail-call flag gotchas.
- **J2CL/j2wasm — a JVM-hosted compiler with the same constraints — "emits
  naïve Wasm using the text format and relies on the Binaryen toolchain"**
  (<https://github.com/google/j2cl/blob/master/docs/getting-started-j2wasm.md>).
- clj.wasm — the prior attempt at this exact project — died building its own
  assembler from the spec grammar (`.ref/clj.wasm/chronology.md`: "the spec
  document is more rendering centric than I hoped"). Never own the assembler.

No off-the-shelf JVM WasmGC-emission library was found (searched
2026-07-30); every WasmGC producer owns its writer or emits text. Binaryen's
own lowering guidance shapes the emitter: more types rather than fewer
(`--type-merging` cleans up), maximally refined and immutable fields,
vtables as immutable globals — with immutability a **prod-mode** property,
since dev-mode var globals are mutable by design (`0009`)
(<https://github.com/WebAssembly/binaryen/wiki/GC-Implementation---Lowering-Tips>).

**Unmeasured and flagged:** dev-mode REPL latency of a JVM →
`wasm-tools parse` process spawn per form. Benchmark before the nREPL unit
assumes it is fine.

### B. The differential oracle is CI-mandatory from the first special form

Semantics claims are checked against `clojure` itself — the standing rule,
mechanized:

1. A committed **corpus** of source forms; every special form lands with its
   corpus lines in the same commit, and any "X works" claim leaves its
   probing expression behind. (ClojureWasm discharged a debt row once by
   listing functions that were not done — `.ref/ClojureWasm/.dev/`; the
   corpus is the countermeasure.)
2. The oracle lane runs the corpus through real `clojure`, **batched in one
   JVM** (per-form spawning is the sibling's recorded anti-pattern),
   printing `pr-str` results or error *classes*.
3. Compiled lanes run on **both wasmtime and node, in both modes** (dev
   open-world and prod direct-linked) from day one. ClojureWasm made the
   comparison opt-in and it "consumed weeks" of silent drift
   (`.ref/ClojureWasm/.dev/decisions/0005`, `0036`); mode divergence is the
   same bug class. Early corpus entries are scalar-returning
   (`wasmtime run --invoke`, exactly `bench/s0`'s mechanism, whose driver
   already fails on wrong answers); printing arbitrary values is itself
   corpus-gated later.
4. Values compare **structurally** (read back with `clojure.edn`) — never
   string-compare set/map printing; errors compare by class. Every diff is
   classified: a bug, or a numbered divergence with a pinned test.

### C. Numerics: fixnum i31, a boxed i64 that throws, and `+` is not `+'`

fib's semantic baseline: Clojure longs. `+` **throws** on 64-bit overflow —
only `+'` promotes — which the sibling learned the hard way
(`.ref/ClojureWasm/docs/clojure_vs_clojurewasm.md`). The representation is
the measured one: i31 fixnums (B3: boxed arithmetic at 0.31× JVM on both
engines, overflow check free — `0002`), overflowing into a boxed i64 whose
own overflow throws. Promotion to bigints is `+'`'s job and out of S3 scope.
Unboxed i64 emission is a prod-mode optimisation for proven types, not the
baseline.

### D. The S3 forms, on the measured substrate

- **`def`** — dev: a mutable global, calls load it (var indirection); prod:
  direct `call` except `^:dynamic`/`^:redef` (`0004`, Clojure's own
  direct-linking rule). Declare at analysis, assign at eval: `(def x 5)
  (def x (/ 1 0)) x` is 5, and a throwing re-`def` must not wipe the old
  root (`.ref/ClojureWasm/.dev/decisions/0038` — both naive orders are
  recorded bugs).
- **`fn`** — a closure is the `$obj` header plus captured locals, per-arity
  funcref slots (`0004`, b1's measured prototype); each capture signature
  is its own struct type in its own rec group (`0009`), callee prologue
  casts to leaf (B4: 0.09 ns). **Varargs carry an explicit rest-mode** —
  rest-binding and `apply`-spreading are two operations, and inferring one
  from argument shape is a recorded correctness bug
  (`.ref/ClojureWasm/.dev/decisions/0042`); `((fn [a & xs] xs) 1 '(2 3))`
  ⇒ `((2 3))` goes in the day-one corpus. Multi-arity ships with `fn`, not
  after it, with the three JVM arity rules enforced at analysis (`0041`).
- **`if`** — truthiness is nil-or-false; the false representation (singleton
  box vs i31 encoding) is an emitter decision the first corpus entry pins.
- **`let`** — Wasm locals; captured ones become closure fields, which makes
  closure conversion an analysis pass.
- **`loop`/`recur`** — a block and a `br`; no tail-call feature needed.
  Non-self tail calls (`return_call`) are out of S3 scope, but the analyzer
  marks tail position from the start — cljs's `:context :return` is not a
  real tail-position analysis, and a `call` where `return_call` was needed
  fails only at depth (the structural-defect class
  `.ref/ClojureWasm/.dev/lessons/structural_defect_hunting.md` names: run
  everything at large n).
- **Calls** — generic three-loads-plus-`call_ref`; specialisation only above
  the measured crossover, conservative 80% threshold by default, per-lane
  builds an open S3 decision (`0010`). The coverage report is a
  deliverable: it is the number S0's verdict rests on.

### E. Deferred, with its survey named: the analyzer

The front end is the load-bearing choice and the evidence is not in yet.
What is known (read from source, 2026-07-30, paths in `.ref/clojurescript`):

- **cljs.analyzer is a fork, not a dependency.** The pipeline is
  read→analyze→emit one top-level form at a time; the AST contract
  (`:op`/`:children`) and the `parse` methods for exactly S3's forms are
  clean — but `js*` bottoms out in JS strings **71 times in `core.cljc`**,
  7 of 41 ops are JS interop, name resolution bakes in goog/node munging,
  and the tag lattice is JS-shaped. The macros are the real cost either
  way: every core macro that expands into host idioms needs a cljwit
  version.
- **tools.analyzer was designed for retargeting** (pluggable
  `macroexpand-1`/`parse`, scheduled passes — which a Wasm backend wants:
  closure conversion, boxing, tail marking). But its pass-scheduling
  specifics were **recalled, not read** in this survey, and it is not in
  `.ref/`.
- clj.wasm's author — the one person who tried this before — picked the
  cljs side (`.ref/clj.wasm/plan.md`).

**The next unit is that survey**: add `tools.analyzer` (and TeaVM, the
production JVM-hosted WasmGC emitter, reported browser-ready as of late
2025 — secondary source only) to `refs.json`, read both, and decide in an
amendment here. Deciding today on a recalled API would be `0013`'s failure
shape — a claim about the tool, never run.

## Alternatives rejected

- **Own binary emitter first.** clj.wasm's recorded death, for zero
  measured benefit at S3's scale; binary emission stays available later as
  a measured optimisation.
- **An opt-in differential oracle.** The sibling measured what opt-in
  costs: weeks of silent drift.
- **Deciding the analyzer now.** The strongest candidate's advantages are
  recalled rather than read; see E.
- **`--closed-world` in dev mode, or anywhere heap-sharing units exist** —
  already measured as a trap (`0009`, the `wat` skill).

## What would falsify this

- The JVM→`wasm-tools` per-form latency making WAT-text emission untenable
  for the REPL loop — measured before the nREPL unit, and binary emission
  is the recorded exit.
- The tools.analyzer read (E's survey) contradicting the recalled API — the
  amendment records whichever way it lands.
- A corpus entry where structural comparison cannot express the contract
  (print order, hash values) — becomes a numbered out-of-contract
  declaration, the sibling's `0122` pattern.
