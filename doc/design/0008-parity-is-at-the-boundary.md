# 0008 — Parity is at the boundary; the internals are not Clojure

**Status:** accepted · 2026-07-29

## The question

`.claude/CLAUDE.md` says "semantics are not negotiable for speed". That is the
prohibition. It has never stated the permission, and the permission is the more
useful half — without it, every design argument drifts toward reimplementing
`clojure.lang`, because that is the only implementation anyone can point at.

## The decision

**The contract is the observable behaviour of a Clojure program. Everything
underneath it is ours to choose.**

If a program cannot tell the difference, there is no difference. Pick whatever
emits good Wasm and is simple.

"Observable" means: **run the same source under `clojure` and under cljwit and
compare what comes out** — return values, printed output, side effects and
their order, and which exceptions escape. Everything else is implementation.
The word is deliberately not "a conforming program": there is no conformance
document to appeal to, and inventing one would let any inconvenient difference
be defined away. Where we cannot match, the answer is a numbered divergence —
see below — never a narrower definition of "program".

This is not a licence to approximate. It is a statement about *where* the
obligation lives: at the in/out boundary of a program, not at the shape of the
runtime that implements it.

## What this permits

- **Not mirroring `clojure.lang`.** Its 55 interfaces, `IFn`'s 22 `invoke`
  overloads, and `Numbers.java`'s 4,242 lines of double dispatch are one host's
  answer to one host's constraints. `0004` already argued this for dispatch;
  this note generalises it, because the same argument keeps having to be
  re-made for collections, for the numeric tower, and for vars.
- **Closed-world specialisation.** Whole-program analysis and monomorphising a
  call site are invisible, so they are free. **Direct-linking a var is not** —
  it is observable, through redefinition, which is why Clojure ships it off by
  default. It belongs to the divergence below, not to this list.
- **Different data representations.** A vector need not be a 32-way trie
  because JVM Clojure's is, as long as its observable complexity and behaviour
  hold.

## What this does not permit

The boundary is wide, and these are on the far side of it. A program *can* tell:

- **The numeric tower.** Overflow promoting to `BigInt`, `Ratio` arithmetic,
  `==` versus `=`, integer division. All observable, all fixed.
- **Laziness, including its edges.** Chunking size is observable through side
  effects, and programs depend on it.
- **Identity, metadata, and `=` / `hash` agreement.** Including the guarantee
  that equal values hash equally.
- **Ordering guarantees**, where Clojure makes one — and the absence of one
  where it does not, which is the trickier half.
- **Error behaviour** that programs catch and dispatch on.
- **`print`/`read` round-tripping.**
- **Hash *values*, not just `=`/`hash` agreement.** They leak through
  `(hash x)` and through the iteration order of unsorted maps and sets, and
  libraries embed them.
- **Concurrency and the reference types.** `atom` and `swap!` — whose CAS
  retry means the update function may run more than once, which side effects
  observe — plus `volatile!`, `ref`/STM, `agent`, `future`, `promise`, `delay`,
  `deref` with a timeout, and the happens-before guarantees underneath them.
  Wasm's baseline is single-threaded, so making `future` synchronous is an
  observable change and a source of deadlocks that do not occur on the JVM.
  **This is the largest unsolved item on this list** and it needs its own note
  before S3 touches it.
- **Dynamic binding conveyance.** `binding` is thread-local and `future` /
  `send` convey the frame. Tied to the previous item: what a binding frame
  *is* with no threads is undecided.
- **`type` / `class` and printed type names**, in a runtime with no Java
  interop at all (`0004`).

## Divergence #1 — production mode is not dynamic

`0009` compiles production builds under a closed world, which removes `eval`,
`resolve`, `find-var`, `intern`, `ns-publics`, `alter-var-root`, runtime
`extend-type`, and var redefinition. **That is an observable difference from
`clojure`, and `.claude/CLAUDE.md` requires it to be a numbered, documented
divergence rather than an unremarked one.** This is that number.

Its shape:

- **Scope:** production builds only. Development builds keep the open world,
  which is the whole point of `0009`.
- **Supported subset:** vars marked `^:dynamic` or `^:redef` keep their
  indirection, matching Clojure's own direct-linking exclusions.
- **Not mitigated by the oracle.** Differential testing against `clojure`
  cannot cover this, because production mode differs from `clojure` on exactly
  these programs *by construction*. The mitigation is that the divergence is
  bounded, opt-out per var, and absent from the mode people develop in.

Recording it here rather than in `0009` because this is the note that defines
the boundary, and a divergence is a hole in a boundary.

## Why

The oracle is `clojure` itself, and it is an oracle for *behaviour*, not for
implementation. This is what makes a differential test the right instrument:
run the same program both ways, compare what came out. A test that asserted our
protocol dispatch "uses a cache like JVM Clojure does" would be testing the
wrong thing, and would forbid exactly the freedom this project needs.

It also settles an argument that would otherwise recur once per subsystem. When
S3 reaches collections, the question "how close to `PersistentVector` must this
be?" has an answer already: as close as a program can observe, and no closer.

## Alternatives rejected

- **Port `clojure.lang` faithfully and optimise later.** The most obvious
  route, and the one `0005` half-follows by reusing ClojureScript's analyzer.
  Rejected for the runtime: `clojure.lang`'s shape is an adaptation to "you
  cannot add a method to `java.lang.String`", a constraint that does not exist
  here. Porting it would import the workaround and pay for it forever.
  Reusing the *analyzer* is different — that is a frontend, and its output is
  semantics, not mechanism.
- **Define parity as passing Clojure's own test suite.** Necessary, not
  sufficient, and misleading as a target: that suite tests behaviour *and*
  internals in places, and passing it would neither prove conformance nor be
  required for it.
- **Leave it implicit.** It was implicit until now, and `0004` had to spend a
  section re-deriving it for dispatch alone.

## What would falsify this

A behaviour that a reasonable Clojure program depends on, that this note's
"not permitted" list does not cover, discovered late enough that the internal
choice is already load-bearing. That is a real risk — the list was already
extended once, on review, by five items including the whole concurrency
surface — and the mitigation is that **differential testing against `clojure`
starts at S3 and is not deferred**. The list is a summary of the boundary, not
its definition. The definition is the oracle.
