# 0012 — The WIT ⇄ Clojure type mapping

**Status:** proposed — **unimplemented and untested**. · 2026-07-30

## The question

`0001` says `cljwit.host` ships first partly because it "forces the WIT ↔
Clojure type mapping (the hardest shared problem) to be solved once". This is
that. The compiler will need the same mapping in the other direction, so
getting it wrong here costs twice.

The WIT value types, from `.ref/component-model` `design/mvp/Explainer.md`:
`bool`, the eight sized integers, `f32`/`f64`, `char`, `string`, `record`,
`variant`, `list<T>`, fixed-length `list<T,N>`, `tuple`, `flags`, `enum`,
`option<T>`, `result<T,E>`, `map<K,V>`, `own`/`borrow`, `stream`/`future`,
`error-context`.

## The rule that decides the easy cases

**Lowering is type-directed; lifting is not.** A call always knows the WIT
signature, so going Clojure → WIT can consult the declared type and does not
have to infer anything from the value's shape. Coming back, WIT → Clojure, the
type is equally known — so the mapping only has to be *unambiguous given the
type*, not globally injective. That is what lets `enum`, `flags` and `string`
all use keywords and strings without colliding.

## The mapping

| WIT | Clojure | notes |
|---|---|---|
| `bool` | `true` / `false` | |
| `s8 s16 s32 s64 u8 u16 u32` | integer | all fit a `long` |
| `u64` | integer, possibly `BigInt` | see divergence 2 |
| `f32` `f64` | double | `f32` is lifted widened; lowering narrows |
| `char` | `Integer` (a code point) | **not** `Character` — see divergence 3 |
| `string` | `String` | |
| `list<T>` | vector | |
| `list<T,N>` | vector | length checked on lowering |
| `tuple<A,B,…>` | vector | indistinguishable from `list` in Clojure, distinguished by the type |
| `record` | map, keyword keys | field names kebab-cased |
| `enum` | keyword | |
| `flags` | set of keywords | |
| `variant` | `[:case-name value]`, or `[:case-name]` when the case has no payload | |
| `option<T>` | value, or `nil` for `none` | see divergence 1 |
| `result<T,E>` | value on ok; **throws** `ex-info` on error | see below |
| `map<K,V>` | map | |
| `own<T>` / `borrow<T>` | an opaque handle object | see below |
| `stream<T>` / `future<T>` | not mapped in S1 | WASI 0.3; deferred |
| `error-context` | not mapped in S1 | |

## The three decisions that are not obvious

**`result<T,E>` throws rather than returning a tagged value.** A Clojure caller
writing `(resize/thumbnail bytes 128 128)` should get the thumbnail, not
`[:ok thumbnail]` to destructure. The error becomes `ex-info` with the lifted
`E` in `ex-data` under `:wit/error`, so nothing is lost and `try` is the
idiomatic handler. **Rejected:** returning `[:ok v]`/`[:err e]`, which is
lossless and honest but makes every call site unwrap and makes the common path
noisy; and returning `nil` on error, which conflates with `option`.

**Resources are opaque handles with explicit lifetime.** `own<T>` and
`borrow<T>` lift to an object the host holds, not to anything inspectable.
Clojure has no destructors, so an `own` handle is closed by `with-open` or an
explicit `close`, and dropping one on the floor leaks until the store dies.
**Rejected:** tying handle lifetime to GC finalization — finalizers are
unordered and unreliable, and Cloudflare's own write-up on `FinalizationRegistry`
is a long argument for not doing this.

**`char` is a code point, not a `Character`.** A WIT `char` is a Unicode scalar
value up to `U+10FFFF`; a Java `Character` is a 16-bit UTF-16 code unit. Half
the WIT `char` space does not fit. Using an `Integer` code point is ugly in
Clojure and correct. **Rejected:** `Character`, which silently truncates
anything above the BMP — exactly the class of failure `0008` forbids.

## Divergences from `clojure`, numbered per `0008`

These are places where a Clojure program can observe something a JVM Clojure
program could not, and `.claude/CLAUDE.md` requires them to be named rather than
left as surprises.

**Divergence 2 — `u64` above 2^63.** A `long` cannot hold it. Values that fit
lift as `long`; values above lift as `BigInt`, so the *type* of a lifted `u64`
depends on its value. **Falsifiable alternative:** always lift `u64` as
`BigInt`, which is consistent and allocates on every call for a case most
interfaces never hit. Chosen the way round that keeps the common path cheap.

**Divergence 3 — nested `option`.** `option<option<T>>` has two distinct values
that both map to `nil`: `none` and `some(none)`. The mapping is therefore not
injective for nested options and **round-tripping one is not supported**.
**Rejected:** wrapping every `some` as `[:some v]`, which is injective and makes
the overwhelmingly common single-level `option` unidiomatic. WIT interfaces
nest options rarely; if that turns out to be wrong, this is the decision to
revisit first.

## What would falsify this

**A round-trip test, and it does not exist yet.** The check this note needs is
an echo component — one exported function per WIT type, returning its argument
— driven from Clojure, asserting `(= v (echo v))` for a generated set of values
per type. Until that runs, everything above is a table someone wrote down.

Concretely it would falsify:

- any mapping that cannot round-trip, beyond the two divergences named;
- the claim that type-directed lowering resolves the ambiguity — if some type
  pair turns out to need value-shape inspection, the rule at the top is wrong;
- the `result` decision, if throwing loses error information that matters.

Building that component means writing the canonical ABI by hand for aggregates
— `memory` plus `cabi_realloc` plus lift/lower per type, which `0007` sizes at
thousands of lines in the sibling zwasm. **So this note is `proposed`, not
accepted, and should not be built on until the echo test exists.**

## Why decide it now rather than after the test

Because the test is expensive and the mapping decides its shape: what to
generate, what to compare, and which cases are expected to fail. Writing the
table first makes the test a check on a claim rather than an exploration.
