# 0001 — Scope and shape

**Status:** accepted · 2026-07-29

## What we are building

A compiler from Clojure to WebAssembly components, and a library for calling
components from Clojure. The unifying claim is the *interface*: WIT is a
language-neutral contract, and ClojureWit puts Clojure on both sides of it.

## Why the interface is the headline, not the compiler

"Compile Clojure to Wasm" is a claim several projects could make. What nobody
has is the other direction:

- Clojure implementations with their own runtime (jank, ClojureWasm) are not
  targeting Wasm as an output.
- Lisps targeting Wasm (Guile Hoot) are not Clojure.
- **Clojure has never been callable *from* another language ecosystem.** JVM
  Clojure is callable from the JVM; ClojureScript from JavaScript. A component
  is callable from anything.

So the framing is "Clojure joins the component ecosystem", and the compiler is
the means.

## Why now, specifically

| | Date | What became true |
|---|---|---|
| Wasm 3.0 → W3C standard | 2025-09 | GC, tail calls, exception handling |
| WasmGC baseline in browsers | 2024-12 | Chrome 119+, Firefox 120+, Safari 18.2+ |
| WASI 0.3.0 | 2026-06-11 | Native async in the Component Model; `wasi:io` folded into the Canonical ABI |

WASI 0.3 is weeks old at the time of writing. An earlier start would have been
building on proposals.

## Two deliverables, in this order

1. **`cljwit.host`** — call components from JVM Clojure. No dialect. Ships first
   because it is independently useful and because it forces the WIT ↔ Clojure
   type mapping, which the compiler needs anyway.
2. **`cljwit`** — the compiler.

If (2) never happens, (1) still has users. That is the point of the ordering.

## Alternatives rejected

- **Extend ClojureWasm instead of starting a project.** ClojureWasm is a Zig
  runtime that executes Wasm; this is a JVM-hosted compiler that emits it.
  Different host, different lifecycle, different users. Sharing a repo would
  have coupled two things whose only overlap is the word "Wasm".
- **Write the compiler in Rust** to use `wasm-tools` natively. Rejected: the
  hard part is Clojure semantics, not Wasm encoding, and writing it in Clojure
  buys the entire Clojure toolchain (see `0005`). Wasm encoding is a bounded
  problem we can solve by emitting WAT first.
- **Target core Wasm with our own GC** (the ClojureWasm approach). Rejected:
  WasmGC exists now, and shipping a GC costs both binary size and the engine's
  ability to optimize across our allocations.
