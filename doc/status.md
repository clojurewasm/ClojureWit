# Status

_Short by design, and printed at every session start — so findings live in
`doc/design/`, plans in `doc/roadmap.md`, and only the present tense here._

**Updated:** 2026-07-30 · **Phase:** S1 closed, S2 half-built, S3 entered (pre-alpha, no compiler yet)

## Next

1. **S1 is closed with two named residues** (`doc/roadmap.md` records them):
   the calling side is exercised only by repo-authored guests — a real
   third-party component reopens it — and a resource nested in a container
   in an import's signature is refused at instantiate (`:nested-resource`)
   rather than marshalled.
2. **S2 — developer experience skeleton** (`doc/roadmap.md`). Its first
   unit is done: **`cljwit.edn` exists** (`0021`, `cljwit.project`) —
   components keyed by namespace, `sync!` plans-then-writes, `status`
   reports tiered drift (`:stale`/`:modified`/`:missing`/`:orphans`), and
   `check` is the CI verb. What remains of S2 — the nREPL entry point and
   the watcher — waits for a compiler to serve, so:
3. **Every S3 form now compiles and is oracle-checked** (`0023` the
   harness, `0024` fn): `corpus/s3.edn` — 45 entries: literals, `if`,
   `let`, `loop`/`recur`, `def`, `fn` with multi-arity, closures,
   method-`recur`, higher-order calls — runs on every `bb check` through
   real `clojure` (fresh namespace per entry) and through
   `cljwit.analyze` → `cljwit.emit` → `wasm-tools` → **wasmtime and V8,
   in both modes**. `(defn fib [n] …) (fib 20)` returns 6765 on both
   engines — the roadmap's S3 sentence, now including a real browser
   page (`corpus/browser.html`, below).
   **The modes now genuinely diverge**: `:prod` direct-links fn defs
   (immutable constant global + plain `call`, `^:dynamic`/`^:redef`
   excluded, re-`def` refused), `:dev` keeps var indirection; the
   dev-only redef entry carries `:modes [:dev]` (`0024` §10). The trap
   table has two exercised rows (division, stack exhaustion — depth
   bands re-measured for the real frame shape, pin at 3,000). Varargs
   analyze but refuse to emit until seqs exist. **B8 is measured**
   (predictions first, both recorded in `0002`; decision `0025`): the
   boxed-i64 lane costs 1.38 ns/op on V8 and 1.99 on wasmtime against
   JVM boxed 2.96 in the same run — fib's n = 46…92 domain needs no
   rescue — the fixnum split survived its falsifier (a real slow path
   costs B3's fast path +3–5%), and **fixnums are canonical** (a boxed
   value fitting i31 must not exist; the probe measured free). Still
   open: the dev-loop output format (before the nREPL unit) and the
   throw representation (its check is priced, its taken arm is not).
   **The boxed lane is in the emitter** (57 corpus entries): longs
   beyond i31 compile, fixnums canonicalize through `$box`, `quot`
   wraps at MIN∕−1 like the JVM, and the corpus runs the stop-condition
   domain iteratively — fib(46) and fib(91); the 92 entry died at the
   oracle's hands because that loop shape computes one step ahead, and
   the corpus comment records it. **The dev-loop format is decided**
   (`0026`, surveyed by running): WAT text in both modes, one emitter
   core; the dev loop assembles next to the engine with binaryen.js at
   **1.28 ms/form** (18× the spawn path; ~170 ms once per session;
   cost linear in the preamble, exit named). wabt.js measured unable
   to parse rec groups. The review demonstrated cross-assembler
   rec-group identity — the fact `0009`'s heap sharing rests on — and
   two silent-wrong-binary traps are now constraints: explicit feature
   flags (never `Features.All`), non-stacky emission.
   `corpus/devloop_differential.mjs` (113/113) is part of "done" for
   emitted-grammar changes until the dev lane is in the gate.
   **The throw representation is decided and implemented** (`0027`):
   a Clojure throw is a Wasm exception — one nominal tag, a class-enum
   payload (`$Exn`; message waits for strings), overflow arms throw
   `ArithmeticException`/"long overflow" (row 3, all three ops in the
   corpus), the V8 lane class-precise, the wasmtime CLI lane
   class-blind by a named hole, and traps stay uncatchable — including
   stack exhaustion, a divergence numbered before `try` exists. **Every
   S3 design decision is now closed, and the stop condition's
   "browser" is no longer a figure of speech**: `corpus/browser.html`
   ran in a real Chrome tab — `fn-defn-fib` printed `result 6765` and
   `throw-add-overflow` printed `exn 1`, the same vocabulary as the
   node runner (2026-07-30; serve the repo root, the page header says
   how). **Dev-mode linking is built and oracle-checked** (`0028`):
   one runtime module per session, one module per form importing
   `"rt"` and the session vars it reads, exporting the ones it defs —
   the corpus's third compiled lane runs every entry as a real session
   (form 2 reads form 1's `def`, fn values cross modules, a
   cross-module throw classifies through the imported tag). The review
   caught a real blocker before it shipped: per-module K broke
   cross-form fn values, so linked K is a session constant, 20 —
   Clojure's own ceiling (`0028` §2a, `0024` amended). The wasmtime
   linked lane is deferred on cost, not capability (`--preload` works;
   recorded). Next unit: **S2's nREPL entry point** — the shadow-cljs
   shape from `doc/roadmap.md`, every prerequisite now decided and
   the session runner already the shape the server drives.

Done since the last update: `0016` `own<T>` handles, `0017` host imports
(A–F), `0018` host-defined resources, `0012`'s `ex-data` contract shrunk to
what reflection can supply, `0019` libwasmtime resolution for library users,
`0020` generated namespaces (`cljwit.host.gen` — one tagged var per export,
instance first, `host/unwrap` as the opt-in result sugar), `host/describe`,
the reflection delete contracts the headers require, and `0023` — the
compiler's first two namespaces exist and every claim they make is checked
against `clojure` itself on every `bb check`. What is left of
`0012` is `map`, `list<T,N>`, `stream`/`future` and `error-context`, none of
which a shipped release can express.

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
and starts paying at 26.6% hit rate on wasmtime against 80% on V8, each now
bracketed by adjacent measured points. Boxed arithmetic wins
outright on both. `ref.cast` is free by depth and expensive by width. `0004` and
`0003` both carry amendments for claims these runs falsified.

**The verdict's condition is per-site guard precision, and what a compiler
actually controls — coverage, how many sites the analysis can prove precise —
is unmeasured.** That is S0's residue and it belongs to S3.

### S1 — reaching a component

**`cljwit.host` is delivered** (`src/cljwit/host.clj`, `0014`). Three
lifetimes — engine, compiled artifact, instance — with exports discovered from
the component itself: no WIT file and no code generation at run time. Names
are the exact WIT strings, including `pkg:name/iface@ver#func` for functions
inside an interface, with keyword aliases only where the name reads back equal
to itself. Every entry into a store takes a non-concurrency check, and results
are lifted eagerly. It marshals **every `0012` row a shipped release can
express, in both directions**: exports and host imports (`0017`), resources in
both ownerships — guest-defined handles as `AutoCloseable` (`0016`),
host-defined resources with destructors (`0018`) — and WASI, deny-by-default
(`0017` E). The one named gap is a resource nested in a container in an
import's signature, refused at instantiate. `0019` gives library users a
libwasmtime resolution order that does not require this repo's flake.

**The calling convention is decided and the cost structure is measured**
(`0011`, corrected by `0013`): `MethodHandleProxies/asInterfaceInstance` behind
a `definterface` gives a static call site at **7.4 ns** against
`invokeWithArguments`' 396, so **`cljwit.host` can be pure Clojure** — no
bytecode generation, no C shim needed for the *binding*. A scalar component
call costs **~0.39 µs**, and every lane has a control on the other side of the
boundary:

| entry point | Rust | C | JVM/FFM |
|---|---:|---:|---:|
| core, typed / `func_call_unchecked` | 15.2 | 15.7 | 46.1 |
| core, untyped `Val` / `func_call` | 58.7 | 74.1 | 102.5 |
| component, typed | 279.4 | *none* | *none* |
| component, untyped `Val` | 309.4 | 352.9 | 392.8 |

- **wasmtime's floor is 15 ns**, agreed to 2% by Rust and C independently.
- **The JVM adds a flat ~27–31 ns**, not a multiple. The binding is not worth
  optimising, which `0011` had right for the wrong reason.
- **The Component Model is ~79% of a component call** — 4.8× a core call
  measured entirely within C, not the ~20% `0011` claimed. **Typing it back
  recovers only ~10%**, so the cost is the Canonical ABI, not the `Val` boxing.
  279 ns is the floor with the best API wasmtime offers, against 15 ns for a
  core call.
- **`wasmtime_func_call_unchecked` *is* the fast path** — 2.2× faster from the
  JVM, 4.9× from C, exactly as its header says. `0011`'s "1.7–3.0× slower" was
  an artifact.
- **`wasmtime_component_func_post_return` is deprecated and a no-op** in 47.0.1.

A Rust shim would buy ~10–20% and is closed as a speed argument (`0013`); the
segfault-safety argument for one is separate and still open.

**Against B6 this inverts the emphasis:** for payloads under ~30 KB the call
dominates the copy.

**S1's premise is verified end to end** (`0011`): `bb spike-host` builds a
component and calls it from the JVM through FFM — engine, store, component,
linker, instantiate, export lookup, call — returning 42. The flake now exports
`CLJWIT_WASMTIME_LIB` so no committed file carries a machine-specific path,
and `0019` settled how a shipped library finds the shared object without it.

Behind that (`0005`, surveyed 2026-07-30): wasmtime's C API
gained the component model between v40 and v43 — 0 exported
`wasmtime_component_*` symbols at 40.0.2, 154 at the pinned 47.0.1 — and the
JVM resolves them through `java.lang.foreign` on Java 25. `tools.json`'s ≥43
minimum, set for WASI 0.3, is also the component minimum; do not lower it.

## Blocked / needs a decision from outside

- Nothing.

## Verified

- **A fresh clone passes `bb check` and reproduces every spike** — re-checked
  2026-07-30 after S1 added ten WIT/WAT artifacts, two of them `deps/`
  directories that git could have dropped: 29 tests, 212 assertions, 5.6 s,
  and `spike-reflect`, `spike-import` and `spike-hres` all print what their
  design notes quote. This is the constraint that has failed here before —
  empty directories git never tracked.
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

Fifteen, all on 2026-07-29/30 — the first two days. Each is written up where it
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
- **The gate is not the same gate inside and outside `nix develop`.** A
  macro-introduced binding linted clean under the flake's pinned clj-kondo and
  failed under the ambient one. The push hook runs outside the shell and caught
  it; **CI runs inside and would have missed it.** Left as-is rather than
  pinned harder — two clj-kondos disagreeing is more coverage than one, and the
  hook is the one that blocks. Recorded so the asymmetry is a decision.
- **`git add -A` committed a design note the commit was not about.**
  `doc/design/0012` went in with a benchmark commit whose message never
  mentions it, minutes before a review found it wrong in most of its hard
  cases. Corrected in place rather than reverted, with what it got wrong
  recorded in the note. The lesson is to stage by path when a working tree
  holds unrelated work.
- **A dev-shell convenience took CI from ~50s to ~20min.** `git` was added to
  `flake.nix` so `bb ref` would work inside the shell, and it drags perl and its
  documentation into the closure. `gitMinimal` fixed it — CI back to **58s**,
  as predicted before the change. **Nothing caught it**: the local gate reports
  its own wall time and stayed at ~1s, because the cost was entirely in
  materialising the shell. The second "green locally, different on CI"
  incident, after the untracked empty directories — and the lesson is that a
  flake change is a CI change.

- **A reflective `MemorySegment.get` became the headline number of two design
  notes.** `(def ^:private I32 ValueLayout/JAVA_INT)` with no type hint made
  the result read in a benchmark loop resolve reflectively, at ~1470 ns a
  call — so `0011` attributed ~1.57 µs to wasmtime and `0013` built a
  seven-row elimination table on top of it. Both retracted. Mechanized: `bb
  reflection` is in the gate and found eleven more sites. `clj-kondo` does not
  catch it and `*warn-on-reflection*` was off everywhere.
- **`wasmtime_component_val_delete` on a result aborts the JVM on the *second*
  call.** Every single call succeeded and each of fourteen exports passed when
  tested alone. Recorded in `0014` E.
- **The host silently dropped every function a real WASI world has.**
  `instantiate` walked only top-level exports, so interface functions did not
  appear and nothing said so. `0014`'s own retrospective had predicted exactly
  this shape of failure about itself.
- **`Instance/close` leaked the store, twice, one level apart.** Fixed once by
  reordering the in-call check; the same shape survived in the handle loop
  inside the guarded block and was found by a review.
- **A truncated view of a source, read as the whole — three times.** Two greps
  whose `[a-z_]` class silently excluded digits (`tuple_type_types_count`,
  `VALTYPE_S8`, `add_wasip2`), and a typedef whose return type was one line
  above the match, which cost a JVM crash and two turns of looking in the wrong
  place. `/survey` carries both halves now.
- **A design note asserted a mechanism that does not exist.** `0018`'s first
  draft called it measured that a reflected valtype compares equal to a
  host-registered resource type. It compares 0. Deferred, then rewritten.
- **A delegated research brief's quotation went into a design note
  unverified.** `0022`'s first draft carried a direct "quote" of j2wasm's
  docs that the cited page does not contain, a wrong-file attribution, and
  an unfindable "recorded anti-pattern" claim — three of six citation
  spot-checks failed in adversarial review. The same family as the
  truncated-view incidents: a summary read as the source. Corrected in
  `0022`'s second rewrite; the rule was already on the books ("re-verified
  before it is built on again") and did not prevent it — the independent
  reviewer did, again.

**Five of the fifteen are one failure**: generalising from an experiment that
varied more than one thing. **Three more are its sibling** — reading a
truncated view of a source and treating it as the whole. Rules were added for
both the same day they appeared and neither prevented the next occurrence.
**What has caught them every time is an independent reviewer with fresh
context**, which is worth more than the rules.

## Deliberately not built

- **A two-tier test gate.** `bb check` takes ~1s and prints its own wall time.
- **A rule directory full of lint rules.** Rules follow incidents.
- **Anything in the compiler.** S0's verdict has to land first.
