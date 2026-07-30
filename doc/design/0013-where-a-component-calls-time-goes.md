# 0013 — Where a component call's time actually goes

**Status:** accepted · 2026-07-30

> **This note's first version was wrong at the root and is rewritten here rather
> than kept.** It claimed the JVM crossing cost 21× and built a seven-row
> elimination table on top of a number that was an artifact of the measuring
> instrument. What went wrong is recorded at the bottom, because the mechanism
> is more useful than the retraction. The file was renamed from
> `0013-the-per-call-cost-is-ours.md`, whose title was the false claim.

## The question

`0011` measured a component call from Clojure at ~1.9–2.1 µs and concluded:

> **What is expensive is one call into wasmtime, at ~1.57 µs** […] so ~1.9 µs
> is the floor for component calls through this C API.

`doc/status.md` carried the consequence: *"the only part this project can
remove by its own choices is the ~20% the Component Model adds."* Neither had
ever been tested against a non-JVM caller.

## The decision

**wasmtime's floor is ~15 ns, the JVM adds ~27–40 ns, and the Component Model
is ~79% of a component call — not the 20% `0011` claimed.** And that 79% is
**not a lever**: it survives the best API wasmtime offers, so it is the
Canonical ABI's price, not a binding choice. `cljwit.host` should be designed
knowing a component call costs ~0.3–0.4 µs and that nothing it does will make
it 15 ns.

## Why

Measured 2026-07-30, Apple M4 Pro, pinned wasmtime 47.0.1, same module
(`dev/resources/add.core.wasm`, `add(i32,i32)->i32`), same export. `bb
spike-c-cost`, `bb spike-cost`, and `cargo run --release` in
`dev/spike/rust_call_cost`:

| entry point | Rust | C | JVM/FFM |
|---|---:|---:|---:|
| core, typed / `func_call_unchecked` | **15.2** | **15.7** | 46.1 |
| core, untyped `Val` / `func_call` | 58.7 | **74.1** | 102.5 |
| component, typed | **279.4** | *no typed path* | *no typed path* |
| component, untyped `Val` | 309.4 | **352.9** | **392.8** |

- **wasmtime's floor is 15 ns.** Rust's typed call and C's unchecked call agree
  to 2%, from different languages through different APIs.
- **The `Val` convention costs 4–5×** — 15 → 62 in Rust, 15 → 75 in C. The same
  tax in both, so it belongs to the convention, not the C API.
- **The JVM adds a flat ~27–31 ns**: 75.4 → 102.5 checked, 15.5 → 46.1
  unchecked. The constancy across two independent entry points is what makes it
  credible. A no-op C function with `wasmtime_func_call`'s exact signature —
  seven parameters, five pointers — costs 17.4 ns through the same proxy
  binding (`bb spike-ffm-shape`), so ~17 ns of that is FFM and the rest is the
  result read.
- **`cljwit.host` roughly doubles it again.** The 392.8 ns is the spike's:
  a proxy-bound handle, one `wasmtime_component_func_call`, nothing else. The
  library measures **807.7 ns** for `add(17, 25)` — it binds through
  `invokeWithArguments` rather than an interface proxy, runs a compiled
  marshaller per argument, takes the non-concurrency CAS, and opens a confined
  arena per call. So **the library adds ~415 ns, more than the Component Model
  charges.** Recorded rather than optimised: `0011` measured the proxy at 7 ns
  against `invokeWithArguments`' 396, so the binding is the obvious first
  suspect and the obvious first thing to try.
- **The Component Model costs 4.8×** — 74.1 → 352.9 measured entirely within C,
  so it is not a cross-lane artifact and not the JVM's. It is about **79%** of a
  component call. The JVM's 392.8 sits ~40 ns above C's 352.9, which is the same
  flat tax as on the core path.
- **Typing the component call recovers ~10%, not the 4.8×** — 309.4 → 279.4 in
  Rust, the only place a typed component path exists. On the *core* path typing
  recovers 75% (58.7 → 15.2). **The Component Model's cost is the Canonical ABI
  itself — lift and lower — not the `Val` boxing.** 279 ns is the floor with the
  best API wasmtime offers, against 15 ns for a core call: **18×**.

**`wasmtime_func_call_unchecked` is the fast path**, as its header says: 2.2×
faster than checked from the JVM, 4.9× from C. `0011` recorded it as
*reproducibly 2.4× slower* and left the contradiction with the documentation
open. There was no contradiction.

**`wasmtime_component_func_post_return` is deprecated and a no-op in 47.0.1.**
The header: *"No longer needs to be called; this function has no effect […]
that's taken care of automatically as part of `wasmtime_component_func_call`."*
`0011`'s finding that adding it "makes the number worse by roughly one more
`invokeWithArguments`" was measuring the harness.

## Alternatives rejected

- **Attributing the cost to wasmtime or to the C API** — `0011`'s conclusion.
  wasmtime is 15 ns and the C API is 1.2× Rust's own untyped path.
- **Shaping `cljwit.host`'s API around a microsecond floor** — batching,
  chunked buffers, an explicit "calls are expensive" contract. There is no such
  floor: a component call is ~0.4 µs, and an API shaped around a cost is hard
  to unshape once the cost goes away.
- **Blaming the JVM.** It adds 27 ns. This is what the first version of the
  note got wrong.
- **A Rust shim, for speed.** The first version rejected it on arithmetic that
  was void; it is now rejected on a measurement. The best case for a shim is
  Rust's typed component call, and that is 279.4 ns against the 392.8 the JVM
  pays today — **~113 ns, ~29%**, of which ~40 ns is the JVM tax a shim does not
  remove and ~44 ns is the C API's. A shim buys ~10–20% and costs a Rust
  toolchain in the build. The speed argument is closed.

  The *safety* argument is not, and is now the only one left: the spike carries
  hand-measured struct offsets where being wrong is a segfault that takes the
  JVM down (`0011` constraint 3). That is a different decision from this one and
  belongs in `cljwit.host`'s API note.

**A second prediction was falsified here.** Rust's typed component call was
predicted at 100–200 ns on the reasoning that typing removes the `Val` boxing,
which is worth 4× on the core path. It measured 279.4. The boxing is not what
the Component Model charges for; the Canonical ABI is. The core path's ratios
did not transfer to the component path, which is the same mistake as assuming a
JVM measurement says something about wasmtime.

## What would falsify this

- **The "15 ns floor" outside its shape.** Two i32 parameters, one i32 result,
  one export, no linear memory, no imports, no traps, warm reused `Store`,
  single thread. Nothing about strings, lists, `cabi_realloc` or resources —
  which is what `cljwit.host` will actually do.
- **Cross-process spread.** The identical core lane measured 1633, 2881 and
  1558 ns across three processes before the fix; `0011` recorded 1561–2842.
  Within a run it holds to ~1.01–1.13×. **No three-significant-figure number
  here should be quoted across processes.**
- **Any of it on x86_64 Linux**, which nothing has tried — `doc/status.md`
  already owes S0 that axis.


## What went wrong, and what now prevents it

`dev/cljwit/spike/call_cost.clj` declared `(def ^:private I32
ValueLayout/JAVA_INT)` with no type hint. `MemorySegment.get` is heavily
overloaded, so `(.get res I32 (long 8))` — reading the result once per
iteration in the hot loop — could not be resolved statically and compiled to
`Reflector.invokeInstanceMethod`. **One reflective four-byte read costs
~1470 ns.** The lanes differ only in how many they do:

| lane | reflective ops/call | reported | corrected |
|---|---:|---:|---:|
| core checked | 1 | 1645 | 102.5 |
| core unchecked | 3 | 4491 | 46.1 |
| component | 1 | 2074 | 392.8 |

92 + 3 × 1470 ≈ 4500 against the 4491 measured. The "290× anomaly" was
arithmetic.

Two failures compounded:

- **Every row of the elimination table varied the callee or the environment —
  none varied the Clojure in the loop.** The one row that accidentally did
  (`noop7`, which read its result with a hinted `.address`) measured 17 ns and
  was read as answering a different question. This is the fourth instance of
  the standing "one experiment characterises one variant" constraint, and the
  first where the uncontrolled variable was in the *instrument* rather than the
  subject. A negative result about the callee could never have shown it.
- **The independent critique `/next` requires was launched and then not waited
  for.** It found this. The wrong version is in the history because the commit
  did not wait for it; the note is right now because the review happened.

**Mechanized:** `bb reflection` fails the gate if any namespace resolves a call
reflectively, and is part of `bb check`. It found eleven more sites beyond the
one that caused this. `clj-kondo` does not catch it and `*warn-on-reflection*`
was off in every namespace in the repo.

## Resources

- `dev/spike/call_cost.c` — the C control. `bb spike-c-cost [n] [reps]`.
- `dev/spike/rust_call_cost/` — the Rust lane, pinned to `wasmtime = "=47.0.1"`.
  Needs a Rust toolchain, which the dev shell does not provide; it corroborates
  the C control rather than carrying it.
- `dev/spike/noop7.c` + `dev/cljwit/spike/ffm_shape.clj` — FFM's own tax, by
  signature shape and by callee duration. `bb spike-ffm-shape`.
- `dev/cljwit/spike/call_cost.clj` — the JVM lane, from `0011`.
