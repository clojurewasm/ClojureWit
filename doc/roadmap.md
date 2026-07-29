# Roadmap

What we are trying to do, in what order, and what would make us stop.

This file changes rarely. Day-to-day state is `doc/status.md`; decisions are
`doc/design/`.

## The goal

Make Clojure a first-class citizen of the WebAssembly component ecosystem in
both directions: Clojure programs that consume components, and Clojure
namespaces that *are* components.

Success looks like a Rust developer depending on a component, using it happily,
and only later noticing it was written in Clojure.

## Order

The order is chosen so that **each stage can kill the project cheaply**, and so
that the earliest stages produce something useful even if the later ones never
happen.

### S0 — Is the dispatch design viable? (days)

Hand-written WAT benchmarks (`bench/s0/`). No compiler, no library.

**Stop condition, rewritten 2026-07-29.** The original was "more than ~10×
slower than JVM Clojure": too loose to discriminate — B1 came in at 5.6× on the
server lane and passed a bar it should not have — and aimed at the engine's own
baseline, which is not ours to fix. A first rewrite used a *ratio* to the
engine's direct-call floor and was worse: a ratio rewards a slow floor, so the
same design passed on wasmtime and failed on V8. The condition is an **absolute
budget** instead.

> **Dispatch overhead — B1 minus B1c, same lane, same build — must be under
> 1 ns.**
>
> - Over budget on **both** lanes → the design is wrong. Change it.
> - Over budget on **one** lane → that lane is not a target. Say so out loud
>   rather than quietly shipping something slow there.

Why 1 ns: it is roughly a 2× tax on dispatch-heavy code, JVM Clojure's own
overhead is 0.08 ns, and B1c shows both engines can call directly for ~1–2 ns.
It is a judgement, not a derivation — but it is absolute, so it cannot be
gamed by slowing the baseline. The unoptimised build is the reference, because
`wasm-opt -O3` halves the control on one lane and not the other, which
`doc/design/0002-*` records as a threat to validity.

| | overhead, generic | overhead, specialised |
|---|---|---|
| V8 | 0.13 ns — passes | **0.00 ns** |
| wasmtime | ~6.1 ns — fails | **0.06 ns — passes** |

**B5 has run and both lanes pass — conditionally.** A guarded specialised site
lands on the direct-call floor on both engines. But at a 2-in-11 hit rate
wasmtime costs 12.4 ns against generic dispatch's 9.2, so **specialising a site
the analysis is wrong about is worse than leaving it alone.**

So S0's answer is: **the design is viable to exactly the extent that
whole-program analysis can tell those two cases apart.** That moves the coverage
report in `doc/design/0004-*` from a nicety to the thing the stage rests on.
**B7 then measured the crossover: roughly 25% hit rate on wasmtime against 80%
on V8** (±5 points; the 3× ratio is the robust part). A single conservative
threshold at 80% never regresses either lane and still collects 5–6 ns per
specialised site on wasmtime where it applies; per-lane builds buy back the
rest at a cost `0009` constrains. Which trade to make is an S3 decision. The
stage's verdict is `doc/design/0010-*`.

### S1 — `cljwit.host`: call Wasm from JVM Clojure (weeks)

A plain Clojure library. No dialect, no compiler.

This exists first for three reasons: it is **useful on its own**, it forces the
**WIT ↔ Clojure type mapping** (the hardest shared problem) to be solved once,
and it makes the project real to people before the compiler exists.

**Amended 2026-07-30.** This stage was stated as "`require` a component as a
namespace and call it". That surface is **deferred** (`0015`): Clojure has no
compile-time arity check for a var call, `clj-kondo` cannot see a macro's
interned vars, and a top-level macro cannot skip itself, so it would take the
gate red outside `nix develop`. When it is built it should be a generated
`.clj`, not a macro. The primitive underneath it — a reflected handle whose
exports are ordinary functions — is what S1 delivers instead (`0014`).

**Stop condition.** A component this project did not author, calling and being
called, with every WIT type a shipped release can express. Two of the three
hold: `dev/resources/zoo.wit` is the un-authored component, and `0012`'s rows
are marshalled in both directions except `map`, `list<T,N>`, `stream`/`future`
and `error-context`, none of which are in a shipped release. Host imports are
the third (`0017`).

### S2 — Developer experience skeleton (weeks)

`cljwit.edn`, an nREPL entry point, a file watcher. The compiler can still be
empty; the *shape* of using ClojureWit is worth having early, because it is
what makes the compiler adoptable when it arrives.

The model is shadow-cljs: the compiler runs on the JVM and is itself the nREPL
server, so CIDER/Calva connect with no extra machinery.

### S3 — Minimal compiler (months)

`def`, `fn`, `if`, `let`, `loop`/`recur`, calls, literals. Enough for
`(defn fib [n] ...)` to run in both a browser and `wasmtime`.

### S4 — Component boundary, both directions

`(:require ["x.wasm" :as x])` and `^{:wit/export ...}`. The Canonical ABI is
written by hand — `wit-bindgen` has no Clojure backend, and adding one is its
own project.

**Sized, 2026-07-29** (`doc/design/0007-*`): no Canonical ABI that any runtime
executes carries GC references across a component boundary, so this stage is a
lift/lower layer over **linear memory**, plus `cabi_realloc`, plus resource
handle tables. Scalar-only exports skip all of it. The sibling zwasm's
equivalent is 8,574 lines and required no core changes — bounded work rather
than a research problem, but a stage, not a detail of one.

### Running through all of it: two modes

`doc/design/0009-*` — an open world in development, a closed one in production
— is not a stage. It is a constraint on S3's output format that cannot be added
afterwards, so it is decided before S3 emits anything and revisited at every
stage that produces an artifact.

### S5 onward — decided by what S0–S4 measured

Persistent collections, the numeric tower, protocols and multimethods, the
optimization passes from `doc/design/0003-*`. Sequencing these now would be
guessing.

## What this project is not

- **Not a Clojure runtime.** [ClojureWasm](https://github.com/clojurewasm/ClojureWasm)
  is that, in this same org. It runs Clojure without a JVM and *executes* Wasm.
  ClojureWit *emits* Wasm and needs a JVM at build time only.
- **Not a ClojureScript replacement.** For DOM-heavy browser work, cljs is
  better and will stay better.
- **Not a claim to beat JVM Clojure on peak throughput.** The expected shape is
  the opposite profile: better on dispatch-heavy and boxed-arithmetic code,
  worse where the JVM can use primitives. See `doc/design/0004-*`.
- **Not a claim of a single performance multiplier.** The engine ranking
  inverts with the workload — V8 is 9.8× faster than wasmtime on B1's dispatch
  and 3.3× slower on compute-bound linear-memory code. Any one number quoted
  about this project is wrong in one of the two directions
  (`doc/design/0003-*`).

## Open questions we know we have

- **Can whole-program analysis actually reach 26% / 80% specialisation
  coverage on real Clojure?** B7 measured where specialising starts paying;
  nothing measures whether the analysis gets there. This is the condition S0's
  verdict rests on and it cannot be answered before S3.
- **What does a boundary crossing cost?** Every S0 benchmark measures dispatch
  *inside* the module. "A Rust developer calls a Clojure component" is a
  GC-to-linear-memory copy per aggregate argument, and it is unmeasured. This
  is the pitch's own claim, so it deserves a benchmark — in S0 alongside the
  other four, or at the head of S1.

- Does the engine's speculative inlining reach us on the server, where wasmtime
  has no adaptive tier? (`doc/design/0003-*`)
- Can the whole-program vtable layout survive `eval` and runtime `extend-type`?
- Is the metadata field on every object worth its word of memory?

Each of these is answered by an experiment, not by argument.
