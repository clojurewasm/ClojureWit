# 0003 — Where optimization happens

**Status:** accepted · 2026-07-29

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
wasmtime existed**, and the browser's L3 is treated as a bonus. Designing the
other way round produces something that is fast in a demo and slow in
production.

This also identifies where offline profile-guided optimization would pay:
precisely on the server, where the engine will not do it for us. That is a
later lever, recorded here so it is not rediscovered.

## Consequence for our own plans

We do not write a JIT. The sibling ClojureWasm project has one on its roadmap
because it owns its engine; we do not own ours, and on the browser path we get
a better one for free.
