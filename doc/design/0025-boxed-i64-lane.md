# 0025 — The boxed-i64 lane: measured, licensed, and canonical fixnums

**Status:** proposed · 2026-07-30

## The question

`0022` C decided the numeric fast path (i31) and left the slow lane —
where fib's whole n = 46…92 input domain lives — unmeasured, with three
decisions explicitly deferred to its benchmark: whether the lane's cost
reopens the fixnum split, whether a boxed value that fits i31 may exist
(canonicalization), and the throw representation. B8 ran
(`doc/design/0002`, predictions first, `bench/s0/b8_boxed.wat`). This
note takes the two decisions the numbers now support and says why the
third stays open.

## The decision

1. **The boxed representation is `(struct (field i64))`, and the lane is
   licensed as the baseline for longs outside i31.** Measured (B8k):
   1.377 ns/op on V8, 1.993 on wasmtime, against the JVM's boxed 2.964 in
   the same run — the slow lane *beats JVM boxed arithmetic on both
   engines*. No rescue design (unboxing passes, escape analysis) is
   needed for S3; unboxed i64 emission stays what `0022` C said it was, a
   prod optimisation for proven types, later.
2. **The fixnum split stands — its named falsifier did not fire.** B8i
   put a real, allocating, canonicalizing slow path under B3's fast
   path: **+~3% on V8, reproduced across two runs; indistinguishable on
   wasmtime**, where the first run's +5% inverted its sign on a rerun —
   inside the ±0.05 spread `0002`'s own resolution standard names
   *(adversarial review, same day — the first draft quoted +5% as
   measured)*. Mixed-representation dispatch on both operands is
   +0.04 ns on V8 (noise) and +0.51 on wasmtime (B8b−B8k).
3. **Fixnums are canonical: a boxed value that fits i31 must not exist.**
   Every producer of a boxed result re-boxes through one guard —
   fits-i31 → `ref.i31`, else `struct.new`. Cost, measured before
   deciding (B8c−B8b): 0.00 ns on V8, +0.19 on wasmtime — so the
   decision is made on semantics, where it is one-sided:
   - `identical?` on small integers stays exactly divergence #1 as
     `0022` C numbered it — *all* i31-range integers are
     `identical?`-true — instead of "depends on which arithmetic
     produced the value", which would be a new, unnumberable divergence.
   - `=` and `hash` on the dominant integer range stay `ref.eq` and a
     tag read, with no boxed-vs-i31 equality case to get wrong.
   - Every `ref.test (ref i31)` guard in emitted code keeps meaning
     "is this a small integer", not "is this a small integer that
     happened to arrive by a fast route".
4. **The throw representation stays open, now with its cost bracketed.**
   The overflow *check* is on every boxed add and costs what B8k already
   includes; the *taken* arm is still `(unreachable)` and no corpus entry
   may reach it. What changes today: the emitter's `$box` guard grows the
   boxed arm (`struct.new` instead of `unreachable`), so literals and
   arithmetic beyond i31 become expressible — with true 64-bit overflow
   the remaining out-of-contract edge until the throw note lands.

## Why

- Every number above comes from one command
  (`bb bench-s0 B3 B8k B8b B8c B8i`, 2026-07-30, Apple M4 Pro, pinned
  toolchain), with the predictions recorded first in `0002` — which is
  also the record of what the predictions got wrong: the lane was
  5–15× cheaper than predicted, and the engine asymmetry was overstated.
- Canonicalization was the one decision that could have been made by
  cost and turned out free enough to make by meaning. The reverse order
  — decide semantics first, hope the cost is fine — is what `0002`
  exists to prevent.

## Alternatives rejected

- **No canonicalization** (boxed small ints may exist). Saves the +0.19
  ns probe on wasmtime's boxed lane and nothing on V8; buys a
  representation-dependent `identical?`, a two-case `=`/`hash` on the
  hottest value range, and guards whose meaning depends on data history.
- **A pair-of-i32 box.** B3's placeholder shape; one i64 field is what
  the arithmetic loads and stores, and there is no 32-bit host to
  appease.
- **Unboxing/escape-analysis machinery in S3.** The lane it would
  rescue already beats the JVM baseline; machinery without an incident
  (`0006`).
- **Deciding the throw representation here.** B8 measured the untaken
  check only; a taken overflow's cost and the trap-table row it needs
  (`ArithmeticException` "long overflow" — which the table's
  jvm-message mechanism exists to distinguish) belong to the throw
  note, with `try`/`catch` semantics in view.

## What would falsify this

- **A survivor-heavy workload repricing allocation.** B8's boxes die one
  iteration after birth — the friendliest GC load. Boxed longs *at rest*
  (collections of them) exercise the collector instead, and are
  unmeasured; if that repricing is severe it does not reopen fixnums,
  but it would reopen "no rescue design needed".
- **An engine change to i31 or allocation costs** — the numbers carry
  the pinned versions; re-verify before building on them again.
- **A `=`/`hash` design that ends up needing boxed-vs-i31 equality
  anyway** (e.g. for boxed values above i31 compared to themselves) —
  that is expected and cheap; what would actually falsify
  canonicalization is a measured hot path where the re-boxing guard
  dominates, which B8c says does not exist today.
