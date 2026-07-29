# 0004 — Dispatch design (hypothesis, tested by S0)

**Status:** proposed — **B1, B2 and B5 measured; B3, B4 outstanding**. · 2026-07-29

> **Amendment, 2026-07-29 (B1).** The prediction "protocol dispatch within 2× of
> JVM" failed on wasmtime: **5.61×** (it held on V8, at 0.58×). The cost is not
> where this note assumed, and not where the first reading of the measurement
> put it either. On wasmtime, per dispatch: the `call_ref` costs **0.13 ns**,
> going from no load to **one** load off the receiver costs **5.85 ns**, and
> going from one load to three costs **0.11 ns**. The expense is the
> **load-to-indirect-branch recurrence**, not the depth of the indirection — so
> **flattening this structure buys nothing**, and the lever is the one this note
> already proposes elsewhere: make the target statically known. Nothing below is
> retracted and the shape is unchanged. The S0 stop condition has since been
> rewritten (`doc/roadmap.md`, 2026-07-29) and under it **the server lane
> fails**: 6.08 ns of dispatch overhead against a 1 ns budget. V8 passes at
> 0.13.
> B2 has since run and is summarised below. See `doc/design/0002-measure-first.md`.

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

B1 measured this at 6.1 ns on wasmtime and 0.13 ns on V8. **The three levels are
not what costs**: collapsing them to a single funcref field on `$obj` was
measured and is worth 0.11 ns, so the structure above stands as written and the
extra word per object it would need is not worth spending. On wasmtime the whole
cost is the first load off the receiver, because the indirect branch cannot run
ahead until the target arrives. The only lever that moves it is removing the
load — that is, specializing the call site (below), not reshaping the table.

**B2 confirmed this and it was not enough.** Ten receiver types at one site cost
wasmtime +9% and JVM Clojure +114%, so the indifference is real. wasmtime is
still 2.84× JVM there, because it carries B1's monomorphic overhead unchanged.
And V8 degrades +122% — it wins B1 by speculating, so it has speculation to
lose. The advantage accrues to the engine that never had a cache.

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

  **B5 promoted this from a caveat to the load-bearing part.** A guarded
  specialised site costs 0.06 ns over a direct call on wasmtime, erasing the
  6.16 ns generic dispatch pays; at a 2-in-11 hit rate it costs 12.4 ns against
  generic dispatch's 9.2. Specialising wrongly is worse than not specialising,
  so the analysis's *precision* — not just its coverage — is what the server
  lane rests on, and the coverage report is a requirement rather than a nicety.
- **Whole-program analysis is expensive.** Start at 0-CFA; give the analysis a
  budget, and when it is exceeded, lower the specialization level rather than
  running longer. Failing conservative is always sound.
- ~~**`ref.cast` cost grows with hierarchy depth.** Keep the type graph shallow
  and wide.~~ **Falsified by B4, 2026-07-29, on both halves.** Depth is flat
  from 2 to 5 (3.669 → 3.698 ns on wasmtime), and *wide* is what a cast pays
  for: casting to a type ten others extend costs 2.80 ns where casting to the
  object's own leaf type costs 0.09, and letting ten input types reach one cast
  site adds another 2.14. V8 shows none of it.

  The guidance that replaces it: **cast to leaves, and keep the set of types
  reaching a cast site small.** Not mirroring `clojure.lang`'s 55-interface tree
  is still right, but for the opposite reason — that tree is broad, not deep.
  This is also the same lever as call-site specialisation below, seen from the
  other side: a specialised site casts to a known leaf.
- **`eval` and runtime `extend-type` break the closed-world assumption** that
  vtable coloring depends on. Handled by reserving an overflow slot and a side
  table, paid for only by builds that ask for it.

## What would falsify this

B1 showing protocol dispatch far off JVM parity. See
`doc/design/0002-measure-first.md` for the recorded predictions.

**B1 has run.** It did not falsify the design's shape, and it did falsify this
note's account of *where the cost is* (amendment above). Under the stop
condition as rewritten on 2026-07-29 it also **fails on the server lane** —
6.08 ns of overhead against a 1 ns budget — which is not a refutation of the
design but a statement that its remaining lever is now load-bearing rather than
optional.

**B2 has run.** The no-cache claim holds as a mechanism — wasmtime is nearly
indifferent to receiver count — and does not carry the server lane on its own.
Still open: B5 (does specialisation close it?), B3, B4.

B1 also raises the value of the specialization machinery under "Known weak
points": on wasmtime it is not an optimization but the only thing that moves
this number, and its coverage is therefore worth measuring rather than assuming.
