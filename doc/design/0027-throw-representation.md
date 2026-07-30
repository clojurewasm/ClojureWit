# 0027 — The throw representation: one tag, a class payload, two lanes of precision

**Status:** proposed · 2026-07-30 · adversarially reviewed the same day,
before implementation. The review re-verified every engine claim
(traps and stack exhaustion escape `catch_all` on both engines — the
central argument holds) and changed five things, marked *(review)*:
the tested two-param-tag alternative with the real reason it loses,
the backtrace evidence the class-blind lane actually has, a divergence
numbered before it exists (`try` will not catch stack exhaustion),
the per-instance tag identity warning, and how a blind row reads in
the table.

## The question

`0022` C deferred the throw representation to its own note — an
output-format decision in `0009`'s non-retrofittable sense — and `0025`
bracketed its cost: every boxed op already pays the overflow *check*,
and the *taken* arm is still `(unreachable)`, so `(+ Long/MAX 1)` kills
the program with the wrong identity instead of throwing what `clojure`
throws. What is a thrown Clojure exception in emitted Wasm, how does an
uncaught one compare against the oracle, and what waits for `try`?

## The decision

1. **A Clojure throw is a Wasm exception** — the final exception-handling
   proposal: `tag`, `throw`, `try_table`. Verified by running on every
   leg of the pinned toolchain (2026-07-30): `wasm-tools` parses and
   validates; binaryen.js 131 assembles it (the `0026` differential
   obligation, checked before deciding); V8/node catches in-module and
   surfaces uncaught ones as `WebAssembly.Exception`; wasmtime 47.0.1
   needs **no flag**, catches in-module, and reports an uncaught one as
   "thrown Wasm exception", exit 1. The alternative — representing
   throws as traps — is not a simplification but a dead end: **a trap
   cannot be caught by `try_table`**, so the day `try` lands every
   throw site would need re-emitting; that is the non-retrofittable
   half, decided now.
2. **One tag, `$clj-exn`, with a `(ref null eq)` payload.** The payload
   is an ordinary Clojure value — which is what `ex-info` needs later —
   and today it is `(struct $Exn (field $class i32))`: a class enum,
   because no string representation exists yet, so the *message* half of
   an exception waits for strings exactly as printing waits in
   `0022` B.3. The enum is not a new artifact: trap-table rows gain an
   `:exn-class` column, keeping one numbered table for every
   error-comparison fact.
3. **Tags are nominal, and the cross-module story is named, not built:**
   two modules share `$clj-exn` only through import/export — unlike rec
   groups, there is no structural identity for tags — so dev-mode
   cross-form catching lands with the shared-runtime import shape
   (`0026`'s preamble exit, `0009`'s world). Today's corpus modules are
   self-contained, so a per-module tag is correct, not a compromise.
   One measured warning for any future runner *(review)*: tag identity
   is per-instance — `e.is()` against a byte-identical second
   instance's tag is false — so a runner in the shared-runtime world
   must hold the tag of the instance it observes, never a cached one.
4. **Two lanes of comparison precision, the hole named** (`0022` B.4):
   the V8 lane is **class-precise** — the module exports the tag and an
   `exn_class` helper, and the runner reads the payload's class back
   *through* Wasm, since GC structs are opaque to JS. The wasmtime CLI
   lane is **class-blind on exceptions** — every uncaught throw prints
   "thrown Wasm exception", so a blind row carries the same string as
   every other exception row, and that visible duplication inside the
   table is the honest rendering of the hole *(review)* — recorded
   rather than discovered later; the V8 lane carries class precision
   until `try`/`catch` makes classes observable in-program, which
   retires the hole. The lane is less blind than the string alone
   *(review)*: the backtrace names the throwing function (`!add`), and
   the hole is accepted **with that evidence left unparsed** — frame
   names are a debug section, not a contract, and would vanish under
   inlining or a names strip. Native traps (division, cast,
   `unreachable`, exhaustion) are unchanged rows — and one divergence
   is numbered before it exists *(review)*: **`try` will not catch
   stack exhaustion** — `catch_all` provably passes it through on both
   engines — where the JVM's `(catch StackOverflowError …)` works.
   Row 2's trap stays a trap even under `try`; number it the day `try`
   lands.
5. **The overflow arms throw, this commit:** `$add`/`$sub`/`$mul`'s
   taken arms become `throw $clj-exn` with class 1
   (`java.lang.ArithmeticException`, jvm-message "long overflow"), and
   `(+ 9223372036854775807 1)` finally enters the corpus — with its
   entries in the same commit, per `0022` B.1.

## Why

- Every claim above about engine behaviour was produced by running the
  probe this session (scratchpad `eh_probe.wat`): catch-in-module
  returns the payload's class on both engines; uncaught behaviour and
  its exact strings are as quoted; binaryen.js round-trips it.
- The single-tag shape mirrors the JVM itself: one `athrow`, class as
  data. It also keeps `try` emission single-handler when it lands.
- Deciding *now* costs one struct type and one tag in the runtime;
  deciding *later* costs re-emitting every overflow arm and every
  future throw site under whatever `try` needs.

## Alternatives rejected

- **Traps for Clojure throws.** Uncatchable by `try_table`; would make
  `try` a whole-program rewrite. The reason this note exists.
- **A (kind, payload) multi-value entry protocol** to give the wasmtime
  CLI lane class precision on uncaught exceptions. Machinery for a
  failure class nobody has seen, priced at touching every entry in both
  runners; superseded anyway when `try` lands and classes become
  observable in-program. The named hole plus V8-lane precision covers
  the interim.
- **The class as a second tag parameter** — `(tag (param i32
  (ref null eq)))`, the class directly readable by the runner via
  `getArg`, no helper export. **Tested, and it works** *(review)*; it
  loses anyway, for the reason that actually decides this note: the
  tag signature is the durable cross-module ABI (§3 — nominal,
  import/export, forever), while the `exn_class` helper is disposable
  corpus scaffolding. Baking a harness convenience into the permanent
  ABI is the wrong trade, and the JVM analogy stays exact: one
  `athrow`, one operand.
- **One tag per exception class.** Tag explosion, cross-module identity
  per class, and `try_table` needing a handler row per class — for
  information that is data, not control flow.
- **Reading the payload from JS via reflection.** GC structs are opaque
  to JS by design; the helper-through-wasm is the supported path, not a
  workaround.

## What would falsify this

- **The cost of a taken throw is unmeasured** — only the untaken check
  is priced (B8). The throw/catch benchmark belongs before the `try`
  emitter work, where catch frequency becomes a real workload; a cold
  throw on an error path has no budget to violate.
- **An engine changing its default for exception handling** — the
  pinned versions carry the evidence; re-verify on any toolchain bump
  (`/survey`'s three-month rule).
- **The class enum outgrowing the table before strings land** — if the
  enum needs more than the JVM classes the corpus actually compares,
  that is the signal to pull strings forward rather than grow the enum.
