# 0005 — The compiler is written in Clojure, and runs on the JVM

**Status:** accepted · 2026-07-29

## The question

What language hosts the compiler, and does a JVM at build time disqualify us?

## The decision

**Clojure, on the JVM.** The compiler is an ordinary Clojure project. Generated
artifacts contain no JVM and no Clojure runtime beyond what we emit.

## Why

**A JVM at build time costs nothing at run time, and buys the entire
ecosystem.** ClojureScript, shadow-cljs, and ClojureDart are all this shape.

The sibling ClojureWasm project is the control group. Because it is a
standalone Zig binary, it had to implement, itself: an nREPL server from bencode
up (and then re-architect it for CIDER fidelity), deps.edn resolution, editor
completion and arglists, a REPL, and bundled reimplementations of
`clojure.test`, `tools.cli`, and `data.json`. Every one of those is free here,
and every one of them is a place where behaviour can differ from real Clojure.

**The frontend already exists.** ClojureScript's analyzer and emitter are
separate files, and the analyzer does not depend on the emitter:

| file | lines | our use |
|---|---|---|
| `cljs/analyzer.cljc` | 5,184 | **reuse** — its only mention of `cljs.compiler` is a comment |
| `cljs/compiler.cljc` | 1,969 | **replace** — this is the WasmGC emitter's slot |
| `cljs/core.cljs` | 13,183 (86 `deftype`) | **port** — the collections, in Clojure |

So "reuse the analyzer, swap the emitter" is a real file boundary, not an
aspiration. The work is on the order of the emitter, not the whole frontend.

**Macros just work.** A compiler written in Clojure runs Clojure macros to
expand Clojure source. Any other host reimplements macroexpansion.

## Alternatives rejected

- **Rust**, for native access to `wasm-tools`. The hard part of this project is
  Clojure semantics, not binary encoding — and encoding has an escape hatch:
  emit WAT text and hand it to `wasm-tools parse`. A binary encoder is a
  bounded later task. Rust would also put the project outside the reach of the
  people most likely to contribute to it.
- **Zig**, matching the sibling project. Same objection as Rust, plus Zig
  cannot itself emit WasmGC (reference types remain unimplemented), so it
  offers no help with the one thing we need.
- **Self-hosted from day one.** Attractive, and the right end state, but it
  requires the compiler to exist before the compiler exists. Revisit once S3
  can compile the analyzer.

## Consequences

- Development requires a JVM; deployment does not. This must be stated plainly
  wherever we describe the project, because "Clojure to Wasm" invites the
  assumption that no JVM is involved anywhere.
- We inherit ClojureScript's analyzer semantics, including its quirks. Where
  they diverge from JVM Clojure, that is a divergence we own and document.
- `bb` (babashka) is the task runner, so contributors without a warm JVM can
  still run the toolchain checks.
