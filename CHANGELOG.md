# Changelog

Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
This project is pre-alpha; nothing is stable and there are no releases yet.

## [Unreleased]

### Added
- Repository, toolchain, and design notes. No compiler yet — see
  `doc/roadmap.md` for what has to be true before one is worth writing.
- `bb check` as the single gate, run identically by developers and CI.
- A pinned toolchain: `flake.nix` for exactness, `tools.json` as the shared
  version SSOT for machines without Nix.
- **`doc/design/0008`** — parity is at the boundary: if a Clojure program
  cannot observe the difference, the internals are ours to choose.
- **`doc/design/0009`** — two modes, dynamic in development and static in
  production, designed together because the output-format constraints they
  force cannot be retrofitted. Rests on a measured fact, now pinned in the gate
  by `test/cljwit/rec_group_identity_test.clj`: independently compiled units
  share WasmGC types iff their rec groups canonicalise alike.
- **A rewritten S0 stop condition** — dispatch overhead under 1 ns, absolute,
  per lane. Replaces "~10× JVM Clojure" (which passed B1 at 5.6×) and a
  ratio-to-the-engine's-floor draft (which rewarded a slow floor). Under it B1
  passes on V8 and **fails on wasmtime**. `doc/design/0003` gained the survey
  behind it: the engine ranking inverts with the workload.
- **`/survey`** — the reconnaissance discipline: search, read the primary
  source, read what someone who solved it wrote down, then *build the smallest
  thing that would fail if the claim were false*. Run on stage entry. `bb ref`
  gained the reference sources it needs.
- **`doc/design/0007`** — the component boundary is linear memory, not GC.
  Found by the first survey; sizes S4 and adds an open question to the roadmap.
- **S0 B1** — hand-written WasmGC protocol dispatch, measured on V8 and
  wasmtime against a JVM Clojure baseline, with three controls forming a curve
  in how far the call target sits from the receiver. `bb bench-s0` runs it.
  First real numbers in the project: `doc/design/0002-measure-first.md`.
