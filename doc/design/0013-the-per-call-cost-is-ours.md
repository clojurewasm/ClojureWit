# 0013 — The per-call cost is on our side of the boundary

**Status:** accepted for *where* the cost is. The mechanism is narrowed to one
surviving explanation by seven eliminations, and is **not** confirmed · 2026-07-30

## The question

`0011` measured a component call from Clojure at ~1.9–2.1 µs and concluded:

> **What is expensive is one call into wasmtime, at ~1.57 µs** […] so if the
> 1.57 µs is the checked path's price, ~1.9 µs is the floor for component calls
> through this C API.

`doc/status.md` carried the consequence: *"the only part this project can
remove by its own choices is the ~20% the Component Model adds."* Both rest on
an attribution that had never been tested, because **nothing had ever called
wasmtime from anything but the JVM.**

## The decision

**The attribution was wrong. wasmtime's floor is ~15 ns, and ~96% of the
measured cost is on the JVM side of the boundary — which is to say, ours.**

Therefore: do **not** design `cljwit.host`'s API around an assumed
microsecond floor, and do **not** plan a Rust shim. Neither the Component
Model nor the C API is where the money is.

## Why

Measured 2026-07-30, Apple M4 Pro, pinned wasmtime 47.0.1, all in one session,
same module (`dev/resources/add.core.wasm`, `add(i32,i32)->i32`), same export:

| caller | entry point | ns/call | command |
|---|---|---:|---|
| Rust | `TypedFunc::call` | **15.2** | `cargo run --release` in `dev/spike/rust_call_cost` |
| C | `wasmtime_func_call_unchecked` | **15.5** | `bb spike-c-cost` |
| Rust | `Func::call` (`Val`) | **61.7** | as above |
| C | `wasmtime_func_call` | **75.4** | `bb spike-c-cost` |
| JVM/FFM | `wasmtime_func_call` | **1645** | `bb spike-cost` |
| JVM/FFM | component call | **2074** | `bb spike-cost` |
| JVM/FFM | trivial native call | **7.1** | `bb spike-cost` |

Read down the column, each step varying one thing:

- **wasmtime's own floor is 15 ns.** Rust's typed call and C's unchecked call
  agree to 2%, from different languages through different APIs. That is the
  number a host call actually costs.
- **`Val` marshalling costs 4×** — 15 → 62 in Rust, 15 → 75 in C. It is the
  same tax in both, so it is the convention's, not the C API's.
- **The C API is nearly free over Rust's own untyped path**: 75.4 against 61.7,
  1.2×. The API that `cljwit.host` binds to is not the bottleneck.
- **The JVM crossing costs 21×** — 75.4 → 1645, on the identical C entry point
  with the identical arguments.

So of the 2074 ns a component call costs today, ~15 ns is wasmtime, ~60 ns is
the `Val` convention, ~430 ns is the Component Model, and **~1570 ns is
whatever happens between Clojure and the C symbol.**

**The prediction that produced this was falsified.** Before running the C lane
this note's author predicted 800–1600 ns for it, reasoning that `0011` had
already measured a trivial FFM native call at 7.7 ns and so the JVM crossing
was exonerated. It measured 75.4. The reasoning was sound and the conclusion
was wrong, which is the case for running it.

**It also closes an open question from `0011`.** The unchecked path measured
2.4× *slower* than the checked one from the JVM, reproducibly, and `0011` said
to treat that as unexplained rather than as a fact about wasmtime. From C the
unchecked path is **4.9× faster** (15.5 vs 75.4), and from the JVM it is now
4491 ns — **290× the C figure**. It is a driving error in the Clojure spike,
not a property of `wasmtime_func_call_unchecked`.

## What this does *not* show — and what has been eliminated

**Where, not why.** But the "why" is now bounded rather than open. Each row
varies one thing against the row it is being compared with; all measured
2026-07-30 in the same session on the same machine.

| # | hypothesis | test | result | verdict |
|---|---|---|---|---|
| 1 | FFM crossings are simply slow | trivial 1-int downcall, same proxy binding | **7.4 ns** | eliminated |
| 2 | FFM is slow for *this signature* | no-op C function, seven params, five pointers, same proxy | **17.4 ns** | eliminated |
| 3 | the C API is the cost | `wasmtime_func_call` from C | **75.4 ns** | eliminated |
| 4 | it is any non-initial thread | the same C loop on a spawned `pthread` | **74.3 ns** | eliminated |
| 5 | the JVM's signal handlers interfere | `bb spike-cost` under `-Xrs` | **1662 vs 1657 ns** | eliminated |
| 6 | `dlopen` vs load-time linking (macOS lazy TLS) | C program that `dlopen`s libwasmtime | **75.3 ns** | eliminated |
| 7 | FFM taxes a callee that *runs* for a while | C function of the same shape doing 166 ns of arithmetic | **166.1 from C, 174.5 from the JVM — +8.4 ns** | eliminated |

Row 7 is the one that closes it. FFM's tax is a flat ~8–17 ns and does **not**
scale with how long the callee runs, so the 1570 ns is not the crossing, the
signature, the duration, the thread, the signals, or the loader.

**What survives: executing wasmtime's entry into JIT-compiled wasm on a JVM
thread specifically.** The leading untested mechanism is macOS arm64's
per-thread W^X state for `MAP_JIT` pages — the JVM manages that state for its
own code cache, and wasmtime executes its own JIT'd pages on the same thread.
It is a hypothesis, not a finding.

**The cheapest discriminator is one this project already owes.**
`doc/status.md` lists "every S0 number is arm64 macOS" as the untried axis.
x86_64 has no per-thread W^X, so **if the 21× survives on x86_64 Linux the W^X
hypothesis is dead, and if it vanishes the hypothesis is strongly supported.**
One run answers two open questions.

## Alternatives rejected

- **A Rust shim exposing a narrow C ABI**, which `0011` named as the lever.
  It would have been right if the C API were the cost. It is 75 ns; a shim
  could recover at most 60 of the 2074, and would add a Rust toolchain to a
  build that does not otherwise need one.
- **Accepting a microsecond floor and designing a coarse-grained API around
  it** — batching, chunked buffers, an explicit "call is expensive" contract in
  the public API. This is the expensive alternative, because an API shaped
  around a cost is hard to unshape once the cost goes away. Rejected precisely
  because the floor is not real.
- **Attributing the cost to the Component Model.** It adds ~430 ns of the 2074,
  about 21% — which `0011` had right, and which is the *only* part of its
  accounting that survived.
- **Doing nothing until S4.** Tempting, since no compiler exists yet. Rejected
  because `cljwit.host`'s API is next on the roadmap and would have been
  designed against a floor that is off by 25×.

## What would falsify this

- **The 21× failing to reproduce on x86_64 Linux, or on a JVM other than
  Temurin 25.** Every number here is arm64 macOS, which `doc/status.md` already
  lists as S0's untried axis.
- **A fresh clone measuring the C lane materially above 75 ns.** `bb
  spike-c-cost` builds and runs it; the Rust lane needs a toolchain the dev
  shell does not provide, and corroborates rather than carries the conclusion.

## Resources

- `dev/spike/call_cost.c` — the C control. `bb spike-c-cost [n] [reps]`.
- `dev/spike/rust_call_cost/` — the Rust lane, pinned to `wasmtime = "=47.0.1"`
  so it matches `tools.json`. Not built by `bb check`.
- `dev/cljwit/spike/call_cost.clj` — the JVM lane, from `0011`.
- `dev/spike/noop7.c` + `dev/cljwit/spike/ffm_shape.clj` — rows 2 and 7 of the
  elimination table. `bb spike-ffm-shape`.

Rows 5 and 6 were one-off commands rather than committed spikes, because a
negative result that closes a hypothesis does not need to be re-runnable:
`clojure -J-Xrs -M:dev -m cljwit.spike.call-cost core`, and a C program
identical to `call_cost.c` but resolving every symbol through `dlsym` on
`$CLJWIT_WASMTIME_LIB`.
