---
name: survey
description: Find out what is actually true outside this repository before building on it — spec phases, engine support, what other compilers did, whether a tool does what its docs claim. Use this when entering a new roadmap stage, when a decision would rest on an external fact, when a design note's dated claim is more than about three months old, and whenever the answer to "does this even work?" is currently an assumption. Reach for it before writing the design note, not after.
---

# Survey before you build on it

This project sits on a spec surface that moved twice in the last year, and its
premise — Clojure, WasmGC and the Component Model in one artifact — sits on a
seam that is actively moving underneath it. Being confidently wrong about the
outside world is the cheapest way to waste a stage here.

A survey is done when you can say what is true, **with a date, a URL, and
something you ran yourself** — at the level of confidence the decision needs,
and no further.

## When

- **Entering a roadmap stage.** Its design was written against facts that were
  current when `doc/roadmap.md` was written, not now.
- **Before a design note.** The note carries the external claims it rests on;
  they have to be checked before they are written down, not after.
- **When a dated claim is going stale.** Roughly three months. Design notes
  carry dates so this is answerable.
- **When you are about to say "X does not support Y".** That sentence is wrong
  more often than any other in this domain.

## Four kinds of evidence, weakest first

Not a checklist to complete — a ladder to climb until the decision is
supported. Where they disagree is usually the finding.

- **Search.** Establishes the shape and finds the primary sources. Blog posts
  and summaries are pointers, never the claim.
- **The primary source.** The proposal issue, the spec text, the release notes,
  the tracking bug. Note its **phase and its date** — "pre-proposal", "Phase
  4", "in the spec", "shipped in v43" are different worlds, and a feature can
  sit in several at once.
- **What someone who solved it wrote down.** `bb ref` clones the reference
  sources into `.ref/`; `refs.json` says what each is for. Read **decisions and
  post-mortems, not code** — the siblings' value is what went wrong, and their
  conclusions were reached under different constraints (`.claude/CLAUDE.md`).
- **Running it.** The strongest, and the one most often skipped. Build the
  smallest artifact that would fail if the claim were false.

**One run characterises one path, not the domain.** `doc/design/0007-*` is the
worked example of both halves: three `wasm-tools component new` invocations
produced an exact requirement list *and* a false generalisation, because
`component new` was the only path tried and it has no GC support to find. The
correction came from hand-writing the thing `component new` will not emit. If a
run gives you a negative result, ask what it was incapable of showing you.

## What comes out

Findings go where the existing three durable files already put things — a
survey does **not** get a file of its own.

- **A fact a decision rests on** → the design note that rests on it, with its
  date and URL, in the note's own "why".
- **A fact that changes the plan** → a new design note, plus `doc/status.md`.
- **A fact that changes nothing** → the date on the existing claim, refreshed
  in place. Say when you checked, so the next reader knows.
- **A source worth returning to** → `refs.json`, with a one-line `why`.

## Two failure modes specific to surveying

The general ones — no local paths, siblings' conclusions don't transfer — are
already in `.claude/CLAUDE.md` and `/next`. These two are not:

- **Negative claims rot fastest.** "wasmtime does not support WasmGC" is still
  repeated in current-looking material; wasmtime 47 runs this repo's
  benchmarks. Check "X does not support Y" hardest, and prefer to check it by
  trying X.
- **Surveying the layer you are working on, not the one underneath.** S0
  benchmarks dispatch inside a module, and was written without anyone asking
  whether the module could be a component at all.

## Prior art: two halves, different references

Checked 2026-07-29. For "who has done this before", the instinct is Rust and
Go — they are one half of the problem, and knowing which half saves a stage.

| | compiles to WasmGC | ships Components / WIT |
|---|---|---|
| Rust | no — linear memory ([no GC target], LLVM has no WasmGC backend) | **yes, best in class** — `wit-bindgen`, native `wasm32-wasip2/p3` |
| TinyGo | no | yes — `-target=wasip2` |
| Go (upstream) | no — [golang#63904] is *Unplanned*; interior pointers are the blocker | no — only `GOOS=wasip1`; components come from `componentize-go` |
| **Dart** (`dart2wasm`) | **yes** | in progress — SDK issue open, but `pkg/wasm_tools` (2026-06) is a real WIT bindgen |
| **Kotlin/Wasm** | **yes** | in progress — [KT-64569] assigned, planned 2.5.0-Beta1 |

So **Rust and TinyGo are the reference for the boundary**, and **Dart and
Kotlin are the reference for the codegen** — a GC'd language lowering its
object model onto WasmGC, which is `0004`'s problem.

**Read [Kotlin/sample-wasi-http-kotlin] before designing S4.** It is an
official JetBrains prototype that takes a Kotlin/Wasm (WasmGC) module through
`wasm-tools component embed`/`new` with a preview1 adapter and runs it on
`wasmtime serve -W gc,exceptions,function-references`. That is the closest
existing artifact to what this project is building, on both halves at once. An
earlier version of this file asserted nobody had done both; that was wrong, and
it was wrong in the direction that would have cost the most.

[no GC target]: https://discourse.llvm.org/t/wasmgc-implementation-status/74821
[golang#63904]: https://github.com/golang/go/issues/63904
[KT-64569]: https://youtrack.jetbrains.com/issue/KT-64569
[Kotlin/sample-wasi-http-kotlin]: https://github.com/Kotlin/sample-wasi-http-kotlin
