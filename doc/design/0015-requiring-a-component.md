# 0015 — Requiring a component as a namespace

**Status:** rejected · 2026-07-30 · the first draft proposed building it; an
adversarial review falsified its two load-bearing claims and the note now
records why not to build it yet

## The question

`doc/roadmap.md` states S1 as "`require` a component as a namespace and call
it", and `0014` A decided that surface is a **layer on** runtime reflection
rather than the primitive under it. `0014` delivered the primitive:
`(:echo-string i)` and `(i "local:zoo/shapes@0.3.0#take-flags")`.

The question was what the var-generating layer should look like. The answer is
that it should not be built now, and that when it is, it should be a generated
`.clj` file rather than a macro.

## The decision

**Do not build `defcomponent` yet.** Two things it was for do not exist, one
mechanism it rests on behaves backwards, and it would take the gate red.

### The arity check it existed for is not a thing Clojure does

The draft's justification was: *"`(math/add 3)` is a compile-time error rather
than an `ex-info` at run time."* Clojure has no compile-time arity check for an
ordinary var call. Measured:

```clojure
;; p/d.clj — (defn add [a b] ...), so :arglists is ([a b])
;; p/u.clj — (defn go [] (d/add 1))
$ clojure -M -e "(require 'p.u) (println :COMPILED-CLEAN)"
:COMPILED-CLEAN
```

The compiler reads `:arglists` only to select `invokePrim`; the only arity
logic is `StaticInvokeExpr.parse` under `clojure.compiler.direct-linking`,
which returns `null` on a mismatch and silently falls back to dynamic invoke.
What a generated var actually gives is an `ArityException` naming a gensym —
**worse** than `0014`'s existing `"…#take-nested takes 1 argument(s)"` with
`:cljwit/export` in `ex-data`.

### clj-kondo cannot see the vars, and the macro would fail this repo's own gate

The draft claimed "clj-kondo sees it". clj-kondo does not execute macros. On a
namespace using a `defcomponent`-interned var it reports `Unresolved symbol`
and exits 3, so `bb lint` — and therefore `bb check` — goes red on the first
use. A clj-kondo hook cannot rescue it: hooks run in sandboxed SCI with no
`slurp` and no `java.io.File`, so a hook can emit `(declare …)` to silence the
error but can never supply names or arities, because it cannot read the
`.wasm`.

### A top-level macro cannot skip itself, so it breaks the gate outside `nix develop`

`bb check` passes today outside the dev shell, because every wasmtime test does
`(if-not lib (println "…skipping"))`. A top-level `defcomponent` has no such
option — macroexpansion is not conditional — so the namespace becomes
unloadable without `CLJWIT_WASMTIME_LIB` or without the `.wasm` present. That
hard-fails `bb reflection`, which `require`s every namespace under
`src`/`dev`/`test`, and the push hook runs outside the shell. `doc/status.md`
already records "the gate is not the same gate inside and outside `nix
develop`" as an incident; this would be a sharper version of it.

### The ambient-instance design was backwards about conveyance

The draft said dynamic binding "does not convey to threads a request spawns,
which is a real limitation". It conveys to exactly the paths that matter and
not to the ones that fail safely. Measured:

| | conveys? |
|---|---|
| `future`, `pmap`, agent `send` | **yes** |
| raw `Thread`, `ExecutorService`, virtual thread | no |

And the draft's claim that `0014` D's non-concurrency check "never fires in
normal use" is the opposite of true: because `binding` *does* convey, a single
`pmap` inside `with-component` puts two calls in one store and trips
`:concurrent-use`, and then the scope exit throws again from `close`.

**A `ThreadLocal` would be the right mechanism, not a `^:dynamic` var** —
`binding-conveyor-fn` does not copy it, so `future`/`pmap`/agents see nothing
bound and get a clean "no component bound" rather than a race. That is a
one-line difference and is recorded here for whenever this is built.

### Ambience is unsafe under laziness whatever the mechanism

A lazy seq built under one instance and realised under another runs against
**the second** — no exception, plausible answer, cross-request contamination on
a pool. Neither a dynamic var nor a `ThreadLocal` prevents it. This is the
argument that survives against *any* ambient design, and the draft's falsifier
list named only the loud thread-spawn case.

### On this repo's own counterweight component, the layer would intern four vars

Against `dev/resources/zoo.wit`, after stripping the interface prefix:

| gets a var | callable today |
|---:|---:|
| 4 of 8 | **2 of 8** |

Half the vars it would intern exist only to throw `:unsupported-type`. **The
binding constraint is not naming, it is `0012`'s missing types** — which is the
opposite of what the draft assumed.

## Alternatives rejected

- **The macro, as drafted.** Above.
- **A real `require` hook** — teaching Clojure's loader that `(:require
  [some.component])` resolves to a `.wasm`. Closest to the roadmap's wording;
  needs `clojure.core/load` machinery, has no place for options, and hides the
  dependency from every tool that reads `ns` forms.
- **Instance as an explicit argument.** The draft dismissed this as reading
  "like a C API". After the conveyance measurements it is the honest option:
  handing an instance to a `future` becomes a visible, deliberate act instead
  of an invisible race. If an ergonomic layer is built before ambience is
  solved, this is the shape.

**Accepted for later, not rejected: generating a `.clj` file.** The draft
rejected it for "adding a build step" — but the macro pays that same step, as
`FileNotFoundException macroexpanding` proves, and pays it harder because
macroexpansion is not skippable. Generated source is the only shape that
delivers arity information to clj-kondo and clojure-lsp at all. It is the
natural next attempt whenever this is picked up.

## What this makes next instead

The review's sharpest point was about sequencing: **an ergonomics layer over an
API that cannot call 6 of its reference component's 8 exports is polishing the
wrong end.** What actually blocks S1:

1. `flags`, `tuple`, `own`/`borrow` marshalling — `0012` rows with no
   implementation, and the reason 6 of 8 zoo exports are uncallable.
2. **Host imports**, which `0014` names as its most-likely-to-fire falsifier
   and which WASI requires. Nothing has touched them.
3. `0012`'s `ex-data` contract, which promises a WIT type name reflection
   cannot supply. `doc/status.md` lists "the name comes from the codegen layer"
   as one of two exits — and this note is that layer declining to exist yet, so
   the other exit is the live one.

## What the first draft got wrong

Recorded rather than silently fixed, as `0012` and `0013` were.

Both of its load-bearing mechanisms were asserted from memory of how Clojure
behaves, and both were checkable in one command. `(d/add 1)` compiles clean;
`binding` conveys to `future`. Neither needed a component, a benchmark, or ten
minutes. **This is the same failure as `0013`'s — a claim about the tool rather
than the subject, never run** — and the note it produced argued for building
something on top of two facts that are not facts.

It also cited `0009` as licensing a "closed-world half". `0009` constrains the
Wasm **S3 emits**; it says nothing about a host API's compile-time openness,
and it explicitly rejects "develop on the JVM, deploy to Wasm". That citation
was decoration.

## Resources

- `0014` — the primitive this would have layered on.
- `dev/resources/zoo.wit` — the component whose 4-of-8 / 2-of-8 split is the
  argument for doing marshalling before ergonomics.
