# 0022 — S3 opens: the compiler's shape, and the one decision it defers

**Status:** proposed · 2026-07-30 · rewritten twice the day it was written.
The first draft made three decisions and deferred the analyzer; the survey
ran the same day and decided it (E — tools.analyzer core plus our own host
layer, `bb spike-analyzer`). Then an adversarial review ran every claim:
three of six citation spot-checks failed (recorded as an incident), A's
dev-loop half was reopened for violating `0009`'s own precondition, and C's
headline was retracted — only the fast path was ever measured. What stands
and what is open are marked per section.

## The question

S0's verdict (`0010`) licenses a compiler whose viability rests on per-site
guard precision; S1/S2 built the host side. S3 is `def`, `fn`, `if`, `let`,
`loop`/`recur`, calls and literals, running in both a browser and wasmtime.
What shape does that compiler take — and what does it refuse to decide
before verifying?

## The decision

### A. Prod/batch emission is WAT text; the dev loop's format is **open**

**What stands:** for batch builds the compiler prints WAT; `wasm-tools
parse` assembles, `wasm-opt` optimizes (prod only, per `0009`). Evidence:

- This repo's whole measured pipeline already is exactly that
  (`bench/s0/run.clj`, the `wat` skill) — S3 inherits validated
  infrastructure, including the GC/tail-call flag gotchas. That evidence is
  for **batch** builds — the driver memoizes across a build — and licenses
  nothing about the per-form loop.
- J2CL/j2wasm, a JVM-hosted compiler under the same constraints, routes a
  `.wat` output into binaryen in its build rule
  (`build_defs/internal_do_not_use/j2wasm_application.bzl` in
  <https://github.com/google/j2cl>, checked 2026-07-30). *(The first draft
  carried a direct "quotation" of j2wasm's docs that its cited page does
  not contain — caught by review; see the incident.)*
- clj.wasm — the prior attempt at this exact question — **stopped** at
  building a spec-derived assembler (`.ref/clj.wasm/plan.md:15`: "the spec
  document is more rendering centric than I hoped"; last commits
  2024-12-09 say the extractor "works on all files", then nothing). One
  paused side project is weak evidence, but the direction is the same:
  never own the assembler.

Binaryen's lowering guidance shapes the emitter: more types rather than
fewer (`--type-merging` cleans up), maximally refined and immutable fields,
vtables as immutable globals — with immutability a **prod-mode** property,
since dev-mode var globals are mutable by design (`0009`)
(<https://github.com/WebAssembly/binaryen/wiki/GC-Implementation---Lowering-Tips>).

**What is open — reopened by review: the dev loop.** `0009` (accepted)
closes with the dev-mode cost benchmark belonging **before S3 commits to an
output format**, and the first draft deferred exactly that. The review ran
the three-minute half: `wasm-tools parse` on a real S0 module is **~23 ms
median per spawn** (12–51 ms, 20 reps, Darwin arm64), `wasmtime` spawn+run
another ~22 ms. Fine per keystroke; multiplied where forms multiply — a
300-form namespace `require` through a per-form dev loop is ~7 s of
assembler spawns alone, and B's corpus lane would add ~1 min to the gate.
The candidates the decision must weigh, none yet examined: assembling
**near the engine** (binaryen.js/wabt.js on the node/browser side — V8
cannot instantiate text anyway, so something assembles there regardless), a
**persistent assembler process**, batching forms per flush, and **TeaVM's
WasmGC binary writer** (JVM-hosted, Apache-2.0, named in E — the first
draft's "no off-the-shelf JVM WasmGC-emission library" overclaimed against
its own section E). The dev-loop format is decided by that benchmark,
before the nREPL unit, not here. *(Decided 2026-07-30: `0026` — WAT text
in both modes, assembled next to the engine by binaryen.js at 1.28 ms per
form; wabt.js measured unable to parse rec groups.)*

### B. The differential oracle is CI-mandatory from the first special form

Semantics claims are checked against `clojure` itself — the standing rule,
mechanized:

1. A committed **corpus** of source forms; every special form lands with
   its corpus lines in the same commit, and any "X works" claim leaves its
   probing expression behind. **This clause is the load-bearing one**: the
   sibling's drift recurred *even after* its oracle became CI-mandatory,
   because coverage lagged — 9 of 24 node kinds compared
   (`.ref/ClojureWasm/.dev/decisions/0005`, `0036`). Mandatory-ness alone
   saved nothing; same-commit coverage is what does.
2. The oracle lane runs the corpus through real `clojure` in one JVM,
   **with a stated isolation rule**: each entry evaluates in a fresh
   namespace, and cross-entry effects are banned by a corpus lint — a
   `def` leaking from entry N into N+1 would make the oracle resolve what
   the per-entry compiled lanes cannot, and the diff would blame the
   compiler.
3. Compiled lanes run on **both wasmtime and node, in both modes** (dev
   open-world and prod direct-linked) from day one; mode divergence is the
   same bug class as backend divergence. Day-one entries are
   scalar-returning (`wasmtime run --invoke`, exactly `bench/s0`'s
   mechanism, whose driver already fails on wrong answers); an entry whose
   expected value is not a scalar — D's varargs probe returns `((2 3))` —
   **enters the corpus the day printing does**, and until then D's claims
   about it are marked untested. (The first draft demanded both
   scalar-only day one and that entry on day one; a review caught the
   contradiction.)
4. Values compare **structurally** (read back with `clojure.edn`), with
   the holes named in the contract rather than discovered: a fn-valued
   result prints as `#object[…]` and **crashes** `clojure.edn/read-string`
   — such entries are corpus-lint errors until a representation exists —
   and NaN fails `=` even against itself, so float comparisons go through
   bit patterns (`0012` already records the same rule for the WIT
   boundary). Set/map print order is never string-compared.
5. Errors compare through a **trap↔class mapping table, a numbered
   artifact from the first corpus entry**: a wasm trap has no exception
   class, so `(/ 1 0)` — `ArithmeticException` on the JVM, a bare division
   trap or thrown exnref on the wasm side — is only comparable once the
   table says so. Its first rows: `ArithmeticException`, and stack
   exhaustion (see D's depth bands). Every diff is classified: a bug, or
   a numbered divergence with a pinned test.

### C. Numerics: fixnum i31, a boxed i64 that throws, and `+` is not `+'`

fib's semantic baseline: Clojure longs. `+` **throws** on 64-bit overflow —
only `+'` promotes — verified by execution in this repo's `clojure`, and
the sibling learned it the hard way
(`.ref/ClojureWasm/docs/clojure_vs_clojurewasm.md`).

**The fast path is the measured one — and only it.** The first draft said
"the representation is the measured one"; a review read the benchmark:
B3's slow path is literally `(unreachable)` (`bench/s0/b3_arith.wat:25`),
its "boxed" type is a two-i32 struct, and its overflow branch never fires
at B3's inputs. What B3 licenses is i31 fixnums at 0.31× JVM with a
perfectly-predicted untaken guard. The boxed-i64 lane — allocation per
overflow-lane add, mixed-representation dispatch on both operands, the
i64 overflow check, the throw — is **unmeasured**, and it is not a
corner: fib's values leave i31 at fib(46), so the stop-condition
program's input domain n = 46…92 runs entirely on it. Per `0002`:
predictions in writing, then the benchmark, **before the numeric
emitter**.

Three decisions this forces, none made here:

- **Canonicalization** — may a boxed value that fits i31 exist? Either
  answer changes `=`, `hash`, and every numeric guard's cost model.
- **The throw representation** — a WasmGC exception with what tag,
  caught how, compared by B's trap table how. An output-format decision
  in `0009`'s non-retrofittable sense; its own note, before the emitter.
- **Divergence #1, numbered here**: i31 makes every small integer
  `identical?`-true, where the JVM's Long cache stops at 127 (measured:
  `(identical? 128 128)` is false in this repo's `clojure`). Semantics
  are not negotiable *unremarked*; this is the remark, and the corpus
  pins it.

Promotion to bigints is `+'`'s job and out of S3 scope. Unboxed i64
emission is a prod-mode optimisation for proven types, not the baseline.

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
- **`loop`/`recur`** — a block and a `br`; no tail-call feature in S3,
  **and the honest reason is parity, not absence of need**: JVM Clojure
  has no TCO either (measured: plain self-recursion survives 10⁴ frames
  and dies at 10⁶). But the lanes die at different depths — measured on a
  minimal recursive function: wasmtime traps between 30k and 40k frames,
  V8 throws `RangeError` between 10k and 20k, the JVM sits an order of
  magnitude higher — and real compiled frames will be fatter. So the
  corpus rule this forces: depth-sensitive entries pin a depth **below
  the shallowest lane's band**, and stack exhaustion is a row in B's trap
  table. "Run everything at large n" (the sibling's structural-defect
  lesson) still holds for everything *else*. Tail-position marking lands
  **with its consumer** (`return_call`, post-S3) — the first draft
  claimed it "from the start", which was a claim with no probing
  expression.
- **Calls** — generic three-loads-plus-`call_ref`; specialisation only above
  the measured crossover, conservative 80% threshold by default, per-lane
  builds an open S3 decision (`0010`). The coverage report is a
  deliverable: it is the number S0's verdict rests on.

### E. The analyzer: tools.analyzer core, plus our own host layer

**Amended 2026-07-30, same day: the survey ran, and it decides.** Everything
below the rule is the original deferral, kept because its reasoning shaped
the survey. What the survey found (all read from source or executed;
`bb spike-analyzer` is the committed, re-runnable half):

- **tools.analyzer core is 827 lines, maintained (4466d93, 2026-02-15), and
  host-agnostic by contract**: four dynamic vars (`macroexpand-1`, `parse`,
  `create-var`, `var?` — `analyzer.clj:129-148`). Its `-parse` natively
  handles every S3 form, already enforces the three JVM arity rules and the
  declare-before-init `def` ordering that D requires, and its pass
  scheduler (`passes.clj`: `:walk`/`:depends` metadata, same-walk fusion)
  is the natural home for closure conversion, tail marking and boxing —
  `collect-closed-overs` already exists.
- **The host layer's cost is measured by example**: tools.analyzer.jvm is
  3,172 lines, of which **58% is JVM reflection and class resolution that a
  Wasm target simply does not have**; ~450 lines are the generic-host
  pattern to adapt (ns map, macroexpansion, the Gilardi `do`-unrolling) and
  ~680 are passes cljwit would write under *any* route.
- **clojure.core's own macros are reusable directly — measured, with a
  two-entry leak table.** With `:inline` **off** (the one deliberate
  divergence from t.a.jvm's `macroexpand-1`), `defn`, `and`, `or`, `when`,
  `->`, `loop` and vector destructuring expand into pure special forms and
  core-var calls; `(+ x 1)` stays an `:invoke` of `#'clojure.core/+`, which
  keeps `+`-vs-`+'` in cljwit's hands (C). The leaks: map destructuring's
  kwargs branch touches `PersistentArrayMap` (needs a shim), and chunked
  `doseq` (out of S3 scope). The host ops (`:host-call` etc.) double as
  **free leak detectors**: any future macro that expands into interop fails
  analysis mechanically.
- **The spike ran, in this repo**: `bb spike-analyzer` analyzes
  `(defn fib [n] …)` through bare tools.analyzer 1.2.2 with a ~40-line
  host binding — top op `:def`, recursive `fib` resolves mid-parse, and
  the distinct op set contains **zero host ops**.
- The cljs fork's real cost is now counted: `core.cljc` defines 166
  macros, and the S3-relevant ones it *re-implements* — `destructure`
  (~140 lines), `let`, `loop`, `fn`, `defn`, `and`, `or` — are exactly
  what the tools.analyzer route defers until a corpus line actually leaks.
  TeaVM, read as the third emitter, independently confirms `0004`'s
  three-loads-plus-`call_ref` vtable shape
  (`WasmGCVirtualCallGenerator.java:56-79`).

**Falsified by:** map-based pseudo-vars failing in passes (the spike
interned real JVM vars; the next spike swaps in a map-returning
`create-var` — the contract allows it, but it was not run); the leak table
growing past shim scale once the corpus passes S3 (`case*`'s JVM hashing,
`binding`, `lazy-seq` — if cljwit ends up owning ~20+ core macros, the
cljs fork's macro layer stops being a differentiator and this reopens); a
macro capturing a non-EDN JVM object into an expansion (mechanically
detectable: a `:const` whose `:val` does not print); the scheduler proving
unable to express the closure-conversion ordering.

---

*The original deferral, for the record:*

The front end is the load-bearing choice and the evidence was not in yet.
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

**The next unit is that survey** — which is the amendment above: read
both, run the spike, decide. Deciding on a recalled API would have been
`0013`'s failure shape — a claim about the tool, never run.

## Alternatives rejected

- **Own binary emitter first.** clj.wasm's recorded death, for zero
  measured benefit at S3's scale; binary emission stays available later as
  a measured optimisation.
- **An opt-in differential oracle.** The sibling measured what opt-in
  costs: weeks of silent drift.
- **Deciding the analyzer on recall** (the note's own first draft, for a
  few hours). The survey replaced recall with reads and a spike; see E.
- **Forking cljs.analyzer.** Owning `destructure`/`let`/`loop`/`fn`/`defn`
  from day one, for parse methods tools.analyzer also has; its 71 `js*`
  bottoms in `core.cljc` are the JS target's, not ours.
- **A purpose-built analyzer for the S3 subset.** Re-implements 827 lines
  of subtle, maintained validation against the standing prefer-the-
  ecosystem constraint.
- **`--closed-world` in dev mode, or anywhere heap-sharing units exist** —
  already measured as a trap (`0009`, the `wat` skill).

## What would falsify this

- The dev-loop benchmark (A) landing where no candidate is acceptable —
  would reopen prod emission too, since the two should share an emitter
  core.
- The boxed-lane benchmark (C) pricing mixed-representation dispatch above
  what erases B3's win — would reopen the fixnum split itself.
- The tools.analyzer read (E's survey) contradicting the recalled API — the
  amendment records whichever way it lands.
- A corpus entry where structural comparison cannot express the contract
  (print order, hash values) — becomes a numbered out-of-contract
  declaration, the sibling's `0122` pattern.
