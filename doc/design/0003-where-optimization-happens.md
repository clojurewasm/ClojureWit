# 0003 — Where optimization happens

**Status:** accepted, amended 2026-07-29 · 2026-07-29

> **Amendment — both lanes are primary, and "near-native" has to be defined
> per workload.** This note originally treated the browser as a bonus. That is
> withdrawn: browser *and* server are both primary targets, the latter with
> Wasm edge platforms (Cloudflare Workers, Fastly Compute, Fermyon Spin /
> SpinKube) in view. The design rule below — **design L1 as if only wasmtime
> existed** — survives unchanged, because it was always the conservative
> direction; what changes is that the browser result is now a requirement to
> hold, not a windfall. See "What near-native actually costs" at the end.

The question this answers: if we are compiling ahead of time, how much
optimization is our job, and how much does the engine do?

## Three layers, with measured contributions

| Layer | Who | What | Measured effect |
|---|---|---|---|
| **L1 language-specific** | our compiler | macroexpansion, direct-linking vars, monomorphizing protocols, numeric type inference, **shaping call sites to have few targets** | — |
| **L2 general Wasm→Wasm** | `wasm-opt` (Binaryen) | escape analysis, load elimination, `ref.cast` removal, DCE, inlining | **1.9×** (V8 team, on Box2D/DeltaBlue/RayTrace/Richards) |
| **L3 adaptive** | the engine | **profile-driven speculative `call_indirect` inlining with deoptimization**, type feedback, devirtualization | **+30%** on Google Sheets' calc engine; **up to +50%** on WasmGC microbenchmarks (Chrome M137+) |

## The consequence for L1

The intuition "Wasm is a target for statically compiled languages, so we must
devirtualize everything ourselves" is **out of date**. V8's guidance:

> indirect calls are noted as they occur at runtime, and if we see that a call
> site has **fairly simple behavior (few call targets)**, then we inline there
> with appropriate guard checks

So L1's job is **not** to eliminate indirect calls. It is to make each call
site's target set *small*, so the engine's speculation fires. Concretely:
one Wasm function type per arity rather than one universal signature; protocol
dispatch through a single table rather than scattered paths.

There is independent evidence against the opposite approach — an AOT JavaScript
compiler that added inline caches via dynamic binary modification found the
gains minimal for the complexity, and the paper is titled *"The False Lead of
Optimizing Inline Caches"*. Its conclusion is that AOT should invest in static
analysis, type inference, specialization, and offline profile data — not in
imitating a JIT.

## The asymmetry that shapes the design

| | browser (V8) | server (wasmtime) |
|---|---|---|
| adaptive optimization | yes | **no** |
| inliner | yes | landed in v36, **off by default, still maturing** |
| type feedback | yes | no |

The same `.wasm` is treated very differently. **So L1 is designed as if only
wasmtime existed** — not because the browser matters less (both lanes are
primary; see the amendment at the top) but because it is the conservative
direction: L1 must carry the server lane alone, and the browser's L3 then
compounds with it rather than being relied on. Designing the
other way round produces something that is fast in a demo and slow in
production.

This also identifies where offline profile-guided optimization would pay:
precisely on the server, where the engine will not do it for us. That is a
later lever, recorded here so it is not rediscovered.

## Consequence for our own plans

We do not write a JIT. The sibling ClojureWasm project has one on its roadmap
because it owns its engine; we do not own ours, and on the browser path we get
a better one for free.

## What near-native actually costs — surveyed 2026-07-29

The goal is "roughly native", not "within 10× of the JVM". Two things about
that target were not obvious and change what can be promised.

### The engine ranking inverts with the workload

Published 2026 figures for the libsodium benchmark suite — CPU-bound C over
linear memory — against native ([00f.net, 2026-06-23]). **Baseline builds**,
which is the only like-for-like column, with the author's best-supported build
alongside where it differs:

| runtime | × native, baseline | with `wide_arithmetic` |
|---|---|---|
| Wasmer 7.1.0 | 2.08 | **1.33** |
| Wasmtime 46 | 2.41 | **1.46** |
| WAMR 2.4.4 (AOT) | 1.57 | — |
| WasmEdge 0.17 (AOT) | 1.74 | — |
| Wazero 1.12 | 4.72 | — |
| Node 26.3.1 | 7.95 | — |
| Bun 1.3.14 | 8.77 | — |

`wide_arithmetic` is a non-default extension that only Wasmer and Wasmtime
implement, so quoting 1.33 against Node's 7.95 varies two things at once. The
author also measured WAVM as the fastest 2026 baseline and notes `wasm2c` is
"hard to beat"; both are omitted above only because they are not deployment
targets here. Caveats worth carrying: an AMD Ryzen AI 9 HX 470 with **boost
disabled at a 2 GHz cap**, and `ITERATIONS=3`, which the author flags as noisy.

Against that, our own B1 — a WasmGC pointer chase through an indirect call on
an Apple M4 Pro at full boost — ranks them the other way: V8 0.87 ns against
wasmtime's 8.43, **V8 9.8× faster**, where libsodium has Node **3.3× slower**
than wasmtime baseline-to-baseline.

**These are different machines and different baselines, so the magnitudes do
not compose** — `.claude/rules/measurement.md` is explicit that cross-machine
comparisons are not comparisons. What survives is the direction, and the
direction is the finding: **there is no faster engine, only a faster engine per
workload.** Straight-line compute over linear memory is what AOT compilers are
for; dispatch-heavy GC code is what speculative inlining is for, and Cranelift
has no adaptive tier at all. A Clojure program contains both kinds of code, so
any single-number performance claim about this project is wrong in one of the
two directions.

An earlier version of this section explained the gap as "V8 never gets to its
good tier on a short run". That is contradicted by this repo's own B1, where V8
reached its top tier and beat everything. The explanation is the workload, not
the warm-up.

### What choosing WasmGC costs in runtime choice — less than expected

Checked 2026-07-29:

| runtime | WasmGC |
|---|---|
| browsers (V8 / SpiderMonkey / JSC) | yes — [baseline 2024-12-11] (Chrome 119 / Firefox 120 shipped 2023-11; Safari 18.2 completed the set) |
| wasmtime | yes — verified here; `bb bench-s0` runs on 47.0.1 |
| WasmEdge | yes — GC for interpreter and AOT/JIT both marked complete, Wasm 3.0 default since 0.16.0 ([ROADMAP]); one "runtime GC support" item is still open |
| WAMR | yes with `WAMR_BUILD_GC=1`, **off by default** and not spec-complete; **absent from fast-jit and multi-tier-jit**, but present in **AOT** — the 1.57× mode |
| Wasmer | **no** — its `Features` struct has no GC field and its backend matrix no GC row |
| Wazero | **no** — [wazero#1860] still open |

An earlier version of this note claimed WasmGC "excludes the runtimes that
produce those numbers" and that 1.33× was unreachable by construction. **That
is false, and this table falsifies it**: of the four runtimes in the
near-native band, three support WasmGC. The one excluded is Wasmer, 9% ahead
of wasmtime — a margin, not a class. Wazero is also excluded and is 4.72×
anyway.

**What none of these numbers say** is the thing we actually need: they are all
for linear-memory code, and not one of them measures what WasmGC allocation,
barriers, and casts cost relative to native. Transferring a linear-memory ratio
to a GC-heavy Clojure runtime is unsupported in either direction. That is a gap
in the survey, not a conclusion from it.

### What that makes the honest target

Not a multiple of native — nothing above licenses one for GC code — and not a
multiple of JVM Clojure. **An absolute budget on the overhead that is ours**:
dispatch cost minus the same walk's direct call, on the same lane and build.
`doc/roadmap.md`'s S0 stop condition was rewritten on 2026-07-29 to say so. The
old "~10× JVM Clojure" could not discriminate a good design from a bad one, and
a *ratio* to the engine's floor — the first attempted replacement — rewarded a
slow floor.

[baseline 2024-12-11]: https://webstatus.dev/features/wasm-garbage-collection
[ROADMAP]: https://github.com/WasmEdge/WasmEdge/blob/master/docs/ROADMAP.md
[wazero#1860]: https://github.com/tetratelabs/wazero/issues/1860

[00f.net, 2026-06-23]: https://00f.net/2026/06/23/webassembly-runtimes-2026/
