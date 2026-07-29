---
paths:
  - "bench/**"
  - "doc/design/0002-measure-first.md"
---

# Measurement

> For the toolchain invocations themselves — `wasm-tools`, `wasm-opt`, `node`,
> `wasmtime`, and the feature flags that go wrong silently — use the **`wat`
> skill** (`/wat`). This rule is about whether a number means anything; that
> skill is about producing one.

This project decides what to build by measuring. A number that cannot be
reproduced is worse than no number, because it gets quoted.

## Every reported number carries

1. **The command that produced it**, runnable from a fresh clone.
2. **The machine and versions** — CPU, OS, and the versions of every tool
   involved (`bb check-tools` prints them).
3. **The comparison baseline**, measured on the same machine in the same run.
   Cross-machine comparisons are not comparisons.

## Gotchas

- **Time numbers are unstable; size numbers are stable.** Report the median of
  at least 20 runs, and the minimum, and say which. A single-shot timing under
  load has produced phantom 30% improvements in a sibling project.
- **The first run of a fresh binary on macOS pays code-signing verification.**
  Discard warm-up runs explicitly rather than hoping.
- **V8 and wasmtime will disagree, and that is the finding, not noise.** V8
  speculatively inlines and deoptimizes; wasmtime has no adaptive tier. Always
  report both. A number from only one is a number about that engine.
- **Do not benchmark through a shell loop for anything under ~10 ms.** Process
  spawn dominates. Loop inside the guest.
- **Beware benchmarking the optimizer away.** `wasm-opt` and V8 will delete
  work whose result is unused. Consume the result — accumulate it and print it.
- **State the prediction first.** `doc/design/0002-measure-first.md` records
  predictions before runs, and the prediction column is never edited afterwards.

## When the number disagrees with the design

Amend the design note and say which prediction failed. That is the mechanism
working, not a setback.
