# 0024 — fn in the slice: closures, arities, and the first real mode divergence

**Status:** proposed · 2026-07-30 · adversarially reviewed the same day,
before implementation finished. The review verified the mechanics it
attacked (constant `struct.new` globals, cross-rec-group subtyping,
distinguishable null/cast traps, `RangeError` on V8 exhaustion, the three
arity rules enforced by bare tools.analyzer, safe SOE recovery in the
oracle) and changed four things, marked *(review)*: the depth pin's
rationale re-measured for the real frame shape, row 2's class-only match
made explicit, K's definition extended to call sites, and a per-mode
corpus annotation for behaviour only one mode can express.

## The question

`0022` D contracts what `fn` must do (multi-arity ships with it, varargs
carry an explicit rest-mode, no tail calls, the depth bands). It does not
pin how a closure is laid out in WasmGC, how a call site reaches the code,
or where `:dev` and `:prod` emission first produce different bytes. Those
are this note's decisions — the first ones `0023`'s harness can check
mode-divergently, since until now both modes emitted identical modules.

## The decision

1. **Function signatures.** `$sigN` is
   `(func (param (ref eq)) (param (ref null eq))×N (result (ref null eq)))`
   — parameter 0 is the closure itself, through which the body reads its
   captures. Uniform boxing per `0023`; unboxed signatures are a prod
   optimisation for later, not the baseline (`0022` C).
2. **`$Fn` is a base struct with one nullable typed-function-ref field per
   arity**, 0..K where K is the module's maximum arity **over both method
   definitions and fn-value call sites** *(review)* — a higher-order site
   calling arity N > any defined method must find a null slot and take
   the classified out-of-contract trap, not emit a `struct.get` on a
   field that does not exist. Per-arity slots are `0004`'s measured
   shape — collapsing them to one funcref was measured there and
   rejected for 0.11 ns; the structure stands.
3. **Each capture signature is its own subtype of `$Fn`, in its own rec
   group** (`0009`'s identity rule: unit-private types must not perturb
   shared groups). The callee's prologue casts parameter 0 to its leaf
   type once into a local (B4: casts are cheap by depth), and capture
   reads are `struct.get` on that local. Captures are by value, which is
   exact — Clojure has no `set!` on locals, so there is nothing mutable
   to alias.
4. **Free variables are computed on uniquified names.**
   `clojure.tools.analyzer.passes.uniquify` is now scheduled inside
   `analyze-forms` — the pass-scheduler home `0022` E predicted for
   closure conversion. Partial shadowing
   (`(let [x 1] (fn [] (+ x (let [x 2] x))))`) makes name-based free-var
   computation wrong without it.
5. **Multi-arity is slots in one struct; a missing arity is a null slot.**
   Calling it traps on the null `call_ref` — reported by the harness as
   out-of-contract until an `ArityException` representation exists (the
   throw representation is open, `0022` C). The three JVM arity rules are
   enforced at analysis where tools.analyzer sees the fn literal.
6. **Varargs analyze but refuse to emit.** `:variadic?` methods are a
   loud out-of-slice error: the rest parameter is a seq, no seq
   representation exists, and `0022` D's rest-mode contract stays marked
   untested — its corpus entry enters the day printing does (`0022` B.3).
7. **`recur` in a fn method rebinds the method's argument parameters**
   (never parameter 0, the closure) — the method's `:loop-id` registers
   them exactly as `loop` registers its bindings, same simultaneous
   stack-swap emission.
8. **Calls.** The generic path is `0004`'s measured shape — reach the fn
   value (local, global, or fresh), `ref.cast (ref $Fn)`, `struct.get`
   the arity-N slot, `call_ref $sigN` — for every call whose target is a
   fn value. **The first mode divergence**: a top-level
   `(def f (fn …))` whose var is neither `^:dynamic` nor `^:redef`
   compiles in `:prod` to an **immutable global holding a constant
   `struct.new`**, and every call through the var to a **direct `call`**
   of the method — Clojure's own direct-linking rule and exclusion list
   (`0004`). In `:dev` the var stays a mutable `(ref null eq)` global
   assigned at eval, and calls go through the generic path — the var
   indirection `0009`'s open world requires. Re-`def` of a direct-linked
   fn in `:prod` is a loud refusal (`^:redef` is how you ask for
   redefinability, same as on the JVM).
9. **Stack depth.** No tail calls, for parity (`0022` D). *(review — the
   bands re-measured for this note's actual frame shape, because `0022`
   D's 30–40k/10–20k numbers were for a minimal function and it predicted
   fatter frames itself)*: with the self param, prologue cast, slot load
   and `call_ref`, **wasmtime exhausts at ~8–10k, V8 at ~10.3–10.7k, and
   the oracle JVM itself at ~8.5–9.2k** for an eval'd recursive fn on the
   default test-JVM stack — the oracle is a band like any other lane.
   Depth-sensitive entries therefore pin **3,000 frames**, ~2.8× under
   the shallowest measured band. One entry exhausts the stack on purpose
   and adds **trap-table row 2**: `java.lang.StackOverflowError` — whose
   message is null, so **this row matches on class alone**, a recorded
   weakening of the table's jvm-message guard *(review)*; SOE also
   arrives *unwrapped* by `eval`, so the CompilerException unwrap is
   correctly a no-op here — ↔ wasmtime's "call stack exhausted" ↔ V8's
   `RangeError` ("Maximum call stack size exceeded"), which is *not* a
   `WebAssembly.RuntimeError`, so the V8 runner learns to classify
   `RangeError` as a trap.
10. **Per-mode corpus entries** *(review)*. `:prod` refuses fn re-`def`
    (point 8), so the dev-mode behaviour it exists to preserve —
    redefinition taking effect — could never be exercised by entries
    that run in both modes. A corpus entry may carry `:modes [:dev]`
    (default: both); the fn-redef entry is dev-only by construction,
    and the `^:redef` entry covers both modes.

## Why

- Every load-bearing shape is inherited from a measured decision: the
  per-arity slots and the direct-linking exclusion rule from `0004`, the
  rec-group isolation from `0009`, cheap leaf casts from B4, boxed
  signatures from `0023`/`0022` C, the depth bands from `0022` D's
  measurements.
- The divergence point (calls through vars, not `def` itself) is where
  Clojure's own direct linking draws it, so no new vocabulary or caveat
  list is invented — including its known behaviour that a direct-linked
  fn ignores redefinition, which here is a refusal instead of a silent
  staleness.

## Alternatives rejected

- **One funcref field plus arity dispatch in the callee.** `0004`
  measured the per-arity table against this and kept the table; an
  arity switch on every call buys one word per closure.
- **Reusing `0004`'s full `$obj`/vtable-array layout now.** That layer
  exists for protocols and open user types (selector coloring over the
  whole program). A fn value's callee set is closed at creation; slots
  suffice. The two converge when protocols land, not before.
- **A universal closure type with an `(ref null eq)` capture array.**
  Loses typed capture reads, adds a bounds-checked load per capture, and
  `0009` already decided per-signature types for sharing reasons.
- **Implementing varargs against an ad-hoc pair/array type.** Every rest
  argument would be re-done when seqs land; `0022` D's rest-mode
  contract is about *marking*, which analysis already preserves.
- **`return_call` for self-recursion now.** The toolchain supports it,
  but parity with the JVM's missing TCO is the deliberate baseline
  (`0022` D); tail-position marking lands with its consumer, post-S3.
- **Direct-linking in `:dev` too.** Kills redefinition, which is the
  entire point of the dev mode's open world (`0009`).

## What would falsify this

- A measured prod-vs-dev call-cost gap too small to justify two paths —
  nothing here is a performance *claim*; the benchmark named by `0010`
  (coverage/guard precision) arrives with specialisation, and if direct
  linking buys nothing measurable the prod path simplifies to the
  generic one.
- The null-slot arity trap colliding with a future `ArityException`
  row — expected; resolving it is part of the throw-representation
  decision (`0022` C), and the trap table gains the distinction then.
- A capture set the uniquified-name computation gets wrong — one corpus
  entry exists specifically to catch partial shadowing.
- `struct.new` with `ref.func` failing as a constant expression on
  either engine — checked at validation, would force prod fns back to
  start-time assignment (a smaller divergence, same call-site shape).
