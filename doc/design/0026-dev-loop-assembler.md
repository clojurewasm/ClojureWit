# 0026 — The dev loop assembles next to the engine

**Status:** proposed · 2026-07-30 · adversarially reviewed the same day.
The review demonstrated the decision's load-bearing assumption rather
than refuting it — cross-assembler, cross-module rec-group identity
(§3b) — and changed four things, marked *(review)*: the cold-start and
size-scaling costs stated next to the headline median, the committed
differential script with the interim "done" rule, the skew watcher named
as that same rule, and the preamble-growth falsifier with its
import-shaped exit.

## The question

`0009` made the dev-mode output format a decision that cannot be
retrofitted, and its own precondition was a cost measurement before S3
commits to one. `0022` A ran the first half — `wasm-tools parse` spawns
at ~23 ms median per form, so a 300-form namespace `require` through a
per-form dev loop is ~7 s of assembler spawns — and named four
candidates, none examined: assembling near the engine (binaryen.js /
wabt.js), a persistent assembler process, batching forms per flush, and
TeaVM's WasmGC binary writer. This note examines them and decides.

## The decision

1. **The compiler emits WAT text in both modes — one emitter core.**
   `0022` A's stated preference ("the two should share an emitter
   core"), now affordable because:
2. **The dev loop assembles in-process, next to the engine, with
   binaryen.js.** Measured 2026-07-30 (binaryen.js 131.0.0 from npm,
   node v26.3.0, Apple M4 Pro; probe in this survey's session):
   parse+emit of a real emitted module (`fn-defn-fib.dev`, 538 B binary)
   is **1.28 ms median** (min 1.19, max 2.52 over 50 in-process reps)
   against the measured 23 ms spawn — **18×** — and V8
   compile+instantiate of the result adds 0.005 ms. The 7-second
   300-form `require` becomes ~0.4 s. Two honest costs the median hides
   *(review)*: a session pays **~170 ms once** (importing binaryen.js
   ~134 ms plus a ~33 ms first assemble), and the per-form cost is
   **linear in module text** (4.0 KB → 0.92 ms, 8.7 KB → 1.94 ms) —
   consequences under "what would falsify this". V8 cannot instantiate
   text, so *something* must assemble on the engine side regardless
   (`0022` A); this makes that something the whole answer.
3. **The evidence is the whole corpus, not one module.** All 113 emitted
   corpus modules (both modes) assembled through binaryen.js agree with
   `wasm-tools`' binaries on every outcome — every value, every trap.
   The probe is committed as `corpus/devloop_differential.mjs`, and
   **re-running it is part of "done" for any change to the emitted
   grammar** until the dev lane itself is in the gate *(review — the
   interim rule; weak enforcement, honestly labeled, and the gate lane
   is the recorded stronger fix the first time drift actually happens)*.
   That differential found two silent-wrong-binary failures on the way,
   which are now constraints:
3b. **Cross-assembler, cross-module type identity holds — demonstrated,
   not assumed** *(review — it is the fact `0009`'s dev-mode heap
   sharing rests on)*: a module exporting `make : [] → (ref $Fn)` links
   into a separately assembled importer through `call_ref`, in both
   pairings (binaryen→binaryen and wasm-tools→binaryen), and
   `wasm-tools print` shows binaryen preserves the rec group verbatim.
   One caveat came with the proof: binaryen prunes unreachable types,
   so an emitted group must stay reachable or stay absent — never
   half-referenced.
4. **Feature flags are explicit — never `Features.All`, never none.**
   With no `setFeatures` call, binaryen's writer silently degrades
   `(ref null eq)` to `anyref` and the binary fails validation on the
   engine; with `Features.All`, the writer emits *exact* heap types
   (custom-descriptors, off by default in stable V8) and fails the same
   way. The dev lane sets exactly the features the emitter uses — the
   same discipline the `wat` skill already records for `wasm-opt`, now
   with its "All is as wrong as none" half.
5. **The emitter stays in the folded, non-stacky subset of WAT.**
   Binaryen's parser is an AST, not a stack machine: bare stacky
   `local.set`s (the first `recur` emission) materialize as `anyref`
   scratch locals, lose the eq refinement, and fail validation.
   `emit-recur` now rebinds through explicit temporaries. The
   constraint's mechanical pin — a binaryen.js lane in the harness —
   lands with the nREPL unit that actually wires the dev loop; until
   then this survey's 113/113, dated today, is the evidence.
6. **`wasm-tools parse` stays the prod/batch assembler and the oracle's
   reference lane.** Two independent assemblers agreeing on the corpus
   is coverage, not redundancy — the same argument the gate already
   makes for two clj-kondos.

## Why

- **wabt.js is out, checked by running it** (1.0.39, 2026-07-30): its
  parser rejects `(rec …)` at the first token — the corpus's shared
  fn substrate cannot be expressed. Second instrument, per the survey
  rule on negative claims: the Supported Proposals table in wabt's own
  README omits GC entirely
  (<https://github.com/WebAssembly/wabt>, checked 2026-07-30).
- **binaryen.js is the same project as the pinned `wasm-opt`** — one
  toolchain relationship, not a new one; the skew (npm 131 vs flake's
  129) is real and recorded below.
- The measured per-form cost sits well below any editor-latency
  convention, with the margin available for the compiler's own
  analyze/emit time.

## Alternatives rejected

- **wabt.js** — cannot parse the output (measured; above).
- **A persistent assembler process.** It amortizes the spawn cost that
  in-process assembly removes entirely, and pays for it with process
  lifecycle management in every editor session. Nothing is left for it
  to be better at.
- **TeaVM's WasmGC binary writer.** A second, JVM-side emitter core —
  exactly what `0022` A's shared-core preference exists to avoid — and
  on the wrong side of the wire: `0009`'s dev loop instantiates in the
  engine's world, so JVM-side binaries still cross to the engine, while
  WAT text crossing the wire keeps the payload readable in every
  debugging session. Unneeded at 1.3 ms.
- **An own binary writer** — already rejected with evidence in `0022` A
  (clj.wasm's recorded stall); nothing here reopens it.
- **Batching as the primary mechanism.** Still trivially available on
  top (one flush, one module), but at 1.3 ms/form nothing forces it,
  and per-form modules are what `0009`'s open world wants.

## What would falsify this

- **A text shape the emitter later needs that binaryen's parser lacks**
  — it already dictated non-stacky emission once. Surfaces mechanically
  once the dev lane is in the gate; until then,
  `corpus/devloop_differential.mjs` is the check, and running it is
  part of "done" for emitted-grammar changes.
- **The version skew biting**: npm binaryen.js and the flake's binaryen
  are different builds of one project (131 vs 129 today). `tools.json`
  gains the dev assembler's version when the nREPL unit lands; until
  then the committed differential is also the skew watcher — the same
  interim rule, named once *(review)*.
- **The margin is a function of the runtime preamble** *(review)*:
  every per-form module re-parses the whole preamble, and assembly cost
  is linear in it — a 100 KB core-library preamble puts the per-form
  cost near 20 ms and the win is gone. The exit is already the shape
  `0009`'s shared heap wants: dev-mode forms *import* the shared
  runtime instead of re-declaring it. If that lands, this note's
  numbers should be re-taken for the import-shaped module.
- **A browser measurement disagreeing with node's.** This survey
  measured node; one run characterises one path. The browser tab is the
  nREPL/browser unit's first measurement, and 1.28 ms has enough margin
  that only an order-of-magnitude surprise reopens the decision.
