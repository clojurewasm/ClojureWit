# 0009 — Two modes: dynamic in development, static in production

**Status:** accepted · 2026-07-29

## The question

`0004` assumes a closed world: whole-program selector colouring, direct-linked
vars, specialised call sites. Clojure development assumes an open one: a REPL
that redefines a function under a running program, `eval`, runtime
`extend-type`.

`0004` treats the open world as an edge case — "reserving an overflow slot and
a side table, paid for only by builds that ask for it". That is the right
mechanism and the wrong priority. Retrofitting interactive development onto a
finished static compiler is the expensive order, and it is the order most
compilers accidentally choose.

## The decision

**Two modes, designed together from the start.**

| | development | production |
|---|---|---|
| world | open — redefinition, `eval`, `extend-type` at runtime | closed |
| vars | indirect | direct-linked, except `^:dynamic` / `^:redef` |
| protocol dispatch | vtable + overflow slot + side table | coloured vtable, specialised where the target set is finite |
| optimisation | whatever survives an open world | everything in `0003`'s L1 |
| what it is for | the edit-evaluate loop | shipping |

**Dynamism is lost at the production boundary, deliberately.** Most shipped
applications never need it, and paying for it in every deployed artifact to
serve a development-time need is the wrong trade. What is *not* acceptable is
losing it during development.

Both modes are the same compiler with a different world assumption, not two
compilers. The alternatives section says why.

## Why this is possible at all — measured, 2026-07-29

Interactive development in Wasm has an obvious-looking blocker: you cannot add
a function to a running module. You instantiate a *new* module. So the question
is whether a module compiled at 3pm can operate on objects allocated by a
module compiled at 2pm — because if it cannot, there is no REPL, only a
restart.

It can. Two modules, separately compiled, never linked to each other, each
declaring the same `(rec …)` group independently:

| module | declares | `ref.test` on the first module's object |
|---|---|---|
| A | `(rec (type $node (sub (struct i32 i32))))` | — allocates it |
| B | the identical rec group | **1** — and `struct.get` reads it correctly |
| C | same fields plus one more | 0 |
| D | the same struct, inside a rec group with another type added | **0** |

**Type identity is the canonicalised rec group plus the index within it.**
Structure, not linkage — so a later-compiled unit shares the heap by
re-declaring the prelude verbatim.

On **wasmtime** the same unification holds, and needs no embedder program:
`wasmtime run --preload a=A.wasm --invoke run B.wasm` instantiates two
separately compiled files into one store and unifies them by rec group. What
remains untested there is narrower — a module *compiled* after the store has
been running, which is the literal REPL case and which `--preload` does not
model.

D is the sharp edge: adding *any* type to a group changes the identity of
*every* type in it. Groups are independent of each other, though — a unit that
defines new types in *new* groups leaves earlier ones intact. So the rule is
not "one frozen group" but:

> **Minimise what shares a group.** Only genuinely mutually recursive types may
> share one. Everything else — every user `deftype`, every later addition —
> gets its own group referring to the shared ones.

`test/cljwit/rec_group_identity_test.clj` pins all of this, including the
mechanism the rule depends on: a `deftype` in its own group referring to the
core group unifies across units, and a unit that later defines *more* groups
still sees the earlier types.

**The cost this exposes.** `0004:67-71` puts one `$fnN`/`$vtN` pair per arity
*inside* the mutually recursive core group. By the rule above, **adding one
supported arity changes the identity of every type in that group** — which
invalidates every object allocated by every previously compiled unit. So the
core group's arity set is frozen at prelude-version time, and **units built by
different cljwit versions cannot share a heap.** That is tolerable within one
application and collides directly with `doc/roadmap.md`'s pitch of shipping
components others depend on — except that `0007` already forbids GC references
from crossing a component boundary at all. Heap sharing is therefore always
*within* one linked artifact, never between separately shipped ones, and the
version constraint lands where it can be met.

**"Identical" means canonical form, not bytes.** The `(rec …)` wrapper is not
load-bearing for a singleton, and type-section order does not matter — but
mutability, `final` versus open, a declared supertype, and *the index within
the group* all are, and identity is transitive: a group whose own text is
unchanged gets a new identity if a group it references changed. There is no
graceful degradation, only a silent 0.

**And the production optimizer will break it if allowed to.** Measured
2026-07-29: `wasm-opt --closed-world -O2` (and `-O3`, and `--gufa`) folds a
`ref.test` against a shared type to `i32.const 0` — *when that type does not
appear in an exported signature*, so nothing tells Binaryen the type comes from
outside. That is correct under a closed world and catastrophic for a unit
sharing a heap, and it fails as a wrong answer rather than an error. It maps
cleanly onto the two modes: **`--closed-world` is a production-mode flag and
must never be applied to a unit that shares a heap with separately compiled
code.** `.claude/skills/wat/SKILL.md` carries the operational form.

These are constraints on the compiler's output format. They are cheap to
satisfy and impossible to retrofit once units are in the wild — which is the
whole argument of this note in miniature.

## Prior art

Every mature system in this shape runs two modes, and the ones that added the
second one late paid for it:

- **Clojure itself.** Direct linking is off by default and on for release
  builds (`clojure.compiler.direct-linking`), with `^:dynamic` and `^:redef` as
  the escape hatches. `0004` already borrows that exclusion rule, so the
  vocabulary exists and no new concept is needed.
- **Dart.** JIT in development for hot reload, AOT for production — and
  *different compilers* for the web (`dartdevc` for dev, `dart2js`/`dart2wasm`
  for release). The split is real, and Flutter's long-standing "debug mode is
  slow" complaint is what it costs when the dev mode is a different machine.
- **ClojureScript / shadow-cljs.** `:dev` versus `:release` with `:advanced`;
  `:static-fns` off in development precisely so redefinition works.

## Alternatives rejected

- **Ship one mode, the static one, and add a REPL later.** The default path,
  and the one the user's constraint is aimed at. Rejected because the frozen
  rec group above shows the cost is not additive: an output-format decision
  made for the static compiler alone is one that a later REPL cannot undo.
- **Develop on the JVM, deploy to Wasm.** Tempting — the compiler is already a
  JVM program (`0005`), so evaluate there and skip Wasm during development.
  Rejected: it makes the development environment a *different implementation*
  from the deployed one, which is precisely the divergence `0008` says is not
  negotiable. It is also how you get "works at the REPL, fails in production"
  as a permanent condition. The JVM's role stays what `0005` gave it — hosting
  the compiler, not running the user's program.
- **Keep the open world in production too**, and rely on the engine to
  optimise. Rejected on measurement: `doc/design/0002-*` B1 shows wasmtime has
  no adaptive tier and pays ~6 ns for a dispatch it cannot see through. On the
  server lane the closed world is not an optimisation, it is the only lever
  there is.
- **Two separate compilers**, as Dart does for the web. Rejected for now: the
  divergence risk above applies to compilers as much as to hosts, and this
  project has one author's worth of capacity. Revisit if the single compiler's
  mode flag starts producing genuinely different pipelines rather than
  different assumptions.

## What would falsify this

- **Runtime instantiation against a live store behaves differently from
  what `--preload` models on wasmtime** — a module compiled *after* the store
  has been allocating is the literal REPL case and is untested.
- **Browser realms.** Every V8 result is from one Node isolate. Whether a
  module compiled in a Web Worker shares a type registry with the main thread
  is untested, and a browser REPL may well be structured that way.
- **`wasm-opt` passes beyond the ones isolated above.** `--type-ssa`,
  `--monomorphize` and the rest of `-O4` are unchecked against cross-unit type
  identity, and at least `--gufa` is closed-world-flavoured enough that its
  absence from a production pipeline should not be assumed.

The note's original largest risk — that a REPL-defined `deftype` could not be
expressed in a separate group referring to the frozen one — **is closed**: it
works on both lanes and is asserted in `test/cljwit/rec_group_identity_test.clj`.
- **The dev mode's cost turns out to be unacceptable** — if an open-world
  build is so much slower that development stops being interactive, the
  "different machine" objection to a JVM dev host loses its force.
- Either of these is a benchmark, not an argument. Neither is scheduled yet;
  both belong before S3 commits to an output format.
