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

Four hand-written WAT benchmarks (`bench/s0/`). No compiler, no library.

**Stop condition:** if protocol dispatch is more than ~10× slower than JVM
Clojure's, the premise "a practical Clojure on Wasm" does not hold. Either the
design changes or the scope narrows to workloads that don't dispatch (which is
a much smaller, and much less interesting, project). Say so out loud rather
than pressing on.

### S1 — `cljwit.host`: call Wasm from JVM Clojure (weeks)

A plain Clojure library. No dialect, no compiler. `require` a component as a
namespace and call it.

This exists first for three reasons: it is **useful on its own**, it forces the
**WIT ↔ Clojure type mapping** (the hardest shared problem) to be solved once,
and it makes the project real to people before the compiler exists.

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

## Open questions we know we have

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
