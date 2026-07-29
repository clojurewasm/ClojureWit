# 0004 — Dispatch design (hypothesis, tested by S0)

**Status:** proposed — **B1 measured, B2–B4 outstanding**. · 2026-07-29

> **Amendment, 2026-07-29 (B1).** The prediction "protocol dispatch within 2× of
> JVM" failed on wasmtime: **5.62×** (it held on V8, at 0.58×). The cost is not
> where this note assumed. "Three loads and an indirect call" is accurate as a
> description, but the note treats the two as one cost; they are not close to
> equal. On wasmtime the indirect call is **0.14 ns** and the three loads are
> **6.0 ns**. See `doc/design/0002-measure-first.md` for the numbers and the
> controls that separate them. Nothing below is retracted — the shape is
> unchanged and the S0 stop condition is not tripped — but **the number of
> indirection levels, not the indirect call, is the lever on wasmtime**, and
> B2–B4 have not run.

Clojure is a dispatch-heavy language, and dispatch is where a Wasm port most
plausibly fails. This note states the design and the reasoning; `bench/s0/`
tests it.

## The premise: JVM Clojure's dispatch machinery is not Clojure

Reading `clojure.lang` and the ClojureScript compiler side by side shows the
same semantics implemented two completely different ways.

A protocol call `(nth coll i)`:

- **JVM** emits a two-level cache — a per-call-site static field holding the
  last seen `Class`, an `instanceof` fast path to a generated interface, and on
  a miss a `MethodImplCache` with a monomorphic entry plus a hash table.
- **ClojureScript** emits `obj.cljs$core$IIndexed$_nth$arity$2(null, i)` — a
  direct property call. No cache, no class compare, no var deref.

The JVM machinery exists because **you cannot add a method to an existing Java
class**; `extend-type` on `String` cannot add a vtable slot to `String`. The
caches are an adaptation to that constraint, not part of the language.

Surveying all of Clojure's dispatch sites, roughly half are of this kind:
mechanism inherited from the host, not semantics. WasmGC lets us choose a third
mechanism.

Supporting scale: `clojure.lang` declares **55 interfaces**; `IFn` declares
**22** `invoke` overloads; `Numbers.java` is **4,242 lines**, much of it the
double dispatch behind `(+ a b)`; `Reflector.java` is **709 lines** and exists
only because of Java interop — a category that **does not exist here at all**,
since host calls go through statically typed WIT.

## The design

**Two-layer dispatch.** WasmGC struct fields are fixed in index and type, and a
vtable pointer costs a word on every object — which is 50% overhead on a cons
cell. So:

- **built-in types** (~15: cons, vector, map, set, keyword, symbol, string,
  fixnum, …) dispatch via a `br_on_cast` chain ordered by frequency. No vtable
  word.
- **user types and protocols** dispatch via a vtable slot, because their number
  is open and a chain would be linear.

**Vtable slots assigned by whole-program selector coloring.** Protocols are
open, but compiling the whole program makes every (protocol, method, arity)
triple known at build time. Classic selector coloring gives a dense table.

**One vtable array per arity**, so the element type is already correct and no
`ref.cast` is needed at the call site:

```wat
(type $vt2 (array (ref null $fn2)))
(type $vtables (struct (field $a1 (ref $vt1)) (field $a2 (ref $vt2)) ...))
(type $obj (sub (struct (field $hash (mut i32)) (field $vt (ref $vtables)))))
```

Cost: three loads and an indirect call. No hashing, no comparison, **no cache —
which means no cache to thrash** when a site is megamorphic. That is the
predicted advantage over the JVM (B2).

B1 measured the three loads at 6.0 ns on wasmtime and ~0 on V8, against 0.14 ns
for the indirect call on either. Whether collapsing a level — reaching the
arity array straight from `$obj`, at the cost of one word per object per arity —
recovers a proportional share is **not measured**, and is the first thing to
measure if the wasmtime lane turns out to matter.

**Arithmetic inlined on the `i31` path.** `br_on_cast_fail` both operands to a
slow path; on the fast path, two `i31.get_s` and an `i32.add` with an overflow
check. No call. The JVM's equivalent is two eight-way class chains plus two
virtual calls.

**Var indirection removed** where the var is neither `^:dynamic` nor
`^:redef` — the same exclusion rule Clojure's own direct-linking uses, so no
new vocabulary is needed.

## Known weak points

- **Defunctionalization is not sound for dynamically typed languages** in
  general: arity mismatches can occur at runtime. So a call site is only
  specialized when both the target set is finite *and* every candidate's arity
  matches. Otherwise it stays generic — and the coverage report says so.
- **Whole-program analysis is expensive.** Start at 0-CFA; give the analysis a
  budget, and when it is exceeded, lower the specialization level rather than
  running longer. Failing conservative is always sound.
- **`ref.cast` cost grows with hierarchy depth** in typical engine
  implementations. Keep the type graph shallow and wide — which means *not*
  mirroring `clojure.lang`'s 55-interface tree. B4 measures whether this
  matters as much as assumed.
- **`eval` and runtime `extend-type` break the closed-world assumption** that
  vtable coloring depends on. Handled by reserving an overflow slot and a side
  table, paid for only by builds that ask for it.

## What would falsify this

B1 showing protocol dispatch far off JVM parity. See
`doc/design/0002-measure-first.md` for the recorded predictions.

**B1 has run and did not falsify it** — the worst lane is 5.6× against a ~10×
stop condition. Still open: B2 (does the no-cache claim actually beat the JVM
when a site is megamorphic?), B3, B4.
