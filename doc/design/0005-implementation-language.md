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

## The S1 host path, surveyed 2026-07-30

This note assumes a JVM reaching Wasm through FFM and wasmtime. Entering S1
means checking that, because `0007` is what happens when a stage is entered on
an unchecked premise.

**It holds, and the margin is narrower than it looks.** wasmtime's C API gained
the component model between v40 and v43 — measured by counting exported symbols
in the shared library the flake pins, not by reading release notes:

| wasmtime | `wasmtime_component_*` exported |
|---|---|
| 40.0.2 | **0** |
| 43.0.1 | 138 |
| 45.0.0 | 154 |
| 47.0.1 | 154 |

`tools.json` already requires ≥ 43.0.0, set for WASI 0.3 — the same version
that first ships the component C API. That coincidence is load-bearing and
should not be lowered.

From the JVM, `java.lang.foreign.SymbolLookup` on Java 25 resolves
`wasmtime_component_new`, `_linker_new`, `_linker_instantiate`,
`_instance_get_func` and `_func_call` out of that library. So the path exists
end to end; what it does *not* yet show is a value crossing it, which is S1's
first unit rather than a survey probe.

**One constraint surfaced that S1 has to answer:** finding
`libwasmtime.dylib` needed an absolute store path, and `.claude/CLAUDE.md`
forbids machine-specific paths in anything committed. How `cljwit.host` locates
the library — bundled, `pkg-config`, an env var, a documented convention — is
an open S1 design question, not a detail.

**The alternative worth watching: [Endive].** A Bytecode Alliance–hosted fork of
[Chicory] (a pure-Java Wasm runtime) that adds a Cranelift backend and targets
full Component Model support on the JVM **with no native dependency**. It does
not have the component model yet — Cranelift and WasmGC come first — and
Chicory itself lists GC on its 2026 roadmap with no component model at all. So
today the native path is the only one, and a pure-JVM path is plausibly coming.
That matters for `cljwit.host`'s deployment story and should be re-checked at
S1's design, not assumed either way.

[Endive]: https://bytecodealliance.org/articles/endive-and-the-next-chapter-of-webassembly-on-the-jvm
[Chicory]: https://github.com/dylibso/chicory

## Consequences

- Development requires a JVM; deployment does not. This must be stated plainly
  wherever we describe the project, because "Clojure to Wasm" invites the
  assumption that no JVM is involved anywhere.
- We inherit ClojureScript's analyzer semantics, including its quirks. Where
  they diverge from JVM Clojure, that is a divergence we own and document.
- `bb` (babashka) is the task runner, so contributors without a warm JVM can
  still run the toolchain checks.
